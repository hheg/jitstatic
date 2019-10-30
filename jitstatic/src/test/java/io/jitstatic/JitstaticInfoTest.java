package io.jitstatic;

/*-
 * #%L
 * jitstatic
 * %%
 * Copyright (C) 2017 H.Hegardt
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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.SortedMap;
import java.util.stream.Collectors;

import javax.ws.rs.client.Client;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.codahale.metrics.health.HealthCheck.Result;

import io.dropwizard.testing.ConfigOverride;
import io.dropwizard.testing.junit5.DropwizardAppExtension;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.jitstatic.test.BaseTest;
import io.jitstatic.test.TemporaryFolder;
import io.jitstatic.test.TemporaryFolderExtension;
import io.jitstatic.tools.ContainerUtils;

@ExtendWith({ TemporaryFolderExtension.class, DropwizardExtensionsSupport.class })
public class JitstaticInfoTest extends BaseTest {

    private final DropwizardAppExtension<JitstaticConfiguration> DW = new DropwizardAppExtension<>(JitstaticApplication.class,
            ContainerUtils.getDropwizardConfigurationResource(), ConfigOverride.config("hosted.basePath", getFolder()));
    private TemporaryFolder tmpFolder;
    private String adress;

    @BeforeEach
    public void setupClass() {
        adress = String.format("http://localhost:%d/application/info", DW.getLocalPort());
    }

    @AfterEach
    public void after() {
        SortedMap<String, Result> healthChecks = DW.getEnvironment().healthChecks().runHealthChecks();
        List<Throwable> errors = healthChecks.entrySet().stream().map(e -> e.getValue().getError()).filter(Objects::nonNull).collect(Collectors.toList());
        errors.stream().forEach(e -> e.printStackTrace());
        assertThat(errors.toString(), errors.isEmpty(), Matchers.is(true));
    }

    @Test
    public void testGetCommitId() {
        Client client = buildClient("test client");
        String response = client.target(String.format("%s/commitid", adress)).request().get(String.class);
        assertNotNull(response);
    }

    @Test
    public void testGetVersion() {
        Client client = buildClient("test client2");
        String response = client.target(String.format("%s/version", adress)).request().get(String.class);
        assertNotNull(response);
    }

    private Client buildClient(final String name) {
        return DW.client();
    }

    @Override
    protected File getFolderFile() throws IOException { 
        return tmpFolder.createTemporaryDirectory();
    }

}
