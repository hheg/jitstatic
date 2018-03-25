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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Scanner;
import java.util.SortedMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

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
import org.junit.rules.ExpectedException;
import org.junit.rules.RuleChain;
import org.junit.rules.TemporaryFolder;

import com.codahale.metrics.health.HealthCheck.Result;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.dropwizard.testing.ConfigOverride;
import io.dropwizard.testing.ResourceHelpers;
import io.dropwizard.testing.junit.DropwizardAppRule;
import io.jitstatic.JitstaticApplication;
import io.jitstatic.JitstaticConfiguration;
import io.jitstatic.client.APIException;
import io.jitstatic.client.CommitData;
import io.jitstatic.client.JitStaticCreatorClient;
import io.jitstatic.client.JitStaticCreatorClientBuilder;
import io.jitstatic.client.JitStaticUpdaterClient;
import io.jitstatic.client.JitStaticUpdaterClientBuilder;
import io.jitstatic.client.MetaData;
import io.jitstatic.client.TriFunction;
import io.jitstatic.hosted.HostedFactory;

public class KeyValueStorageWithHostedStorageAcceptanceTest {

    private static final String UTF_8 = "UTF-8";
    private static final String ACCEPT_STORAGE = "accept/storage";
    private static final String USER = "suser";
    private static final String PASSWORD = "ssecret";
    private static final TemporaryFolder TMP_FOLDER = new TemporaryFolder();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private DropwizardAppRule<JitstaticConfiguration> DW;
    private String adress;
    @Rule
    public final RuleChain chain = RuleChain.outerRule(TMP_FOLDER).around((DW = new DropwizardAppRule<>(JitstaticApplication.class,
            ResourceHelpers.resourceFilePath("simpleserver.yaml"), ConfigOverride.config("hosted.basePath", getFolder()))));

    @Rule
    public ExpectedException ex = ExpectedException.none();

