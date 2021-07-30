package io.jitstatic;

import static io.jitstatic.JitStaticConstants.GIT_SECRETS;

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

import static io.jitstatic.JitStaticConstants.JITSTATIC_GIT_REALM;
import static io.jitstatic.JitStaticConstants.USERS;
import static io.jitstatic.source.ObjectStreamProvider.toByte;
import static org.eclipse.jetty.http.HttpStatus.BAD_REQUEST_400;
import static org.eclipse.jetty.http.HttpStatus.FORBIDDEN_403;
import static org.eclipse.jetty.http.HttpStatus.METHOD_NOT_ALLOWED_405;
import static org.eclipse.jetty.http.HttpStatus.NOT_FOUND_404;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Scanner;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.http.client.ClientProtocolException;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.hamcrest.CoreMatchers;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.codahale.metrics.health.HealthCheck.Result;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;

import io.dropwizard.testing.ConfigOverride;
import io.dropwizard.testing.junit5.DropwizardAppExtension;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.jitstatic.api.KeyDataWrapper;
import io.jitstatic.client.APIException;
import io.jitstatic.client.CommitData;
import io.jitstatic.client.JitStaticClient;
import io.jitstatic.client.MetaData;
import io.jitstatic.client.MetaData.User;
import io.jitstatic.client.ModifyUserKeyData;
import io.jitstatic.client.TriFunction;
import io.jitstatic.injection.configuration.JitstaticConfiguration;
import io.jitstatic.injection.configuration.hosted.HostedFactory;
import io.jitstatic.test.BaseTest;
import io.jitstatic.test.TemporaryFolder;
import io.jitstatic.test.TemporaryFolderExtension;
import io.jitstatic.tools.ContainerUtils;

@ExtendWith({ TemporaryFolderExtension.class, DropwizardExtensionsSupport.class })
public class KeyValueStorageWithHostedStorageTest extends BaseTest {

    private static final String METADATA = ".metadata";
    private static final String REFS_HEADS_NEWBRANCH = "refs/heads/newbranch";
    private static final String REFS_HEADS_MASTER = "refs/heads/master";
    private static final String APPLICATION_JSON = "application/json";
    private static final Charset UTF_8 = StandardCharsets.UTF_8;
    private static final String ACCEPT_STORAGE = "accept/storage";
    private static final String USER = "suser";
    private static final String PASSWORD = "ssecret";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private DropwizardAppExtension<JitstaticConfiguration> DW = new DropwizardAppExtension<>(JitstaticApplication.class, ContainerUtils
            .getDropwizardConfigurationResource(), ConfigOverride.config("hosted.basePath", getFolder()));
    private TemporaryFolder tmpfolder;
    private String adress;

    @BeforeEach
    public void setup() throws Exception {
        adress = String.format("http://localhost:%d/application", DW.getLocalPort());
        HostedFactory hostedFactory = DW.getConfiguration().getHostedFactory();
        String user = hostedFactory.getUserName();
        String pass = hostedFactory.getSecret();
        String servletName = hostedFactory.getServletName();
        String endpoint = hostedFactory.getHostedEndpoint();

        setupRepo(user, pass, servletName, endpoint);
    }

    private void setupRepo(String user, String pass, String servletName, String endpoint) throws Exception {
        File workingDirectory = getFolderFile();
        UsernamePasswordCredentialsProvider provider = new UsernamePasswordCredentialsProvider(user, pass);
        try (Git git = Git.cloneRepository().setDirectory(workingDirectory).setURI(adress + "/" + servletName + "/" + endpoint).setCredentialsProvider(provider)
                .call()) {
            setupUser(git, JitStaticConstants.JITSTATIC_KEYUSER_REALM, USER, PASSWORD, Set.of("read", "write"));

            writeFile(workingDirectory.toPath(), ACCEPT_STORAGE);
            writeFile(workingDirectory.toPath(), ACCEPT_STORAGE + METADATA);

            final Path filePath = workingDirectory.toPath().resolve("accept/.metadata");
            Files.createDirectories(Objects.requireNonNull(filePath.getParent()));
            try (InputStream is = getClass().getResourceAsStream("/accept/metadata")) {
                Files.copy(is, filePath, StandardCopyOption.REPLACE_EXISTING);
            }

            writeFile(workingDirectory.toPath(), "accept/genkey");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("Initial commit").call();
            verifyOkPush(git.push().setCredentialsProvider(provider).call());
        }
    }

