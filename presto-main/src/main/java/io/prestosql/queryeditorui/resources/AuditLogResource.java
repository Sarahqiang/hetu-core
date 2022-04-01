/*
 * Copyright (C) 2018-2020. Huawei Technologies Co., Ltd. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.queryeditorui.resources;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.prestosql.eventlistener.EventListenerManager;
import io.prestosql.protocol.ObjectMapperProvider;
import io.prestosql.security.AccessControl;
import io.prestosql.security.AccessControlUtil;
import io.prestosql.server.HttpRequestSessionContext;
import io.prestosql.server.ServerConfig;
import io.prestosql.spi.security.GroupProvider;
import org.eclipse.jetty.server.Response;

import javax.inject.Inject;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.google.common.base.Strings.emptyToNull;
import static java.util.Objects.requireNonNull;

@Path("/v1/audit")
public class AuditLogResource
{
    private final AccessControl accessControl;

    private final ServerConfig serverConfig;
    private final GroupProvider groupProvider;
    private final EventListenerManager eventListenerManager;

    @Inject
    public AuditLogResource(AccessControl accessControl,
                            ServerConfig serverConfig,
                            GroupProvider groupProvider,
                            EventListenerManager eventListenerManager)
    {
        this.accessControl = requireNonNull(accessControl, "httpServerInfo is null");
        this.serverConfig = requireNonNull(serverConfig, "httpServerInfo is null");
        this.groupProvider = requireNonNull(groupProvider, "groupProvider is null");
        this.eventListenerManager = requireNonNull(eventListenerManager, "eventListenerManager is null");
    }

    @POST
    @Path("/{type}")
    public void getAuditLog(
            @FormParam("user") String username,
            @FormParam("beginTime") String beginTime,
            @FormParam("endTime") String endTime,
            @FormParam("level") String level,
            @PathParam("type") String type,
            @Context HttpServletResponse response,
            @Context HttpServletRequest servletRequest) throws IOException
    {
        //if the user is admin, don't filter results by user.
        Optional<String> filterUser = AccessControlUtil.getUserForFilter(accessControl, serverConfig, servletRequest, groupProvider);

        if (filterUser.isPresent()) {
            response.setStatus(Response.SC_FORBIDDEN);
            return;
        }
        String inputUsername = emptyToNull(username);
        String inputBeginTime = emptyToNull(beginTime);
        String inputEndTime = emptyToNull(endTime);
        String inputLevel = emptyToNull(level);

        List<String> logFiles = getLogFiles(type, inputBeginTime, inputEndTime, inputUsername, inputLevel);
        if (logFiles.isEmpty()) {
            response.setStatus(Response.SC_NOT_FOUND);
            return;
        }
        response.reset();
        response.setCharacterEncoding("utf-8");
        response.setContentType("application/json");
        ServletOutputStream out = response.getOutputStream();
        List<String> resLog = new ArrayList<>(100);
        int auditLogLimit = 99; // show 100 logs in webUI
        for (int i = logFiles.size() - 1; auditLogLimit >= 0 && i >= 0; i--) {
            BufferedReader br = new BufferedReader(new FileReader(logFiles.get(i)));
            String str = null;
            while ((str = br.readLine()) != null) {
                resLog.add(str + System.lineSeparator());
                auditLogLimit--;
                if (auditLogLimit < 0) {
                    break;
                }
            }
            br.close();
        }
        ObjectMapper objectMapper = new ObjectMapperProvider().get();
        objectMapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        out.println(objectMapper.writeValueAsString(resLog));
        out.flush();
        resLog.clear();
    }

    //downloadLogFiles, return compressed package in. Zip format
    @GET
    @Path("/download")
    public void downloadLogFiles(
            @QueryParam("user") String username,
            @QueryParam("beginTime") String beginTime,
            @QueryParam("endTime") String endTime,
            @QueryParam("level") String level,
            @QueryParam("type") String type,
            @Context HttpServletResponse response,
            @Context HttpServletRequest servletRequest) throws IOException
    {
        //if the user is admin, don't filter results by user.
        Optional<String> filterUser = AccessControlUtil.getUserForFilter(accessControl, serverConfig, servletRequest, groupProvider);

        String sessionUser = AccessControlUtil.getUser(accessControl, new HttpRequestSessionContext(servletRequest, groupProvider));

        if (filterUser.isPresent()) {
            response.setStatus(Response.SC_FORBIDDEN);
            return;
        }
        String inputUsername = emptyToNull(username);
        String inputBeginTime = emptyToNull(beginTime);
        String inputEndTime = emptyToNull(endTime);
        String inputLevel = emptyToNull(level);

        List<String> logFiles = getLogFiles(type, inputBeginTime, inputEndTime, inputUsername, inputLevel);
        if (logFiles.isEmpty()) {
            response.setStatus(Response.SC_NOT_FOUND);
            return;
        }
        response.reset();
        response.setContentType("multipart/form-data");
        String downloadName = getCurrentDate() + "_" + sessionUser + "_auditLog.zip";
        response.setHeader("Content-Disposition", "attachment;fileName=" + downloadName);

        //set compression stream: write response directly to achieve compression while downloading
        ZipOutputStream zipos = new ZipOutputStream(new BufferedOutputStream(response.getOutputStream()));
        zipos.setMethod(ZipOutputStream.DEFLATED);
        DataOutputStream os = null;
        try {
            for (String filePath : logFiles) {
                File file = new File(filePath);
                zipos.putNextEntry(new ZipEntry(file.getName()));
                os = new DataOutputStream(zipos);
                InputStream is = new FileInputStream(file);
                byte[] b = new byte[1024];
                int length = 0;
                while ((length = is.read(b)) != -1) {
                    os.write(b, 0, length);
                }
                is.close();
                zipos.closeEntry();
            }
        }
        finally {
            os.flush();
            os.close();
            zipos.close();
        }
    }

    @GET
    @Path("/pattern")
    public String getPattern()
    {
        String pattern = eventListenerManager.getLogconversionpattern();
        if (pattern == null || !pattern.contains(".")) {
            pattern = "YYYY-MM-DD";
        }
        else {
            pattern = "YYYY-MM-DD.HH";
        }
        return pattern;
    }

    public List<String> getLogFiles(String type, String beginTime, String endTime, String user, String level) throws IOException
    {
        ArrayList<String> res = new ArrayList<>();
        String logPath = eventListenerManager.getLogOutput() + "/" + type;
        File f = new File(logPath);
        File[] file = f.listFiles();
        if (file == null) {
            return res;
        }
        Arrays.sort(file, (o1, o2) -> Long.valueOf(o1.lastModified()).compareTo(o2.lastModified()));
        for (File tmpFile : file) {
            if (filterTimeAndUser(beginTime, endTime, user, level, tmpFile.getName())) {
                res.add(tmpFile.getCanonicalPath());
            }
        }
        return res;
    }

    private static Boolean filterTimeAndUser(String beginTime, String endTime, String user, String level, String filename)
    {
        if (!filename.contains(".log") || filename.contains(".lck") || filename.contains(".log.")) {
            return false;
        }

        String[]values = filename.split("#");
        String tmpLevel = values[0];
        String tmpUser = values[1];
        String tmpTime = values[2].substring(0, values[2].length() - 4); // cut ".log" from filename

        if (beginTime != null && beginTime.contains(".") && tmpTime.length() != beginTime.length()) {
            tmpTime = tmpTime + ".00";
        }
        if (beginTime != null && tmpTime.compareTo(beginTime) < 0) {
            return false;
        }
        if (endTime != null && tmpTime.compareTo(endTime) > 0) {
            return false;
        }
        if (user != null && !tmpUser.equals(user)) {
            return false;
        }
        if (level != null && !tmpLevel.equals(level)) {
            return false;
        }
        return true;
    }

    private static String getCurrentDate()
    {
        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter format = DateTimeFormatter.ofPattern("yyyy-MM-dd.HH");
        return now.format(format);
    }
}
