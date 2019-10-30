package io.jitstatic;

import static org.eclipse.jetty.http.HttpStatus.OK_200;
import static org.eclipse.jetty.http.HttpStatus.UNAUTHORIZED_401;

/*-
 * #%L
 * jitstatic
 * %%
 * Copyright (C) 2017 - 2018 H.Hegardt
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.io.IOException;

import org.eclipse.jetty.http.HttpStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.JsonNode;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;

import io.dropwizard.testing.ConfigOverride;
import io.dropwizard.testing.junit5.DropwizardAppExtension;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.jitstatic.hosted.HostedFactory;
import io.jitstatic.test.BaseTest;
import io.jitstatic.test.TemporaryFolder;
import io.jitstatic.test.TemporaryFolderExtension;
import io.jitstatic.tools.ContainerUtils;

@ExtendWith({ TemporaryFolderExtension.class, DropwizardExtensionsSupport.class })
public class AdminUrlTest extends BaseTest {

    private DropwizardAppExtension<JitstaticConfiguration> DW = new DropwizardAppExtension<>(JitstaticApplication.class,
            ContainerUtils.getDropwizardConfigurationResource(), ConfigOverride.config("hosted.basePath", getFolder()));
    private TemporaryFolder tmpfolder;

    @Test
    public void testAccessAdminMetrics() throws UnirestException {
        HttpResponse<JsonNode> response = Unirest.get(String.format("http://localhost:%s/admin/metrics", DW.getLocalPort()))
                .header("accept", "application/json").asJson();
        assertEquals(UNAUTHORIZED_401, response.getStatus());

        response = Unirest.get(String.format("http://localhost:%s/admin/metrics", DW.getLocalPort())).queryString("pretty", "true")
                .header("accept", "application/json").asJson();
        assertEquals(UNAUTHORIZED_401, response.getStatus());

        response = Unirest.post(String.format("http://localhost:%s/admin/metrics", DW.getLocalPort())).header("accept", "application/json").asJson();
        assertEquals(UNAUTHORIZED_401, response.getStatus());

        HostedFactory hostedFactory = DW.getConfiguration().getHostedFactory();
        response = Unirest.get(String.format("http://localhost:%s/admin/metrics", DW.getLocalPort()))
                .basicAuth(hostedFactory.getAdminName(), hostedFactory.getAdminPass()).header("accept", "application/json").asJson();
        assertEquals(OK_200, response.getStatus());

        response = Unirest.get(String.format("http://localhost:%s/admin/metrics", DW.getLocalPort())).queryString("pretty", "true")
                .basicAuth(hostedFactory.getAdminName(), hostedFactory.getAdminPass()).header("accept", "application/json").asJson();
        assertEquals(OK_200, response.getStatus());
    }

    @Test
    public void testAccessAdminHealthCheck() throws UnirestException {
        HttpResponse<JsonNode> response = Unirest.get(String.format("http://localhost:%s/admin/healthcheck", DW.getLocalPort()))
                .header("accept", "application/json").asJson();
        assertEquals(OK_200, response.getStatus());

        HostedFactory hostedFactory = DW.getConfiguration().getHostedFactory();
        response = Unirest.get(String.format("http://localhost:%s/admin/healthcheck", DW.getLocalPort()))
                .basicAuth(hostedFactory.getAdminName(), hostedFactory.getAdminPass()).header("accept", "application/json").asJson();
        assertEquals(OK_200, response.getStatus());
    }

    @Test
    public void testAccessTasksGc() throws Exception {
        HttpResponse<String> response = Unirest.post(String.format("http://localhost:%s/admin/tasks/gc", DW.getLocalPort())).asString();
        assertEquals(UNAUTHORIZED_401, response.getStatus());

        HostedFactory hostedFactory = DW.getConfiguration().getHostedFactory();
        response = Unirest.post(String.format("http://localhost:%s/admin/tasks/gc", DW.getLocalPort()))
                .basicAuth(hostedFactory.getAdminName(), hostedFactory.getAdminPass()).asString();
        assertEquals(OK_200, response.getStatus());
    }

    @Test
    public void testAccessTasks() throws Exception {
        HttpResponse<String> response = Unirest.post(String.format("http://localhost:%s/admin/tasks", DW.getLocalPort())).asString();
        assertEquals(UNAUTHORIZED_401, response.getStatus());

        HostedFactory hostedFactory = DW.getConfiguration().getHostedFactory();
        response = Unirest.post(String.format("http://localhost:%s/admin/tasks", DW.getLocalPort()))
                .basicAuth(hostedFactory.getAdminName(), hostedFactory.getAdminPass()).asString();
        assertEquals(HttpStatus.NOT_FOUND_404, response.getStatus());
    }
    
    protected File getFolderFile() throws IOException {
        return tmpfolder.createTemporaryDirectory();
    }

}
