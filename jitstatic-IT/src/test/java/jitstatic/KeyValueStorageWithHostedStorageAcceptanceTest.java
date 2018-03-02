package jitstatic;

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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.SortedMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.AbortedByHookException;
import org.eclipse.jgit.api.errors.ConcurrentRefUpdateException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.NoFilepatternException;
import org.eclipse.jgit.api.errors.NoHeadException;
import org.eclipse.jgit.api.errors.NoMessageException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.api.errors.UnmergedPathsException;
import org.eclipse.jgit.api.errors.WrongRepositoryStateException;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.TemporaryFolder;

import com.codahale.metrics.health.HealthCheck.Result;

import io.dropwizard.client.HttpClientBuilder;
import io.dropwizard.client.HttpClientConfiguration;
import io.dropwizard.client.JerseyClientBuilder;
import io.dropwizard.setup.Environment;
import io.dropwizard.testing.ConfigOverride;
import io.dropwizard.testing.ResourceHelpers;
import io.dropwizard.testing.junit.DropwizardAppRule;
import io.dropwizard.util.Duration;
import jitstatic.api.ModifyKeyData;
import jitstatic.hosted.HostedFactory;

public class KeyValueStorageWithHostedStorageAcceptanceTest {

    private static final String ACCEPT_STORAGE = "accept/storage";
    private static final String USER = "suser";
    private static final String PASSWORD = "ssecret";
    private static final TemporaryFolder TMP_FOLDER = new TemporaryFolder();
    private final HttpClientConfiguration HCC = new HttpClientConfiguration();
    private DropwizardAppRule<JitstaticConfiguration> DW;
    private String adress;
    private String basic;

    @Rule
    public final RuleChain chain = RuleChain.outerRule(TMP_FOLDER).around((DW = new DropwizardAppRule<>(JitstaticApplication.class,
            ResourceHelpers.resourceFilePath("simpleserver.yaml"), ConfigOverride.config("hosted.basePath", getFolder()))));

    @Before
    public void setup() throws InvalidRemoteException, TransportException, GitAPIException, IOException {
        adress = String.format("http://localhost:%d/application", DW.getLocalPort());
        HostedFactory hostedFactory = DW.getConfiguration().getHostedFactory();
        String user = hostedFactory.getUserName();
        String pass = hostedFactory.getSecret();
        String servletName = hostedFactory.getServletName();
        String endpoint = hostedFactory.getHostedEndpoint();
        basic = basicAuth();
        HCC.setConnectionRequestTimeout(Duration.minutes(1));
        HCC.setConnectionTimeout(Duration.minutes(1));
        HCC.setTimeout(Duration.minutes(1));

        setupRepo(user, pass, servletName, endpoint);
    }

    private void setupRepo(String user, String pass, String servletName, String endpoint) throws IOException, GitAPIException,
            NoFilepatternException, NoHeadException, NoMessageException, UnmergedPathsException, ConcurrentRefUpdateException,
            WrongRepositoryStateException, AbortedByHookException, InvalidRemoteException, TransportException {
        File workingDirectory = TMP_FOLDER.newFolder();
        UsernamePasswordCredentialsProvider provider = new UsernamePasswordCredentialsProvider(user, pass);
        try (Git git = Git.cloneRepository().setDirectory(workingDirectory).setURI(adress + "/" + servletName + "/" + endpoint)
                .setCredentialsProvider(provider).call()) {

            writeFile(workingDirectory.toPath(), ACCEPT_STORAGE);
            writeFile(workingDirectory.toPath(), ACCEPT_STORAGE + ".metadata");

            git.add().addFilepattern(".").call();
            git.commit().setMessage("Initial commit").call();
            git.push().setCredentialsProvider(provider).call();
        }
    }

    @After
    public void after() {
        SortedMap<String, Result> healthChecks = DW.getEnvironment().healthChecks().runHealthChecks();
        List<Throwable> errors = healthChecks.entrySet().stream().map(e -> e.getValue().getError()).filter(Objects::nonNull)
                .collect(Collectors.toList());
        errors.stream().forEach(e -> e.printStackTrace());
        assertThat(errors.toString(), errors.isEmpty(), Matchers.is(true));
    }