    @AfterEach
    public void after() {
        SortedMap<String, Result> healthChecks = DW.getEnvironment().healthChecks().runHealthChecks();
        List<Throwable> errors = healthChecks.entrySet().stream().map(e -> e.getValue().getError()).filter(Objects::nonNull).collect(Collectors.toList());
        errors.stream().forEach(e -> e.printStackTrace());
        assertThat(errors.toString(), errors.isEmpty(), Matchers.is(true));
    }

    @Test
    public void testGetNotFoundKeyWithoutAuth() throws Exception {
        assertThat(assertThrows(APIException.class, () -> {
            try (JitStaticClient client = buildClient(DW.getLocalPort()).build();) {
                client.getKey("nokey", null, parse(JsonNode.class));
            }
        }).getMessage(), CoreMatchers.containsString("/application/storage/nokey failed with: 404 Not Found"));
    }

    @Test
    public void testGetNotFoundKeyWithAuth() throws Exception {
        assertThat(assertThrows(APIException.class, () -> {
            try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(USER).setPassword(PASSWORD).build();) {
                client.getKey("nokey", null, parse(JsonNode.class));
            }
        }).getMessage(), CoreMatchers.containsString("application/storage/nokey failed with: 404 Not Found"));
    }

    @Test
    public void testGetAKeyValue() throws Exception {
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(USER).setPassword(PASSWORD).build();) {
            Entity<JsonNode> key = client.getKey(ACCEPT_STORAGE, null, parse(JsonNode.class));
            assertEquals(getData(), key.getData().toString());
            assertNotNull(key.getTag());
        }
    }

    @Test
    public void testGetAKeyValueWithEtag() throws Exception {
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(USER).setPassword(PASSWORD).build();) {
            Entity<JsonNode> key = client.getKey(ACCEPT_STORAGE, null, parse(JsonNode.class));
            assertEquals(getData(), key.getData().toString());
            assertNotNull(key.getTag());
            Entity<JsonNode> key2 = client.getKey(ACCEPT_STORAGE, parse(JsonNode.class), key.getTag());
            assertEquals(key.getTag(), key2.getTag());
        }
    }

    @Test
    public void testGetAKeyValueWithoutAuth() throws Exception {
        assertThat(assertThrows(APIException.class, () -> {
            try (JitStaticClient client = buildClient(DW.getLocalPort()).build();) {
                client.getKey(ACCEPT_STORAGE, null, parse(JsonNode.class));
            }
        }).getMessage(), CoreMatchers.containsString("/application/storage/accept/storage failed with: 403 Forbidden"));
    }

    @Test
    public void testModifyAKey() throws Exception {
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(USER).setPassword(PASSWORD).build();) {
            Entity<JsonNode> key = client.getKey(ACCEPT_STORAGE, null, parse(JsonNode.class));
            assertEquals(getData(), key.getData().toString());
            String oldVersion = key.getTag();
            byte[] newData = "{\"one\":\"two\"}".getBytes(UTF_8);
            String modifyKey = client.modifyKey(newData, new CommitData(ACCEPT_STORAGE, "master", "commit message", "user1", "user@mail"), key.getTag());
            assertNotEquals(oldVersion, modifyKey);
            key = client.getKey(ACCEPT_STORAGE, null, parse(JsonNode.class));
            assertEquals(new String(newData, "UTF-8"), key.getData().toString());
        }
    }

    @Test
    public void testPrettifiedKey() throws Exception {
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(USER).setPassword(PASSWORD).build();) {
            Entity<String> key = client.getKey(ACCEPT_STORAGE, null, stringFactory);
            assertEquals(getData(), key.getData().toString());
            String oldVersion = key.getTag();
            byte[] prettyData = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(MAPPER.readTree(getData()));
            String modifyKey = client.modifyKey(prettyData, new CommitData(ACCEPT_STORAGE, "master", "commit message", "user1", "user@mail"), key.getTag());
            assertNotEquals(oldVersion, modifyKey);
            key = client.getKey(ACCEPT_STORAGE, null, stringFactory);
            assertEquals(new String(prettyData, "UTF-8"), key.getData());
        }
    }

    @Test
    public void testAddKey() throws Exception {
        HostedFactory hostedFactory = DW.getConfiguration().getHostedFactory();
        String user = hostedFactory.getUserName();
        String pass = hostedFactory.getSecret();

        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(user).setPassword(pass).build();
                JitStaticClient getter = buildClient(DW.getLocalPort()).build()) {
            String createKey = client.createKey(getData()
                    .getBytes(UTF_8), new CommitData("base/newkey", "master", "commit message", "user1", "user@mail"), new MetaData(APPLICATION_JSON));
            Entity<String> key = getter.getKey("base/newkey", stringFactory);
            assertArrayEquals(getData().getBytes(UTF_8), key.getData().getBytes(UTF_8));
            assertEquals(createKey, key.getTag());
        }
    }

    @Test
    public void testAddingMalformedKey() throws URISyntaxException {
        HostedFactory hostedFactory = DW.getConfiguration().getHostedFactory();
        String user = hostedFactory.getUserName();
        String pass = hostedFactory.getSecret();

        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(user).setPassword(pass).build();) {
            APIException apiException = assertThrows(APIException.class, () -> client
                    .createKey(getData().getBytes(UTF_8), new CommitData("base/newkey", "master", "commit message", "user1", "user@mail"), new MetaData(Set
                            .of(new User("", "1234")), APPLICATION_JSON)));
            assertEquals(HttpStatus.UNPROCESSABLE_ENTITY_422, apiException.getStatusCode());
            assertThat(apiException.getMessage(), CoreMatchers.containsString("metaData.users Users in metadata will be ignored"));
            assertThat(apiException.getMessage(), CoreMatchers.containsString("metaData.users[].user must not be blank"));
        }
    }

    @Test
    public void testAddKeyWhenChecked() throws Exception {
        HostedFactory hostedFactory = DW.getConfiguration().getHostedFactory();
        String user = hostedFactory.getUserName();
        String pass = hostedFactory.getSecret();

        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(user).setPassword(pass).build();
                JitStaticClient getter = buildClient(DW.getLocalPort()).build()) {
            assertEquals(NOT_FOUND_404, assertThrows(APIException.class, () -> getter.getKey("base/mid/newkey", stringFactory)).getStatusCode());
            String createKey = client.createKey(getData()
                    .getBytes(UTF_8), new CommitData("base/mid/new%20key", "master", "commit message", "user1", "user@mail"), new MetaData(APPLICATION_JSON));
            Entity<String> key = getter.getKey("base/mid/new%20key", stringFactory);
            assertEquals(NOT_FOUND_404, assertThrows(APIException.class, () -> getter.getKey("base/mid/new", stringFactory)).getStatusCode());
            assertArrayEquals(getData().getBytes(UTF_8), key.getData().getBytes(UTF_8));
            assertEquals(createKey, key.getTag());
        }
    }

    @Test
    public void testModifyUserKey() throws Exception {
        HostedFactory hostedFactory = DW.getConfiguration().getHostedFactory();
        String root = hostedFactory.getUserName();
        String pass = hostedFactory.getSecret();
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(root).setPassword(pass).build();
                JitStaticClient firstUpdater = buildClient(DW.getLocalPort()).setUser(USER).setPassword(PASSWORD).build();
                JitStaticClient thirdUpdater = buildClient(DW.getLocalPort()).setUser("random").setPassword("randompass").build()) {
            Entity<JsonNode> key = firstUpdater.getKey(ACCEPT_STORAGE, parse(JsonNode.class));
            assertNotNull(key);
            key = client.getKey(ACCEPT_STORAGE, parse(JsonNode.class));
            assertNotNull(key);
            assertEquals(FORBIDDEN_403, assertThrows(APIException.class, () -> thirdUpdater.getKey(ACCEPT_STORAGE, parse(JsonNode.class))).getStatusCode());
            Entity<io.jitstatic.MetaData> metaKey = client.getMetaKey(ACCEPT_STORAGE, null, parse(io.jitstatic.MetaData.class));
            String oldVersion = metaKey.getTag();
            io.jitstatic.MetaData md = metaKey.getData();
            String modifyKey = client.modifyMetaKey(ACCEPT_STORAGE, null, metaKey
                    .getTag(), new ModifyUserKeyData(new MetaData("plain/text", Set
                            .of(new MetaData.Role("other")), toRole(md.getWrite())), "msg", "mail", "info"));
            assertNotEquals(oldVersion, modifyKey);
            metaKey = client.getMetaKey(ACCEPT_STORAGE, null, parse(io.jitstatic.MetaData.class));
            assertEquals("plain/text", metaKey.getData().getContentType());
            key = client.getKey(ACCEPT_STORAGE, parse(JsonNode.class));
            assertNotNull(key);
            assertEquals(FORBIDDEN_403, assertThrows(APIException.class, () -> firstUpdater.getKey(ACCEPT_STORAGE, parse(JsonNode.class))).getStatusCode());
        }
    }

    @Test
    public void testModifyMasterMetaData() throws URISyntaxException, ClientProtocolException, IOException {
        HostedFactory hostedFactory = DW.getConfiguration().getHostedFactory();
        String user = hostedFactory.getUserName();
        String pass = hostedFactory.getSecret();

        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(user).setPassword(pass).build();
                JitStaticClient firstUpdater = buildClient(DW.getLocalPort()).setUser(USER).setPassword(PASSWORD).build();
                JitStaticClient secondUpdater = buildClient(DW.getLocalPort()).setUser(user).setPassword(pass).build()) {
            Entity<JsonNode> key = firstUpdater.getKey("accept/genkey", parse(JsonNode.class));
            assertNotNull(key);
            Entity<JsonNode> metaKey = client.getMetaKey("accept/", null, parse(JsonNode.class));

            String modifyMetaKey = client.modifyMetaKey("accept/", null, metaKey
                    .getTag(), new ModifyUserKeyData(new MetaData(APPLICATION_JSON, true, false, roleOf("read"), roleOf("write")), "msg", "mail", "info"));
            assertNotEquals(metaKey.getTag(), modifyMetaKey);
            Entity<JsonNode> key2 = secondUpdater.getKey("accept/genkey", parse(JsonNode.class));
            assertNotNull(key2);
        }
    }

    @Test
    public void testDeleteKey() throws Exception {
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(USER).setPassword(PASSWORD).build();) {
            Entity<JsonNode> key = client.getKey(ACCEPT_STORAGE, null, parse(JsonNode.class));
            assertEquals(getData(), key.getData().toString());
            assertNotNull(key.getTag());
            client.delete(new CommitData(ACCEPT_STORAGE, "message", "user", "mail"));
            TimeUnit.MILLISECONDS.sleep(100);
            assertEquals(NOT_FOUND_404, assertThrows(APIException.class, () -> client.getKey(ACCEPT_STORAGE, null, parse(JsonNode.class))).getStatusCode());
        }
    }

    @Test
    public void testAddBranchAndKey() throws URISyntaxException, ClientProtocolException, APIException, IOException {
        HostedFactory hostedFactory = DW.getConfiguration().getHostedFactory();
        String data = getData(3);
        assertEquals(BAD_REQUEST_400, assertThrows(APIException.class, () -> {
            try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(hostedFactory.getUserName()).setPassword(hostedFactory.getSecret()).build();) {
                client.createKey(data.getBytes(UTF_8), new CommitData("key", REFS_HEADS_NEWBRANCH, "new key", "user", "mail"), new MetaData(APPLICATION_JSON));
            }
        }).getStatusCode());
    }

    @Test
    public void addKeyAndDeleteAndThenAddAgain() throws Exception {
        HostedFactory hostedFactory = DW.getConfiguration().getHostedFactory();
        String branch = REFS_HEADS_MASTER;
        String data = getData(3);
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(hostedFactory.getUserName()).setPassword(hostedFactory.getSecret()).build();
                JitStaticClient updaterClient = buildClient(DW.getLocalPort()).setUser(USER).setPassword(PASSWORD).build();) {
            client.createKey(data
                    .getBytes(UTF_8), new CommitData("key", branch, "new key", "user", "mail"), new MetaData(APPLICATION_JSON, roleOf("read"), roleOf("write")));
            Entity<JsonNode> key = updaterClient.getKey("key", branch, parse(JsonNode.class));
            assertEquals(data, key.getData().toString());
            updaterClient.delete(new CommitData("key", branch, "delete key", "user", "mail"));
            assertEquals(HttpStatus.NOT_FOUND_404, assertThrows(APIException.class, () -> updaterClient.getKey("key", branch, parse(JsonNode.class)))
                    .getStatusCode());
            client.createKey(data.getBytes(UTF_8), new CommitData("key", branch, "new key", "user", "mail"), new MetaData(APPLICATION_JSON));
            key = updaterClient.getKey("key", branch, parse(JsonNode.class));
            assertEquals(data, key.getData().toString());
            assertEquals(HttpStatus.FORBIDDEN_403, assertThrows(APIException.class, () -> updaterClient
                    .delete(new CommitData("key", branch, "delete key", "user", "mail")))
                            .getStatusCode());
        }
    }

    @Test
    public void testListAllFiles() throws Exception {
        HostedFactory hostedFactory = DW.getConfiguration().getHostedFactory();
        String branch = REFS_HEADS_MASTER;
        String data = getData(3);
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(hostedFactory.getUserName()).setPassword(hostedFactory.getSecret()).build();
                JitStaticClient updaterClient = buildClient(DW.getLocalPort()).setUser(USER).setPassword(PASSWORD).build();) {
            List<String> list = List.of("key1", "key2", "dir/key1", "dir/key2");
            for (String key : list) {
                client.createKey(data
                        .getBytes(UTF_8), new CommitData(key, branch, "new key", "user", "mail"), new MetaData(APPLICATION_JSON, roleOf("read"), roleOf("write")));
            }
            client.createKey(data
                    .getBytes(UTF_8), new CommitData("key3", branch, "new key", "user", "mail"), new MetaData(APPLICATION_JSON, roleOf("read2"), roleOf("write2")));
            KeyDataWrapper result = updaterClient.listAll("/", readKeyData());
            assertNotNull(result);
            assertEquals(2, result.getResult().size(), result.toString());
            assertEquals("key1", result.getResult().get(0).getKey());
            assertEquals("key2", result.getResult().get(1).getKey());
        }
    }

    @Test
    public void testListAllFilesTree() throws Exception {
        HostedFactory hostedFactory = DW.getConfiguration().getHostedFactory();
        String branch = REFS_HEADS_MASTER;
        String data = getData(3);
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(hostedFactory.getUserName()).setPassword(hostedFactory.getSecret()).build();
                JitStaticClient updaterClient = buildClient(DW.getLocalPort()).setUser(USER).setPassword(PASSWORD).build();) {
            List<String> list = List.of("key1", "key2", "dir/key1", "dir/key2", "dir/dir/key1", "dir/k", "k", "di/k");
            for (String key : list) {
                client.createKey(data
                        .getBytes(UTF_8), new CommitData(key, branch, "new key", "user", "mail"), new MetaData(APPLICATION_JSON, roleOf("read"), roleOf("write")));
            }
            client.createKey(data
                    .getBytes(UTF_8), new CommitData("dir/key3", branch, "new key", "user", "mail"), new MetaData(APPLICATION_JSON, roleOf("read2"), roleOf("write2")));
            KeyDataWrapper result = updaterClient.listAll("dir/", readKeyData());
            assertNotNull(result);
            assertEquals(3, result.getResult().size());
            assertEquals("dir/k", result.getResult().get(0).getKey());
            assertEquals("dir/key1", result.getResult().get(1).getKey());
            assertEquals("dir/key2", result.getResult().get(2).getKey());
        }
    }

    @Test
    public void testListAllFilesTreeRecursive() throws Exception {
        HostedFactory hostedFactory = DW.getConfiguration().getHostedFactory();
        String branch = REFS_HEADS_MASTER;
        String data = getData(3);
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(hostedFactory.getUserName()).setPassword(hostedFactory.getSecret()).build();
                JitStaticClient updaterClient = buildClient(DW.getLocalPort()).setUser(USER).setPassword(PASSWORD).build();) {
            List<String> list = List.of("key1", "key2", "dir/key1", "dir/key2", "dir/dir/key1", "dir/key/d");
            Map<String, Integer> map = new HashMap<>();
            int i = 0;
            for (String key : list) {
                client.createKey(getData(i)
                        .getBytes(UTF_8), new CommitData(key, branch, "new key", "user", "mail"), new MetaData(APPLICATION_JSON, roleOf("read"), roleOf("write")));
                map.put(key, i);
                i++;
            }
            client.createKey(data
                    .getBytes(UTF_8), new CommitData("dir/key3", branch, "new key", "user", "mail"), new MetaData(APPLICATION_JSON, roleOf("read2"), roleOf("write2")));
            client.createKey(data
                    .getBytes(UTF_8), new CommitData("dir/dir/key3", branch, "new key", "user", "mail"), new MetaData(APPLICATION_JSON, roleOf("read2"), roleOf("write2")));
            KeyDataWrapper result = updaterClient.listAll("dir/", true, readKeyData());
            assertNotNull(result);
            assertEquals(4, result.getResult().size());
            assertEquals("dir/dir/key1", result.getResult().get(0).getKey());
            assertArrayEquals(getData(map.get("dir/dir/key1")).getBytes(UTF_8), toByte(result.getResult().get(0).getData()));
            assertEquals("dir/key/d", result.getResult().get(1).getKey());
            assertArrayEquals(getData(map.get("dir/key/d")).getBytes(UTF_8), toByte(result.getResult().get(1).getData()));
            assertEquals("dir/key1", result.getResult().get(2).getKey());
            assertArrayEquals(getData(map.get("dir/key1")).getBytes(UTF_8), toByte(result.getResult().get(2).getData()));
            assertEquals("dir/key2", result.getResult().get(3).getKey());
            assertArrayEquals(getData(map.get("dir/key2")).getBytes(UTF_8), toByte(result.getResult().get(3).getData()));
        }
    }

    @Test
    public void testListAllFilesTreeWithHidden() throws Exception {
        HostedFactory hostedFactory = DW.getConfiguration().getHostedFactory();
        String user = hostedFactory.getUserName();
        String pass = hostedFactory.getSecret();
        String data = getData(3);
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(user).setPassword(pass).build();
                JitStaticClient updaterClient = buildClient(DW.getLocalPort()).setUser(USER).setPassword(PASSWORD).build();) {
            List<String> list = List.of("key1", "key2", "dir/key1", "dir/dir/key1");
            for (String key : list) {
                client.createKey(data
                        .getBytes(UTF_8), new CommitData(key, REFS_HEADS_MASTER, "new key", "user", "mail"), new MetaData(APPLICATION_JSON, roleOf("read"), roleOf("write")));
            }
            client.createKey(data
                    .getBytes(UTF_8), new CommitData("dir/key2", REFS_HEADS_MASTER, "new key", "user", "mail"), new MetaData(APPLICATION_JSON, false, true, roleOf("read"), roleOf("write")));

            client.createKey(data
                    .getBytes(UTF_8), new CommitData("dir/key3", REFS_HEADS_MASTER, "new key", "user", "mail"), new MetaData(APPLICATION_JSON, roleOf("read2"), roleOf("write2")));
            KeyDataWrapper result = updaterClient.listAll("dir/", readKeyData());
            assertNotNull(result);
            assertEquals(1, result.getResult().size());
            assertEquals("dir/key1", result.getResult().get(0).getKey());
        }
    }

    @Test
    public void testListAllFilesLight() throws Exception {
        HostedFactory hostedFactory = DW.getConfiguration().getHostedFactory();
        String user = hostedFactory.getUserName();
        String pass = hostedFactory.getSecret();
        String data = getData(3);
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(user).setPassword(pass).build();
                JitStaticClient updaterClient = buildClient(DW.getLocalPort()).setUser(USER).setPassword(PASSWORD).build();) {
            List<String> list = List.of("key1", "key2", "dir/key1", "dir/key2");
            for (String key : list) {
                client.createKey(data
                        .getBytes(UTF_8), new CommitData(key, REFS_HEADS_MASTER, "new key", "user", "mail"), new MetaData(APPLICATION_JSON, roleOf("read"), roleOf("write")));
            }
            client.createKey(data
                    .getBytes(UTF_8), new CommitData("key3", REFS_HEADS_MASTER, "new key", "user", "mail"), new MetaData(APPLICATION_JSON, roleOf("read2"), roleOf("write")));
            KeyDataWrapper result = updaterClient.listAll("/", false, true, readKeyData());
            assertNotNull(result);
            assertEquals(2, result.getResult().size(), result.toString());
            assertEquals("key1", result.getResult().get(0).getKey());
            assertEquals("key2", result.getResult().get(1).getKey());
            assertNull(result.getResult().get(0).getData());
            assertNull(result.getResult().get(1).getData());

        }
    }

    @Test
    public void testAccessDirectoryKeyWithoutSlash() throws Exception {
        HostedFactory hostedFactory = DW.getConfiguration().getHostedFactory();
        String user = hostedFactory.getUserName();
        String pass = hostedFactory.getSecret();
        String servletName = hostedFactory.getServletName();
        String endpoint = hostedFactory.getHostedEndpoint();
        String data = getData(3);

        File workingDirectory = getFolderFile();
        UsernamePasswordCredentialsProvider provider = new UsernamePasswordCredentialsProvider(user, pass);
        try (Git git = Git.cloneRepository().setDirectory(workingDirectory).setURI(adress + "/" + servletName + "/" + endpoint).setCredentialsProvider(provider)
                .call()) {
            Path dst = workingDirectory.toPath().resolve("test1").resolve("test2");
            dst.toFile().mkdirs();
            Files.write(dst.resolve(METADATA), getMetaDataAsString().getBytes(UTF_8));
            Files.write(dst.resolve("key1"), getData(1).getBytes(UTF_8));
            Files.write(dst.resolve("key2"), getData(2).getBytes(UTF_8));
            Path nxt = dst.resolve("test3");
            nxt.toFile().mkdirs();
            Files.write(nxt.resolve(METADATA), getMetaDataAsString().getBytes(UTF_8));
            Files.write(nxt.resolve("key4"), getData(4).getBytes(UTF_8));
            git.add().addFilepattern(".").call();
            git.commit().setMessage("Initial commit").call();
            verifyOkPush(git.push().setCredentialsProvider(provider).call());
        }
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(user).setPassword(pass).build();
                JitStaticClient updaterClient = buildClient(DW.getLocalPort()).setUser(USER).setPassword(PASSWORD).build();
                JitStaticClient clientNoCred = buildClient(DW.getLocalPort()).build();
                JitStaticClient updaterClientNoCred = buildClient(DW.getLocalPort()).build();) {
            List<String> list = List.of("key1", "key2", "dir/key1", "dir/key2");
            for (String key : list) {
                client.createKey(data
                        .getBytes(UTF_8), new CommitData(key, REFS_HEADS_MASTER, "new key", "user", "mail"), new MetaData(APPLICATION_JSON, roleOf("read"), roleOf("write")));
            }

            assertEquals(NOT_FOUND_404, assertThrows(APIException.class, () -> updaterClient.getKey("test1/test2", parse(JsonNode.class))).getStatusCode());

            KeyDataWrapper keyData = updaterClient.listAll("test1/test2/", readKeyData());
            assertNotNull(keyData);
            assertFalse(keyData.getResult().isEmpty());
            assertEquals(NOT_FOUND_404, assertThrows(APIException.class, () -> updaterClient.getKey("test1/test2", parse(JsonNode.class))).getStatusCode());
            assertEquals(NOT_FOUND_404, assertThrows(APIException.class, () -> updaterClientNoCred.getKey("test1/test2", parse(JsonNode.class)))
                    .getStatusCode());
        }
    }

    private Function<InputStream, KeyDataWrapper> readKeyData() {
        return (is) -> {
            try {
                return MAPPER.readValue(is, KeyDataWrapper.class);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }

    @Test
    public void testGetAndAddSecretUser() throws Exception {
        HostedFactory hostedFactory = DW.getConfiguration().getHostedFactory();
        String user = hostedFactory.getUserName();
        String pass = hostedFactory.getSecret();
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(user).setPassword(pass).build()) {
            final String key = USERS + "/" + JITSTATIC_GIT_REALM + "/user";
            assertEquals(NOT_FOUND_404, assertThrows(APIException.class, () -> client.getKey(key, parse(JsonNode.class))).getStatusCode());
            assertEquals(METHOD_NOT_ALLOWED_405, assertThrows(APIException.class, () -> client
                    .createKey(getData()
                            .getBytes(UTF_8), new CommitData(key, "msg", "info", "mail"), new MetaData(APPLICATION_JSON, false, false, roleOf("read"), roleOf("write"))))
                                    .getStatusCode());
            assertThrows(APIException.class, () -> client.getKey(key, parse(JsonNode.class)));
            assertThrows(APIException.class, () -> client.getKey(key, GIT_SECRETS, parse(JsonNode.class)));
            assertEquals(METHOD_NOT_ALLOWED_405, assertThrows(APIException.class, () -> {
                client.createKey(getData()
                        .getBytes(UTF_8), new CommitData(key, GIT_SECRETS, "msg", "info", "mail"), new MetaData(APPLICATION_JSON, false, false, roleOf("read"), roleOf("write")));
            }).getStatusCode());
            assertEquals(NOT_FOUND_404, assertThrows(APIException.class, () -> client.getKey(key, parse(JsonNode.class))).getStatusCode());
        }
    }

    @Test
    public void testHeadRequestOnASecureResource() throws UnirestException, URISyntaxException, APIException, IOException {
        String tag;
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(USER).setPassword(PASSWORD).build()) {
            Entity<JsonNode> key = client.getKey(ACCEPT_STORAGE, parse(JsonNode.class));
            tag = key.getTag();
        }
        HttpResponse<String> asString = Unirest.head(String.format("http://localhost:%s/application/storage/" + ACCEPT_STORAGE, DW.getLocalPort()))
                .header("if-none-match", tag)
                .header("Authorization", "Basic " + baseEncode(USER, PASSWORD)).asString();
        assertEquals(HttpStatus.NOT_MODIFIED_304, asString.getStatus());
        HttpResponse<String> blocked = Unirest.head(String.format("http://localhost:%s/application/storage/" + ACCEPT_STORAGE, DW.getLocalPort())).asString();
        assertEquals(HttpStatus.FORBIDDEN_403, blocked.getStatus());
    }

    @Override
    protected File getFolderFile() throws IOException { return tmpfolder.createTemporaryDirectory(); }

    private void writeFile(Path workBase, String file) throws IOException {
        final Path filePath = workBase.resolve(file);
        Files.createDirectories(Objects.requireNonNull(filePath.getParent()));
        try (InputStream is = getClass().getResourceAsStream("/" + file)) {
            Files.copy(is, filePath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String getMetaDataAsString() throws JsonProcessingException {
        return MAPPER.writeValueAsString(new MetaData(APPLICATION_JSON, Set.of(new io.jitstatic.client.MetaData.Role("read")), Set
                .of(new io.jitstatic.client.MetaData.Role("write"))));
    }

    private TriFunction<InputStream, String, String, Entity<String>> stringFactory = (is, v, t) -> {
        try (Scanner s = new Scanner(is, "UTF-8").useDelimiter("\\A")) {
            String result = s.hasNext() ? s.next() : "";
            return new Entity<String>(v, t, result);
        }
    };

    private Set<io.jitstatic.client.MetaData.Role> toRole(Set<io.jitstatic.Role> write) {
        return write.stream().map(r -> new io.jitstatic.client.MetaData.Role(r.getRole())).collect(Collectors.toSet());
    }

}