    @Before
    public void setup() throws InvalidRemoteException, TransportException, GitAPIException, IOException {
        adress = String.format("http://localhost:%d/application", DW.getLocalPort());
        HostedFactory hostedFactory = DW.getConfiguration().getHostedFactory();
        String user = hostedFactory.getUserName();
        String pass = hostedFactory.getSecret();
        String servletName = hostedFactory.getServletName();
        String endpoint = hostedFactory.getHostedEndpoint();

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
    public void testGetNotFoundKeyWithoutAuth() throws Exception {
        ex.expect(APIException.class);
        ex.expectMessage("/application/storage/nokey failed with: 404 Not Found");
        try (JitStaticUpdaterClient client = buildClient().build();) {
            client.getKey("nokey", null, tf);
        }
    }

    @Test
    public void testGetNotFoundKeyWithAuth() throws Exception {
        ex.expect(APIException.class);
        ex.expectMessage("application/storage/nokey failed with: 404 Not Found");
        try (JitStaticUpdaterClient client = buildClient().setUser(USER).setPassword(PASSWORD).build();) {
            client.getKey("nokey", null, tf);
        }
    }

    @Test
    public void testGetAKeyValue() throws Exception {
        try (JitStaticUpdaterClient client = buildClient().setUser(USER).setPassword(PASSWORD).build();) {
            Entity<JsonNode> key = client.getKey(ACCEPT_STORAGE, null, tf);
            assertEquals(getData(), key.data.toString());
            assertNotNull(key.getTag());
        }
    }

    @Test
    public void testGetAKeyValueWithoutAuth() throws Exception {
        ex.expect(APIException.class);
        ex.expectMessage("/application/storage/accept/storage failed with: 401 Unauthorized");
        try (JitStaticUpdaterClient client = buildClient().build();) {
            client.getKey(ACCEPT_STORAGE, null, tf);
        }
    }

    @Test
    public void testModifyAKey() throws Exception {
        try (JitStaticUpdaterClient client = buildClient().setUser(USER).setPassword(PASSWORD).build();) {
            Entity<JsonNode> key = client.getKey(ACCEPT_STORAGE, null, tf);
            assertEquals(getData(), key.data.toString());
            String oldVersion = key.getTag();
            byte[] newData = "{\"one\":\"two\"}".getBytes(UTF_8);
            String modifyKey = client.modifyKey(newData, new CommitData("master", ACCEPT_STORAGE, "commit message", "user1", "user@mail"),
                    key.getTag());
            assertNotEquals(oldVersion, modifyKey);
            key = client.getKey(ACCEPT_STORAGE, null, tf);
            assertEquals(new String(newData), key.data.toString());
        }
    }

    @Test
    public void testPrettifiedKey() throws Exception {
        try (JitStaticUpdaterClient client = buildClient().setUser(USER).setPassword(PASSWORD).build();) {
            Entity<String> key = client.getKey(ACCEPT_STORAGE, null, stringFactory);
            assertEquals(getData(), key.data.toString());
            String oldVersion = key.getTag();
            byte[] prettyData = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(MAPPER.readTree(getData()));
            String modifyKey = client.modifyKey(prettyData,
                    new CommitData("master", ACCEPT_STORAGE, "commit message", "user1", "user@mail"), key.getTag());
            assertNotEquals(oldVersion, modifyKey);
            key = client.getKey(ACCEPT_STORAGE, null, stringFactory);
            assertEquals(new String(prettyData), key.data);
        }
    }

    @Test
    public void testAddKey() throws Exception {
        HostedFactory hostedFactory = DW.getConfiguration().getHostedFactory();
        String user = hostedFactory.getUserName();
        String pass = hostedFactory.getSecret();
        JitStaticCreatorClientBuilder builder = JitStaticCreatorClient.create().setHost("localhost").setPort(DW.getLocalPort())
                .setAppContext("/application/").setUser(user).setPassword(pass);

        try (JitStaticCreatorClient client = builder.build(); JitStaticUpdaterClient getter = buildClient().build()) {
            Entity<String> createKey = client.createKey(getData().getBytes(UTF_8),
                    new CommitData("master", "newkey", "commit message", "user1", "user@mail"),
                    new MetaData(new HashSet<>(), "application/json"), stringFactory);
            assertNotNull(createKey.getTag());
            assertArrayEquals(getData().getBytes(UTF_8), createKey.data.getBytes(UTF_8));
            Entity<String> key = getter.getKey("newkey", stringFactory);
            assertArrayEquals(getData().getBytes(UTF_8), key.data.getBytes(UTF_8));
            assertEquals(createKey.getTag(), key.getTag());
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

    private JitStaticUpdaterClientBuilder buildClient() {
        return JitStaticUpdaterClient.create().setHost("localhost").setPort(DW.getLocalPort())
                .setAppContext("/application/storage/");
    }

    private void writeFile(Path workBase, String file) throws IOException {
        final Path filePath = workBase.resolve(file);
        Files.createDirectories(Objects.requireNonNull(filePath.getParent()));
        try (InputStream is = getClass().getResourceAsStream("/" + file)) {
            Files.copy(is, filePath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String getData() {
        return getData(0);
    }

    private static String getData(int c) {
        return "{\"key" + c
                + "\":{\"data\":\"value1\",\"users\":[{\"captain\":\"america\",\"black\":\"widow\"}]},\"mkey3\":{\"data\":\"value3\",\"users\":[{\"tony\":\"stark\",\"spider\":\"man\"}]}}";
    }

    private TriFunction<InputStream, String, String, Entity<String>> stringFactory = (is, v, t) -> {
        try (Scanner s = new Scanner(is).useDelimiter("\\A")) {
            String result = s.hasNext() ? s.next() : "";
            return new Entity<String>(v, t, result);
        }
    };

    static class Entity<T> {

        final T data;
        private final String tag;
        private final String contentType;

        public Entity(String tag, String contentType, T data) {
            this.tag = tag;
            this.contentType = contentType;
            this.data = data;
        }

        public String getTag() {
            return tag;
        }

        public String getContentType() {
            return contentType;
        }
    }

    private TriFunction<InputStream, String, String, Entity<JsonNode>> tf = (is, v, t) -> {
        try {
            return new Entity<>(v, t, MAPPER.readValue(is, JsonNode.class));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    };
}