    @Test
    public void testGetNotFoundKeyWithoutAuth() {
        Client client = buildClient("test client");
        try {
            Response response = client.target(String.format("%s/storage/nokey", adress)).request().get();
            assertEquals(Status.NOT_FOUND.getStatusCode(), response.getStatus());
        } finally {
            client.close();
        }
    }

    @Test
    public void testGetNotFoundKeyWithAuth() {
        Client client = buildClient("test3 client");
        try {
            Response response = client.target(String.format("%s/storage/nokey", adress)).request().header(HttpHeaders.AUTHORIZATION, basic)
                    .get();
            assertEquals(Status.NOT_FOUND.getStatusCode(), response.getStatus());
        } finally {
            client.close();
        }
    }

    @Test
    public void testGetAKeyValue() {
        Client client = buildClient("test4 client");
        try {
            Response response = client.target(String.format("%s/storage/" + ACCEPT_STORAGE, adress)).request()
                    .header(HttpHeaders.AUTHORIZATION, basic).get();
            assertEquals(getData(), response.readEntity(String.class));
            assertNotNull(response.getEntityTag().getValue());
        } finally {
            client.close();
        }
    }

    @Test
    public void testGetAKeyValueWithoutAuth() {
        Client client = buildClient("test2 client");
        try {
            Response response = client.target(String.format("%s/storage/" + ACCEPT_STORAGE, adress)).request().get();
            assertEquals(Status.UNAUTHORIZED.getStatusCode(), response.getStatus());
        } finally {
            client.close();
        }
    }

    @Test
    public void testModifyAKey() throws IOException {
        Client client = buildClient("testmodify client");
        try {
            WebTarget target = client.target(String.format("%s/storage/" + ACCEPT_STORAGE, adress));
            Response response = target.request().header(HttpHeaders.AUTHORIZATION, basic).get();
            assertEquals(getData(), response.readEntity(String.class));
            String oldVersion = response.getEntityTag().getValue();
            ModifyKeyData data = new ModifyKeyData();
            byte[] newData = "{\"one\":\"two\"}".getBytes("UTF-8");
            response.close();
            data.setData(newData);
            data.setMessage("commit message");
            data.setUserMail("user@mail");
            String invoke = target.request().header(HttpHeaders.AUTHORIZATION, basic).header(HttpHeaders.IF_MATCH, "\"" + oldVersion + "\"")
                    .buildPut(Entity.json(data)).invoke(String.class);
            assertNotEquals(oldVersion, invoke);
            response = target.request().header(HttpHeaders.AUTHORIZATION, basic).get();
            assertEquals(new String(newData), response.readEntity(String.class));
            response.close();
        } finally {
            client.close();
        }
    }

    private static Supplier<String> getFolder() {
        return () -> {
            try {
                return TMP_FOLDER.newFolder().toString();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        };
    }

    private Client buildClient(final String name) {
        Environment env = DW.getEnvironment();
        JerseyClientBuilder jerseyClientBuilder = new JerseyClientBuilder(env);
        jerseyClientBuilder.setApacheHttpClientBuilder(new HttpClientBuilder(env).using(HCC));
        return jerseyClientBuilder.build(name);
    }

    private void writeFile(Path workBase, String file) throws IOException {
        final Path filePath = workBase.resolve(file);
        Files.createDirectories(Objects.requireNonNull(filePath.getParent()));
        try (InputStream is = getClass().getResourceAsStream("/" + file)) {
            Files.copy(is, filePath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String basicAuth() throws UnsupportedEncodingException {
        return "Basic " + Base64.getEncoder().encodeToString((USER + ":" + PASSWORD).getBytes("UTF-8"));
    }

    private static String getData() {
        return "{\"key\":{\"data\":\"value1\",\"users\":[{\"captain\":\"america\",\"black\":\"widow\"}]},\"key3\":{\"data\":\"value3\",\"users\":[{\"tony\":\"stark\",\"spider\":\"man\"}]}}";
    }
}
