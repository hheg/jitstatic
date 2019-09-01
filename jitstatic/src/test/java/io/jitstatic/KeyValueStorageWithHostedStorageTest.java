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

import static io.jitstatic.JitStaticConstants.GIT_REALM;
import static io.jitstatic.JitStaticConstants.SECRETS;
import static io.jitstatic.JitStaticConstants.USERS;
import static io.jitstatic.source.ObjectStreamProvider.toByte;
import static org.eclipse.jetty.http.HttpStatus.FORBIDDEN_403;
import static org.eclipse.jetty.http.HttpStatus.NOT_FOUND_404;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Scanner;
import java.util.Set;
import java.util.SortedMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.apache.http.client.ClientProtocolException;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate.Status;
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

import io.dropwizard.testing.ConfigOverride;
import io.dropwizard.testing.junit5.DropwizardAppExtension;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.jitstatic.api.KeyDataWrapper;
import io.jitstatic.client.APIException;
import io.jitstatic.client.CommitData;
import io.jitstatic.client.JitStaticClient;
import io.jitstatic.client.JitStaticClientBuilder;
import io.jitstatic.client.MetaData;
import io.jitstatic.client.MetaData.User;
import io.jitstatic.client.ModifyUserKeyData;
import io.jitstatic.client.TriFunction;
import io.jitstatic.hosted.HostedFactory;
import io.jitstatic.test.TemporaryFolder;
import io.jitstatic.test.TemporaryFolderExtension;
import io.jitstatic.tools.AUtils;

@ExtendWith({ TemporaryFolderExtension.class, DropwizardExtensionsSupport.class })
public class KeyValueStorageWithHostedStorageTest {

    private static final String METADATA = ".metadata";
    private static final String REFS_HEADS_NEWBRANCH = "refs/heads/newbranch";
    private static final String REFS_HEADS_MASTER = "refs/heads/master";
    private static final String APPLICATION_JSON = "application/json";
    private static final Charset UTF_8 = StandardCharsets.UTF_8;
    private static final String ACCEPT_STORAGE = "accept/storage";
    private static final String USER = "suser";
    private static final String PASSWORD = "ssecret";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private DropwizardAppExtension<JitstaticConfiguration> DW = new DropwizardAppExtension<>(JitstaticApplication.class,
            AUtils.getDropwizardConfigurationResource(), ConfigOverride.config("hosted.basePath", getFolder()));
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
            Iterable<PushResult> call = git.push().setCredentialsProvider(provider).call();
            assertTrue(StreamSupport.stream(call.spliterator(), false).allMatch(p -> p.getRemoteUpdate(REFS_HEADS_MASTER).getStatus() == Status.OK));
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
            try (JitStaticClient client = buildClient().build();) {
                client.getKey("nokey", null, tf);
            }
        }).getMessage(), CoreMatchers.containsString("/application/storage/nokey failed with: 404 Not Found"));
    }

    @Test
    public void testGetNotFoundKeyWithAuth() throws Exception {
        assertThat(assertThrows(APIException.class, () -> {
            try (JitStaticClient client = buildClient().setUser(USER).setPassword(PASSWORD).build();) {
                client.getKey("nokey", null, tf);
            }
        }).getMessage(), CoreMatchers.containsString("application/storage/nokey failed with: 404 Not Found"));
    }

    @Test
    public void testGetAKeyValue() throws Exception {
        try (JitStaticClient client = buildClient().setUser(USER).setPassword(PASSWORD).build();) {
            Entity<JsonNode> key = client.getKey(ACCEPT_STORAGE, null, tf);
            assertEquals(getData(), key.data.toString());
            assertNotNull(key.getTag());
        }
    }

    @Test
    public void testGetAKeyValueWithEtag() throws Exception {
        try (JitStaticClient client = buildClient().setUser(USER).setPassword(PASSWORD).build();) {
            Entity<JsonNode> key = client.getKey(ACCEPT_STORAGE, null, tf);
            assertEquals(getData(), key.data.toString());
            assertNotNull(key.getTag());
            Entity<JsonNode> key2 = client.getKey(ACCEPT_STORAGE, tf, key.getTag());
            assertEquals(key.tag, key2.tag);
        }
    }

    @Test
    public void testGetAKeyValueWithoutAuth() throws Exception {
        assertThat(assertThrows(APIException.class, () -> {
            try (JitStaticClient client = buildClient().build();) {
                client.getKey(ACCEPT_STORAGE, null, tf);
            }
        }).getMessage(), CoreMatchers.containsString("/application/storage/accept/storage failed with: 401 Unauthorized"));
    }

    @Test
    public void testModifyAKey() throws Exception {
        try (JitStaticClient client = buildClient().setUser(USER).setPassword(PASSWORD).build();) {
            Entity<JsonNode> key = client.getKey(ACCEPT_STORAGE, null, tf);
            assertEquals(getData(), key.data.toString());
            String oldVersion = key.getTag();
            byte[] newData = "{\"one\":\"two\"}".getBytes(UTF_8);
            String modifyKey = client.modifyKey(newData, new CommitData(ACCEPT_STORAGE, "master", "commit message", "user1", "user@mail"), key.getTag());
            assertNotEquals(oldVersion, modifyKey);
            key = client.getKey(ACCEPT_STORAGE, null, tf);
            assertEquals(new String(newData, "UTF-8"), key.data.toString());
        }
    }

    @Test
    public void testPrettifiedKey() throws Exception {
        try (JitStaticClient client = buildClient().setUser(USER).setPassword(PASSWORD).build();) {
            Entity<String> key = client.getKey(ACCEPT_STORAGE, null, stringFactory);
            assertEquals(getData(), key.data.toString());
            String oldVersion = key.getTag();
            byte[] prettyData = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(MAPPER.readTree(getData()));
            String modifyKey = client.modifyKey(prettyData, new CommitData(ACCEPT_STORAGE, "master", "commit message", "user1", "user@mail"), key.getTag());
            assertNotEquals(oldVersion, modifyKey);
            key = client.getKey(ACCEPT_STORAGE, null, stringFactory);
            assertEquals(new String(prettyData, "UTF-8"), key.data);
        }
    }

    @Test
    public void testAddKey() throws Exception {
        HostedFactory hostedFactory = DW.getConfiguration().getHostedFactory();
        String user = hostedFactory.getUserName();
        String pass = hostedFactory.getSecret();
        JitStaticClientBuilder builder = JitStaticClient.create().setHost("localhost").setPort(DW.getLocalPort()).setAppContext("/application/").setUser(user)
                .setPassword(pass);

        try (JitStaticClient client = builder.build(); JitStaticClient getter = buildClient().build()) {
            String createKey = client.createKey(getData().getBytes(UTF_8), new CommitData("base/newkey", "master", "commit message", "user1", "user@mail"),
                    new MetaData(new HashSet<>(), APPLICATION_JSON));
            Entity<String> key = getter.getKey("base/newkey", stringFactory);
            assertArrayEquals(getData().getBytes(UTF_8), key.data.getBytes(UTF_8));
            assertEquals(createKey, key.getTag());
        }
    }

    @Test
    public void testAddKeyWhenChecked() throws Exception {
        HostedFactory hostedFactory = DW.getConfiguration().getHostedFactory();
        String user = hostedFactory.getUserName();
        String pass = hostedFactory.getSecret();
        JitStaticClientBuilder builder = JitStaticClient.create().setHost("localhost").setPort(DW.getLocalPort()).setAppContext("/application/").setUser(user)
                .setPassword(pass);

        try (JitStaticClient client = builder.build(); JitStaticClient getter = buildClient().build()) {
            assertEquals(NOT_FOUND_404, assertThrows(APIException.class, () -> getter.getKey("base/mid/newkey", stringFactory)).getStatusCode());
            String createKey = client.createKey(getData().getBytes(UTF_8),
                    new CommitData("base/mid/new%20key", "master", "commit message", "user1", "user@mail"), new MetaData(new HashSet<>(), APPLICATION_JSON));
            Entity<String> key = getter.getKey("base/mid/new%20key", stringFactory);
            assertEquals(NOT_FOUND_404, assertThrows(APIException.class, () -> getter.getKey("base/mid/new", stringFactory)).getStatusCode());
            assertArrayEquals(getData().getBytes(UTF_8), key.data.getBytes(UTF_8));
            assertEquals(createKey, key.getTag());
        }
    }

    @Test
    public void testModifyUserKey() throws Exception {
        HostedFactory hostedFactory = DW.getConfiguration().getHostedFactory();
        String root = hostedFactory.getUserName();
        String pass = hostedFactory.getSecret();
        try (JitStaticClient client = buildClient().setUser(root).setPassword(pass).build();
                JitStaticClient firstUpdater = buildClient().setUser(USER).setPassword(PASSWORD).build();
                JitStaticClient secondUpdater = buildClient().setUser(root).setPassword(pass).build();
                JitStaticClient thirdUpdater = buildClient().setUser("random").setPassword("randompass").build()) {
            Entity<JsonNode> key = firstUpdater.getKey(ACCEPT_STORAGE, tf);
            assertNotNull(key);
            key = secondUpdater.getKey(ACCEPT_STORAGE, tf);
            assertNotNull(key);
            try {
                thirdUpdater.getKey(ACCEPT_STORAGE, tf);
                fail();
            } catch (APIException e) {
                assertEquals(FORBIDDEN_403, e.getStatusCode());
            }
            Entity<JsonNode> metaKey = client.getMetaKey(ACCEPT_STORAGE, null, tf);
            String oldVersion = metaKey.getTag();
            String modifyKey = client.modifyMetaKey(ACCEPT_STORAGE, null, metaKey.getTag(),
                    new ModifyUserKeyData(new MetaData(Set.of(new User(root, pass)), "plain/text"), "msg", "mail", "info"));
            assertNotEquals(oldVersion, modifyKey);
            metaKey = client.getMetaKey(ACCEPT_STORAGE, null, tf);
            assertEquals("plain/text", metaKey.data.get("contentType").asText());
            key = secondUpdater.getKey(ACCEPT_STORAGE, tf);
            assertNotNull(key);
            try {
                firstUpdater.getKey(ACCEPT_STORAGE, tf);
                fail();
            } catch (APIException e) {
                assertEquals(FORBIDDEN_403, e.getStatusCode());
            }
        }
    }

    @Test
    public void testModifyMasterMetaData() throws URISyntaxException, ClientProtocolException, IOException {
        HostedFactory hostedFactory = DW.getConfiguration().getHostedFactory();
        String user = hostedFactory.getUserName();
        String pass = hostedFactory.getSecret();

        try (JitStaticClient client = buildClient().setUser(user).setPassword(pass).build();
                JitStaticClient firstUpdater = buildClient().setUser(USER).setPassword(PASSWORD).build();
                JitStaticClient secondUpdater = buildClient().setUser(user).setPassword(pass).build()) {
            Entity<JsonNode> key = firstUpdater.getKey("accept/genkey", tf);
            assertNotNull(key);
            Entity<JsonNode> metaKey = client.getMetaKey("accept/", null, tf);

            String modifyMetaKey = client.modifyMetaKey("accept/", null, metaKey.tag,
                    new ModifyUserKeyData(new MetaData(Set.of(new User(user, pass)), APPLICATION_JSON, true, false, List.of()), "msg", "mail", "info"));
            assertNotEquals(metaKey.tag, modifyMetaKey);
            Entity<JsonNode> key2 = secondUpdater.getKey("accept/genkey", tf);
            assertNotNull(key2);
        }
    }

    @Test
    public void testDeleteKey() throws Exception {
        try (JitStaticClient client = buildClient().setUser(USER).setPassword(PASSWORD).build();) {
            Entity<JsonNode> key = client.getKey(ACCEPT_STORAGE, null, tf);
            assertEquals(getData(), key.data.toString());
            assertNotNull(key.getTag());
            client.delete(new CommitData(ACCEPT_STORAGE, "message", "user", "mail"));
            Thread.sleep(100);
            assertEquals(NOT_FOUND_404, assertThrows(APIException.class, () -> client.getKey(ACCEPT_STORAGE, null, tf)).getStatusCode());
        }
    }

    @Test
    public void testAddBranchAndKey() throws URISyntaxException, ClientProtocolException, APIException, IOException {
        HostedFactory hostedFactory = DW.getConfiguration().getHostedFactory();
        String user = hostedFactory.getUserName();
        String pass = hostedFactory.getSecret();
        String data = getData(3);
        assertEquals(NOT_FOUND_404, assertThrows(APIException.class, () -> {
            try (JitStaticClient client = buildClient().setUser(user).setPassword(pass).build();) {
                client.createKey(data.getBytes(UTF_8), new CommitData("key", REFS_HEADS_NEWBRANCH, "new key", "user", "mail"),
                        new MetaData(APPLICATION_JSON));
            }
        }).getStatusCode());
    }

    @Test
    public void addKeyAndDeleteAndThenAddAgain() throws Exception {
        HostedFactory hostedFactory = DW.getConfiguration().getHostedFactory();
        String user = hostedFactory.getUserName();
        String pass = hostedFactory.getSecret();
        String branch = REFS_HEADS_MASTER;
        String data = getData(3);
        try (JitStaticClient client = buildClient().setUser(user).setPassword(pass).build();
                JitStaticClient updaterClient = buildClient().setUser(USER).setPassword(PASSWORD).build();) {
            client.createKey(data.getBytes(UTF_8), new CommitData("key", branch, "new key", "user", "mail"),
                    new MetaData(Set.of(new User(USER, PASSWORD)), APPLICATION_JSON));

            Entity<JsonNode> key = updaterClient.getKey("key", branch, tf);
            assertEquals(data, key.data.toString());
            updaterClient.delete(new CommitData("key", branch, "delete key", "user", "mail"));
            assertEquals(404, assertThrows(APIException.class, () -> updaterClient.getKey("key", branch, tf)).getStatusCode());
            client.createKey(data.getBytes(UTF_8), new CommitData("key", branch, "new key", "user", "mail"), new MetaData(APPLICATION_JSON));
            key = updaterClient.getKey("key", branch, tf);
            assertEquals(data, key.data.toString());
            assertEquals(400,
                    assertThrows(APIException.class, () -> updaterClient.delete(new CommitData("key", branch, "delete key", "user", "mail"))).getStatusCode());
        }
    }

    @Test
    public void testListAllFiles() throws Exception {
        HostedFactory hostedFactory = DW.getConfiguration().getHostedFactory();
        String user = hostedFactory.getUserName();
        String pass = hostedFactory.getSecret();
        String branch = REFS_HEADS_MASTER;
        String data = getData(3);
        try (JitStaticClient client = buildClient().setUser(user).setPassword(pass).build();
                JitStaticClient updaterClient = buildClient().setUser(USER).setPassword(PASSWORD).build();) {
            List<String> list = List.of("key1", "key2", "dir/key1", "dir/key2");
            for (String key : list) {
                client.createKey(data.getBytes(UTF_8), new CommitData(key, branch, "new key", "user", "mail"),
                        new MetaData(Set.of(new User(USER, PASSWORD)), APPLICATION_JSON));
            }
            client.createKey(data.getBytes(UTF_8), new CommitData("key3", branch, "new key", "user", "mail"),
                    new MetaData(Set.of(new User("someother", PASSWORD)), APPLICATION_JSON));
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
        String user = hostedFactory.getUserName();
        String pass = hostedFactory.getSecret();
        String branch = REFS_HEADS_MASTER;
        String data = getData(3);
        try (JitStaticClient client = buildClient().setUser(user).setPassword(pass).build();
                JitStaticClient updaterClient = buildClient().setUser(USER).setPassword(PASSWORD).build();) {
            List<String> list = List.of("key1", "key2", "dir/key1", "dir/key2", "dir/dir/key1", "dir/k", "k", "di/k");
            for (String key : list) {
                client.createKey(data.getBytes(UTF_8), new CommitData(key, branch, "new key", "user", "mail"),
                        new MetaData(Set.of(new User(USER, PASSWORD)), APPLICATION_JSON));
            }
            client.createKey(data.getBytes(UTF_8), new CommitData("dir/key3", branch, "new key", "user", "mail"),
                    new MetaData(Set.of(new User("someother", PASSWORD)), APPLICATION_JSON));
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
        String user = hostedFactory.getUserName();
        String pass = hostedFactory.getSecret();
        String branch = REFS_HEADS_MASTER;
        String data = getData(3);
        try (JitStaticClient client = buildClient().setUser(user).setPassword(pass).build();
                JitStaticClient updaterClient = buildClient().setUser(USER).setPassword(PASSWORD).build();) {
            List<String> list = List.of("key1", "key2", "dir/key1", "dir/key2", "dir/dir/key1", "dir/key/d");
            Map<String, Integer> map = new HashMap<>();
            int i = 0;
            for (String key : list) {
                client.createKey(getData(i).getBytes(UTF_8), new CommitData(key, branch, "new key", "user", "mail"),
                        new MetaData(Set.of(new User(USER, PASSWORD)), APPLICATION_JSON));
                map.put(key, i);
                i++;
            }
            client.createKey(data.getBytes(UTF_8), new CommitData("dir/key3", branch, "new key", "user", "mail"),
                    new MetaData(Set.of(new User("someother", PASSWORD)), APPLICATION_JSON));
            client.createKey(data.getBytes(UTF_8), new CommitData("dir/dir/key3", branch, "new key", "user", "mail"),
                    new MetaData(Set.of(new User("someother", PASSWORD)), APPLICATION_JSON));
            KeyDataWrapper result = updaterClient.listAll("dir/", true, readKeyData());
            System.out.println(result);
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
        try (JitStaticClient client = buildClient().setUser(user).setPassword(pass).build();
                JitStaticClient updaterClient = buildClient().setUser(USER).setPassword(PASSWORD).build();) {
            List<String> list = List.of("key1", "key2", "dir/key1", "dir/dir/key1");
            for (String key : list) {
                client.createKey(data.getBytes(UTF_8), new CommitData(key, REFS_HEADS_MASTER, "new key", "user", "mail"),
                        new MetaData(Set.of(new User(USER, PASSWORD)), APPLICATION_JSON));
            }
            client.createKey(data.getBytes(UTF_8), new CommitData("dir/key2", REFS_HEADS_MASTER, "new key", "user", "mail"),
                    new MetaData(Set.of(new User(USER, PASSWORD)), APPLICATION_JSON, false, true, List.of()));

            client.createKey(data.getBytes(UTF_8), new CommitData("dir/key3", REFS_HEADS_MASTER, "new key", "user", "mail"),
                    new MetaData(Set.of(new User("someother", PASSWORD)), APPLICATION_JSON));
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
        try (JitStaticClient client = buildClient().setUser(user).setPassword(pass).build();
                JitStaticClient updaterClient = buildClient().setUser(USER).setPassword(PASSWORD).build();) {
            List<String> list = List.of("key1", "key2", "dir/key1", "dir/key2");
            for (String key : list) {
                client.createKey(data.getBytes(UTF_8), new CommitData(key, REFS_HEADS_MASTER, "new key", "user", "mail"),
                        new MetaData(Set.of(new User(USER, PASSWORD)), APPLICATION_JSON));
            }
            client.createKey(data.getBytes(UTF_8), new CommitData("key3", REFS_HEADS_MASTER, "new key", "user", "mail"),
                    new MetaData(Set.of(new User("someother", PASSWORD)), APPLICATION_JSON));
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
            Files.write(dst.resolve(METADATA), getMetaData(Set.of(new User(USER, PASSWORD))).getBytes(UTF_8));
            Files.write(dst.resolve("key1"), getData(1).getBytes(UTF_8));
            Files.write(dst.resolve("key2"), getData(2).getBytes(UTF_8));
            Path nxt = dst.resolve("test3");
            nxt.toFile().mkdirs();
            Files.write(nxt.resolve(METADATA), getMetaData(Set.of(new User(USER, PASSWORD))).getBytes(UTF_8));
            Files.write(nxt.resolve("key4"), getData(4).getBytes(UTF_8));
            git.add().addFilepattern(".").call();
            git.commit().setMessage("Initial commit").call();
            Iterable<PushResult> call = git.push().setCredentialsProvider(provider).call();
            assertTrue(StreamSupport.stream(call.spliterator(), false).allMatch(p -> p.getRemoteUpdate(REFS_HEADS_MASTER).getStatus() == Status.OK));

        }
        try (JitStaticClient client = buildClient().setUser(user).setPassword(pass).build();
                JitStaticClient updaterClient = buildClient().setUser(USER).setPassword(PASSWORD).build();
                JitStaticClient clientNoCred = buildClient().build();
                JitStaticClient updaterClientNoCred = buildClient().build();) {
            List<String> list = List.of("key1", "key2", "dir/key1", "dir/key2");
            for (String key : list) {
                client.createKey(data.getBytes(UTF_8), new CommitData(key, REFS_HEADS_MASTER, "new key", "user", "mail"),
                        new MetaData(Set.of(new User(USER, PASSWORD)), APPLICATION_JSON));
            }

            assertEquals(NOT_FOUND_404, assertThrows(APIException.class, () -> updaterClient.getKey("test1/test2", tf)).getStatusCode());

            KeyDataWrapper keyData = updaterClient.listAll("test1/test2/", readKeyData());
            assertNotNull(keyData);
            assertFalse(keyData.getResult().isEmpty());
            assertEquals(NOT_FOUND_404, assertThrows(APIException.class, () -> updaterClient.getKey("test1/test2", tf)).getStatusCode());
            assertEquals(NOT_FOUND_404, assertThrows(APIException.class, () -> updaterClientNoCred.getKey("test1/test2", tf)).getStatusCode());
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
        try (JitStaticClient client = buildClient().setUser(user).setPassword(pass).build()) {
            final String key = USERS + "/" + GIT_REALM + "/user";
            assertEquals(NOT_FOUND_404, assertThrows(APIException.class, () -> client.getKey(key, tf)).getStatusCode());
            assertEquals(FORBIDDEN_403,
                    assertThrows(APIException.class, () -> client.createKey(getData().getBytes(UTF_8), new CommitData(key, "msg", "info", "mail"),
                            new MetaData(Set.of(new User(USER, PASSWORD)), APPLICATION_JSON, false, false, List.of()))).getStatusCode());
            assertThrows(APIException.class, () -> client.getKey(key, tf));
            assertThrows(APIException.class, () -> client.getKey(key, SECRETS, tf));
            assertEquals(FORBIDDEN_403, assertThrows(APIException.class, () -> {
                client.createKey(getData().getBytes(UTF_8), new CommitData(key, SECRETS, "msg", "info", "mail"),
                        new MetaData(Set.of(new User(USER, PASSWORD)), APPLICATION_JSON, false, false, List.of()));
            }).getStatusCode());
            assertEquals(NOT_FOUND_404, assertThrows(APIException.class, () -> client.getKey(key, tf)).getStatusCode());
        }
    }

    private Supplier<String> getFolder() {
        return () -> {
            try {
                return getFolderFile().getAbsolutePath();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        };
    }

    private File getFolderFile() throws IOException {
        return tmpfolder.createTemporaryDirectory();
    }

    private JitStaticClientBuilder buildClient() {
        return JitStaticClient.create().setHost("localhost").setPort(DW.getLocalPort()).setAppContext("/application/");
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

    private static String getMetaData(Set<User> users) throws JsonProcessingException {
        MetaData data = new MetaData(users, APPLICATION_JSON);
        return MAPPER.writeValueAsString(data);
    }

    private TriFunction<InputStream, String, String, Entity<String>> stringFactory = (is, v, t) -> {
        try (Scanner s = new Scanner(is, "UTF-8").useDelimiter("\\A")) {
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
        if (is != null) {
            try {
                return new Entity<>(v, t, MAPPER.readValue(is, JsonNode.class));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return new Entity<>(v, t, null);
    };
}
