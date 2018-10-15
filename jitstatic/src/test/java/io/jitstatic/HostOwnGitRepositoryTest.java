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
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.RemoteRefUpdate.Status;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.hamcrest.CoreMatchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.fasterxml.jackson.core.JsonParser.Feature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.dropwizard.testing.ConfigOverride;
import io.dropwizard.testing.ResourceHelpers;
import io.dropwizard.testing.junit5.DropwizardAppExtension;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.jitstatic.auth.UserData;
import io.jitstatic.client.APIException;
import io.jitstatic.client.CommitData;
import io.jitstatic.client.JitStaticClient;
import io.jitstatic.client.JitStaticClientBuilder;
import io.jitstatic.client.MetaData;
import io.jitstatic.client.TriFunction;
import io.jitstatic.hosted.HostedFactory;
import io.jitstatic.test.TemporaryFolder;
import io.jitstatic.test.TemporaryFolderExtension;
import io.jitstatic.tools.Utils;

@ExtendWith({ TemporaryFolderExtension.class, DropwizardExtensionsSupport.class })
public class HostOwnGitRepositoryTest {

    private static final Charset UTF_8 = StandardCharsets.UTF_8;
    private static final String METADATA = ".metadata";
    private static final String STORE = "store";
    private static final String REFS_HEADS_NEWBRANCH = "refs/heads/newbranch";
    private static final String REFS_HEADS_MASTER = "refs/heads/master";
    private static final String USER = "suser";
    private static final String PASSWORD = "ssecret";

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(Feature.ALLOW_COMMENTS);
    private TemporaryFolder tmpFolder;
    private DropwizardAppExtension<JitstaticConfiguration> DW = new DropwizardAppExtension<>(JitstaticApplication.class,
            ResourceHelpers.resourceFilePath("simpleserver.yaml"), ConfigOverride.config("hosted.basePath", getFolderString()));

    private UsernamePasswordCredentialsProvider provider;

    private String gitAdress;

    @BeforeEach
    public void setupClass() throws IOException {
        final HostedFactory hf = DW.getConfiguration().getHostedFactory();
        provider = new UsernamePasswordCredentialsProvider(hf.getUserName(), hf.getSecret());
        int localPort = DW.getLocalPort();
        gitAdress = String.format("http://localhost:%d/application/%s/%s", localPort, hf.getServletName(), hf.getHostedEndpoint());
    }

    @AfterEach
    public void after() {
        Utils.checkContainerForErrors(DW);
    }

    @Test
    public void testCloneOwnHostedRepository() throws Exception {
        String localFilePath = STORE;
        try (Git git = Git.cloneRepository().setDirectory(getFolder().toFile()).setCredentialsProvider(provider).setURI(gitAdress).call()) {
            Path path = createData(localFilePath, git);
            assertTrue(Files.exists(path));
        }
        try (Git git = Git.cloneRepository().setDirectory(getFolder().toFile()).setCredentialsProvider(provider).setURI(gitAdress).call()) {
            Path path = Paths.get(getRepopath(git), localFilePath);
            Path mpath = Paths.get(getRepopath(git), localFilePath + METADATA);
            assertTrue(Files.exists(path));
            assertTrue(Files.exists(mpath));
        }
    }

    @Test
    public void testGetFromAnEmptyStorage() throws Exception {
        assertThat(assertThrows(APIException.class, () -> {
            try (JitStaticClient client = buildClient()) {
                client.getKey("key1", "master", tf);
            }
        }).getMessage(), CoreMatchers.containsString("/application/storage/key1?ref=refs%2Fheads%2Fmaster failed with: 404 Not Found"));
    }

    @Test
    public void testPushToOwnHostedRepositoryAndFetchResultFromKeyValuestorage() throws Exception {
        try (Git git = Git.cloneRepository().setDirectory(getFolder().toFile()).setURI(gitAdress).setCredentialsProvider(provider).call()) {
            createData(STORE, git);
        }
        try (JitStaticClient client = buildClient()) {
            Entity key = client.getKey(STORE, REFS_HEADS_MASTER, tf);
            assertEquals(getData(), key.data.toString());
        }
    }

    @Test
    public void testPushToOwnHostedRepositoryAndFetchResultFromDoubleKeyValuestorage() throws Exception {
        String localFilePath = "key/key1";
        try (Git git = Git.cloneRepository().setDirectory(getFolder().toFile()).setURI(gitAdress).setCredentialsProvider(provider).call()) {
            createData(localFilePath, git);
        }
        try (JitStaticClient client = buildClient()) {
            Entity key = client.getKey(localFilePath, REFS_HEADS_MASTER, tf);
            assertEquals(getData(), key.data.toString());
        }
    }

    @Test
    public void testPushToOwnHostedRepositoryWithBrokenJSONStorage() throws Exception {
        String originalSHA = null;
        String localFilePath = STORE;
        try (Git git = Git.cloneRepository().setDirectory(getFolder().toFile()).setURI(gitAdress).setCredentialsProvider(provider).call()) {
            Path path = createData(localFilePath, git);
            originalSHA = git.getRepository().resolve(Constants.MASTER).getName();
            assertTrue(Files.exists(path));
            Files.write(path.resolveSibling(path.getFileName() + METADATA), "{".getBytes(UTF_8), StandardOpenOption.TRUNCATE_EXISTING);
            git.add().addFilepattern(".").call();
            git.commit().setMessage("Test commit").call();
            Iterable<PushResult> push = git.push().setCredentialsProvider(provider).call();
            PushResult pushResult = push.iterator().next();
            RemoteRefUpdate remoteUpdate = pushResult.getRemoteUpdate(REFS_HEADS_MASTER);
            assertEquals(Status.REJECTED_OTHER_REASON, remoteUpdate.getStatus());
            assertTrue(remoteUpdate.getMessage().startsWith("Error in branch " + REFS_HEADS_MASTER), () -> "Was '" + remoteUpdate.getMessage() + "'");
        }
        try (Git git = Git.cloneRepository().setDirectory(getFolder().toFile()).setURI(gitAdress).setCredentialsProvider(provider).call()) {
            assertNotNull(readSource(Paths.get(getRepopath(git), STORE)));
            assertEquals(originalSHA, git.getRepository().resolve(Constants.MASTER).getName());
        }
    }

    @Test
    public void testPushToOwnHostedRepositoryWithNewBranch() throws Exception {
        try (Git git = Git.cloneRepository().setDirectory(getFolder().toFile()).setURI(gitAdress).setCredentialsProvider(provider).call()) {
            String localFilePath = STORE;
            Path path = createData(localFilePath, git);

            git.checkout().setCreateBranch(true).setName("newbranch").call();
            Files.write(path, getData(2).getBytes(UTF_8), StandardOpenOption.CREATE);
            git.add().addFilepattern(localFilePath).call();
            git.commit().setMessage("Newer commit").call();
            verifyOkPush(git.push().setCredentialsProvider(provider).call(), REFS_HEADS_NEWBRANCH);

            Map<String, Ref> refs = git.lsRemote().setCredentialsProvider(provider).callAsMap();
            assertNotNull(refs.get(REFS_HEADS_NEWBRANCH));

        }
        try (JitStaticClient client = buildClient()) {
            Entity key = client.getKey(STORE, REFS_HEADS_NEWBRANCH, tf);
            assertEquals(getData(2), key.data.toString());
            key = client.getKey(STORE, REFS_HEADS_MASTER, tf);
            assertEquals(getData(), key.data.toString());
        }
    }

    @Test
    public void testPushToOwnHostedRepositoryWithNewBranchAndThenDelete() throws Exception {
        assertThat(assertThrows(APIException.class, () -> {
            try (Git git = Git.cloneRepository().setDirectory(getFolder().toFile()).setURI(gitAdress).setCredentialsProvider(provider).call();
                    JitStaticClient client = buildClient()) {
                Path path = createData(STORE, git);

                git.checkout().setCreateBranch(true).setName("newbranch").call();
                Files.write(path, getData(1).getBytes(UTF_8), StandardOpenOption.CREATE);
                git.add().addFilepattern(".").call();
                git.commit().setMessage("Newer commit").call();
                verifyOkPush(git.push().setCredentialsProvider(provider).call(), REFS_HEADS_NEWBRANCH);

                Map<String, Ref> refs = git.lsRemote().setCredentialsProvider(provider).callAsMap();
                assertNotNull(refs.get(REFS_HEADS_NEWBRANCH));

                Entity key = client.getKey(STORE, REFS_HEADS_NEWBRANCH, tf);
                assertEquals(getData(1), key.data.toString());

                git.checkout().setName("master").call();
                List<String> call = git.branchDelete().setBranchNames("newbranch").setForce(true).call();
                assertTrue(call.stream().allMatch(b -> b.equals(REFS_HEADS_NEWBRANCH)));

                verifyOkPush(git.push().setCredentialsProvider(provider).setRefSpecs(new RefSpec(":" + REFS_HEADS_NEWBRANCH)).call(), REFS_HEADS_NEWBRANCH);
                assertEquals(HttpStatus.NOT_FOUND_404, assertThrows(APIException.class, () -> client.getKey(STORE, REFS_HEADS_NEWBRANCH, tf)).getStatusCode());
                refs = git.lsRemote().setCredentialsProvider(provider).callAsMap();
                assertNull(refs.get(REFS_HEADS_NEWBRANCH));

                client.getKey(STORE, REFS_HEADS_NEWBRANCH, tf);
            }
        }).getMessage(), CoreMatchers.containsString("/application/storage/store?ref=refs%2Fheads%2Fnewbranch failed with: 404 Not Found"));
    }

    @Test
    public void testRemoveKey() throws Exception {
        assertThat(assertThrows(APIException.class, () -> {
            try (Git git = Git.cloneRepository().setDirectory(getFolder().toFile()).setURI(gitAdress).setCredentialsProvider(provider).call();
                    JitStaticClient client = buildClient()) {
                createData(STORE, git);
                Entity key = client.getKey(STORE, REFS_HEADS_MASTER, tf);
                String version = key.getTag();
                assertNotNull(version);
                assertEquals(getData(), key.data.toString());

                git.rm().addFilepattern(STORE).call();
                git.rm().addFilepattern(STORE + METADATA).call();
                git.commit().setMessage("Removed file").call();
                verifyOkPush(git.push().setCredentialsProvider(provider).call());

                key = client.getKey(STORE, REFS_HEADS_MASTER, tf);
            }
        }).getMessage(), CoreMatchers.containsString("/application/storage/store?ref=refs%2Fheads%2Fmaster failed with: 404 Not Found"));
    }

    @Test
    public void testGetTag() throws Exception {
        String localFilePath = STORE;
        try (Git git = Git.cloneRepository().setDirectory(getFolder().toFile()).setURI(gitAdress).setCredentialsProvider(provider).call()) {
            createData(localFilePath, git);
            git.tag().setName("tag").call();
            verifyOkPush(git.push().setCredentialsProvider(provider).setPushTags().call(), "refs/tags/tag");
        }

        try (JitStaticClient client = buildClient()) {
            Entity key = client.getKey(STORE, "refs/tags/tag", tf);
            assertEquals(getData(), key.data.toString());
        }
    }

    @Test
    public void testModifyKeySeveralTimes() throws Exception {
        try (Git git = Git.cloneRepository().setDirectory(getFolder().toFile()).setURI(gitAdress).setCredentialsProvider(provider).call();
                JitStaticClient client = buildClient()) {
            Path path = createData(STORE, git);
            git.tag().setName("tag").call();
            verifyOkPush(git.push().setCredentialsProvider(provider).setPushTags().call(), "refs/tags/tag");

            Entity key = client.getKey(STORE, REFS_HEADS_MASTER, tf);
            assertEquals(getData(), key.data.toString());

            Files.write(path, getData(1).getBytes(UTF_8), StandardOpenOption.TRUNCATE_EXISTING);
            git.add().addFilepattern(".").call();
            git.commit().setMessage("Inital commit").call();
            verifyOkPush(git.push().setCredentialsProvider(provider).call());

            key = client.getKey(STORE, REFS_HEADS_MASTER, tf);
            assertEquals(getData(1), key.data.toString());

            key = client.getKey(STORE, "refs/tags/tag", tf);

            assertEquals(getData(), key.data.toString());
        }
    }

    @Test
    public void testModifyTwoKeySeparatly() throws Exception {
        try (Git git = Git.cloneRepository().setDirectory(getFolder().toFile()).setURI(gitAdress).setCredentialsProvider(provider).call();
                JitStaticClient client = buildClient()) {
            String other = "other";
            createData(STORE, git);
            git.tag().setName("tag").call();
            verifyOkPush(git.push().setCredentialsProvider(provider).setPushTags().call(), "refs/tags/tag");

            Entity key = client.getKey(STORE, REFS_HEADS_MASTER, tf);

            assertEquals(getData(), key.data.toString());

            Path otherPath = Paths.get(getRepopath(git), other);
            Path otherMpath = Paths.get(getRepopath(git), other + METADATA);

            Files.write(otherPath, getData(2).getBytes(UTF_8), StandardOpenOption.CREATE_NEW);
            Files.write(otherMpath, getMetaData().getBytes(UTF_8), StandardOpenOption.CREATE_NEW);

            git.add().addFilepattern(".").call();
            git.commit().setMessage("Other commit").call();
            verifyOkPush(git.push().setCredentialsProvider(provider).call());
            key = client.getKey(STORE, tf);

            assertEquals(getData(), key.data.toString());

            key = client.getKey(other, tf);
            assertEquals(getData(2), key.data.toString());

            key = client.getKey(STORE, "refs/tags/tag", tf);

            assertEquals(getData(), key.data.toString());
        }
    }

    @Test
    public void testFormattedJson() throws Exception {
        File workingFolder = getFolder().toFile();
        try (Git git = Git.cloneRepository().setDirectory(workingFolder).setURI(gitAdress).setCredentialsProvider(provider).call();
                JitStaticClient client = buildClient()) {
            createData(STORE, git);
            Entity key = client.getKey(STORE, REFS_HEADS_MASTER, tf);

            byte[] bytes = MAPPER.writer().withDefaultPrettyPrinter().writeValueAsString(MAPPER.readTree(getData(2))).getBytes(UTF_8);

            client.modifyKey(bytes, new CommitData(STORE, "master", "Modified", "user", "noone@none.org"), key.getTag());

            git.pull().setCredentialsProvider(provider).call();

            byte[] readAllBytes = Files.readAllBytes(workingFolder.toPath().resolve(STORE));

            assertArrayEquals(bytes, readAllBytes);
        }
    }

    @Test
    public void testReloadedAfterManualPush() throws Exception {
        File workingFolder = getFolder().toFile();
        try (Git git = Git.cloneRepository().setDirectory(workingFolder).setURI(gitAdress).setCredentialsProvider(provider).call();
                JitStaticClient client = buildClient()) {
            createData(STORE, git);
            Entity first = client.getKey(STORE, REFS_HEADS_MASTER, tf);
            Files.write(workingFolder.toPath().resolve(STORE), getData(2).getBytes(UTF_8), StandardOpenOption.TRUNCATE_EXISTING);
            git.add().addFilepattern(".").call();
            git.commit().setMessage("New file data").call();
            verifyOkPush(git.push().setCredentialsProvider(provider).call());
            Entity second = client.getKey(STORE, REFS_HEADS_MASTER, tf);
            assertNotEquals(first.getTag(), second.getTag());
        }
    }

    @Test
    public void testAddingKeyWithMasterMetaData() throws Exception {
        final HostedFactory hf = DW.getConfiguration().getHostedFactory();

        File workingFolder = getFolder().toFile();
        try (Git git = Git.cloneRepository().setDirectory(workingFolder).setURI(gitAdress).setCredentialsProvider(provider).call();
                JitStaticClient updaterClient = buildClient();
                JitStaticClient client = buildCreatorClient().setUser(hf.getUserName()).setPassword(hf.getSecret()).build()) {
            Path mpath = Paths.get(getRepopath(git), METADATA);
            Files.write(mpath, getMetaData().getBytes(UTF_8), StandardOpenOption.CREATE_NEW);
            for (int i = 0; i < 10; i++) {
                Path path = Paths.get(getRepopath(git), STORE + i);
                Files.createDirectories(path.getParent());
                Files.write(path, getData().getBytes(UTF_8), StandardOpenOption.CREATE_NEW);
            }
            git.add().addFilepattern(".").call();
            git.commit().setMessage("Inital commit").call();
            verifyOkPush(git.push().setCredentialsProvider(provider).call());
            for (int i = 0; i < 10; i++) {
                Entity key = updaterClient.getKey(STORE + i, tf);
                assertNotNull(key);
            }

            for (int j = 0; j < 2; j++) {
                for (int i = 10; i < 20; i++) {
                    final int p = i;
                    assertEquals(HttpStatus.NOT_FOUND_404, assertThrows(APIException.class, () -> updaterClient.getKey(STORE + p, tf)).getStatusCode());
                }
            }
            for (int i = 10; i < 20; i++) {
                client.createKey(getData(i).getBytes(UTF_8), new CommitData(STORE + i, "master", "commit message", "user1", "user@mail"),
                        new MetaData(new HashSet<>(), "application/json"));
                assertEquals(getData(i), updaterClient.getKey(STORE + i, tf).data.toString());
            }
            for (int i = 10; i < 20; i++) {
                final int j = i;
                assertEquals(HttpStatus.CONFLICT_409,
                        assertThrows(APIException.class, () -> client.createKey(getData(j).getBytes(UTF_8),
                                new CommitData(STORE + j, "master", "commit message", "user1", "user@mail"), new MetaData(new HashSet<>(), "application/json")))
                                        .getStatusCode());

            }
        }
    }

    @Test
    public void testUserOnlyPullRights() throws Exception {
        Path working = getFolder();
        try (Git git = Git.cloneRepository().setDirectory(working.toFile()).setURI(gitAdress).setCredentialsProvider(provider).call();
                JitStaticClient client = buildClient()) {
            Path users = working.resolve(JitStaticConstants.USERS);
            Path gitRealm = users.resolve(JitStaticConstants.GIT_REALM);
            Path user = gitRealm.resolve("blipp");
            assertTrue(gitRealm.toFile().mkdirs());
            Files.write(user, MAPPER.writeValueAsBytes(new UserData(Set.of(new Role("pull")), "1234")), StandardOpenOption.CREATE);

            git.add().addFilepattern(".").call();
            git.commit().setMessage("msg").call();
            verifyOkPush(git.push().setCredentialsProvider(provider).call());
        }
        try (Git git2 = Git.cloneRepository().setDirectory(getFolder().toFile()).setURI(gitAdress)
                .setCredentialsProvider(new UsernamePasswordCredentialsProvider("blipp", "1234")).call()) {
        }
        assertTrue(assertThrows(TransportException.class, () -> {
            try (Git git2 = Git.cloneRepository().setDirectory(getFolder().toFile()).setURI(gitAdress)
                    .setCredentialsProvider(new UsernamePasswordCredentialsProvider("blipp", "2")).call()) {
            }
        }).getMessage().contains("not authorized"));
        Path workingFolder = getFolder();
        try (Git git2 = Git.cloneRepository().setDirectory(getFolder().toFile()).setURI(gitAdress)
                .setCredentialsProvider(new UsernamePasswordCredentialsProvider("blipp", "1234")).call()) {
            Files.write(workingFolder.resolve(STORE), getData(2).getBytes("UTF-8"), StandardOpenOption.CREATE);
            git2.add().addFilepattern(".").call();
            git2.commit().setMessage("New file data").call();
            verifyOkPush(git2.push().setCredentialsProvider(provider).call());
        }
    }

    @Test
    public void testGetKeyWithValidUser() throws Exception {
        Path working = getFolder();
        try (Git git = Git.cloneRepository().setDirectory(working.toFile()).setURI(gitAdress).setCredentialsProvider(provider).call();
                JitStaticClient client = buildClient()) {
            Path users = working.resolve(JitStaticConstants.USERS);
            Path gitRealm = users.resolve(JitStaticConstants.GIT_REALM);
            Path user = gitRealm.resolve("blipp");
            assertTrue(gitRealm.toFile().mkdirs());
            Files.write(user, MAPPER.writeValueAsBytes(new UserData(Set.of(new Role("pull")), "1234")), StandardOpenOption.CREATE);

            git.add().addFilepattern(".").call();
            git.commit().setMessage("msg").call();
            verifyOkPush(git.push().setCredentialsProvider(provider).call());
        }
        try (Git git2 = Git.cloneRepository().setDirectory(getFolder().toFile()).setURI(gitAdress)
                .setCredentialsProvider(new UsernamePasswordCredentialsProvider("blipp", "1234")).call()) {
        }
        assertEquals(HttpStatus.NOT_FOUND_404, assertThrows(APIException.class, () -> {
            try (JitStaticClient client = buildClient()) {
                client.getKey(JitStaticConstants.USERS + JitStaticConstants.GIT_REALM + "/blipp", tf);
            }
        }).getStatusCode());
        try (Git git2 = Git.cloneRepository().setDirectory(getFolder().toFile()).setURI(gitAdress)
                .setCredentialsProvider(new UsernamePasswordCredentialsProvider("blipp", "1234")).call()) {
        }
    }

    @Test
    public void testGetKeyWithValidUserRole() throws Exception {
        Path working = getFolder();
        try (Git git = Git.cloneRepository().setDirectory(working.toFile()).setURI(gitAdress).setCredentialsProvider(provider).call();
                JitStaticClient client = buildClient();) {
            Path users = working.resolve(JitStaticConstants.USERS);
            Path gitRealm = users.resolve(JitStaticConstants.JITSTATIC_KEYADMIN_REALM);
            Path user = gitRealm.resolve("blipp");
            assertTrue(gitRealm.toFile().mkdirs());
            Files.write(user, MAPPER.writeValueAsBytes(new UserData(Set.of(new Role("create")), "1234")), StandardOpenOption.CREATE);
            git.add().addFilepattern(".").call();
            git.commit().setMessage("msg").call();
            verifyOkPush(git.push().setCredentialsProvider(provider).call());
        }
        assertTrue(assertThrows(TransportException.class, () -> {
            try (Git git2 = Git.cloneRepository().setDirectory(getFolder().toFile()).setURI(gitAdress)
                    .setCredentialsProvider(new UsernamePasswordCredentialsProvider("blipp", "1234")).call()) {
            }
        }).getMessage().contains("not authorized"));
        assertEquals(HttpStatus.NOT_FOUND_404, assertThrows(APIException.class, () -> {
            try (JitStaticClient client = buildClient()) {
                client.getKey(JitStaticConstants.USERS + JitStaticConstants.JITSTATIC_KEYADMIN_REALM + "/blipp", tf);
            }
        }).getStatusCode());
        try (JitStaticClient cclient = buildCreatorClient().setPassword("1234").setUser("blipp").build()) {
            assertNotNull(cclient.createKey(new byte[] { 1 }, new CommitData("string/key", "msg", "user", "mail"),
                    new MetaData(Set.of(new MetaData.User("news", "1234")), "application/json")));
        }
    }

    private JitStaticClientBuilder buildCreatorClient() {
        return JitStaticClient.create().setHost("localhost").setPort(DW.getLocalPort()).setAppContext("/application/");
    }

    private void verifyOkPush(Iterable<PushResult> iterable) {
        verifyOkPush(iterable, REFS_HEADS_MASTER);
    }

    private void verifyOkPush(Iterable<PushResult> iterable, String branch) {
        PushResult pushResult = iterable.iterator().next();
        RemoteRefUpdate remoteUpdate = pushResult.getRemoteUpdate(branch);
        assertEquals(Status.OK, remoteUpdate.getStatus());
    }

    private Path createData(String localFilePath, Git git) throws Exception {
        String repopath = getRepopath(git);
        Path path = Paths.get(repopath, localFilePath);
        Path mpath = Paths.get(repopath, localFilePath + METADATA);
        Files.createDirectories(path.getParent());
        Files.write(path, getData().getBytes(UTF_8), StandardOpenOption.CREATE_NEW);
        Files.write(mpath, getMetaData().getBytes(UTF_8), StandardOpenOption.CREATE_NEW);
        git.add().addFilepattern(".").call();
        git.commit().setMessage("Inital commit").call();
        verifyOkPush(git.push().setCredentialsProvider(provider).call());
        return path;
    }

    private Supplier<String> getFolderString() {
        return () -> {
            try {
                return getFolder().toString();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }

    private Path getFolder() throws IOException {
        return tmpFolder.createTemporaryDirectory().toPath();
    }

    private JsonNode readSource(final Path storage) throws IOException {
        assertTrue(Files.exists(storage));
        try (InputStream bc = Files.newInputStream(storage);) {
            return MAPPER.readValue(bc, JsonNode.class);
        }
    }

    private JitStaticClient buildClient(String altPasswd) throws URISyntaxException {
        return JitStaticClient.create().setHost("localhost").setPort(DW.getLocalPort()).setScheme("http").setUser(USER).setPassword(altPasswd)
                .setAppContext("/application/").build();
    }

    private JitStaticClient buildClient() throws URISyntaxException {
        return buildClient(PASSWORD);
    }

    private static String getRepopath(Git git) {
        return git.getRepository().getDirectory().getParentFile().getAbsolutePath();
    }

    private String getData() {
        return getData(0);
    }

    private String getMetaData() {
        return "{\"users\":[{\"password\":\"" + PASSWORD + "\",\"user\":\"" + USER + "\"}]}";
    }

    private String getData(int i) {
        return "{\"key" + i
                + "\":{\"data\":\"value1\",\"users\":[{\"captain\":\"america\",\"black\":\"widow\"}]},\"key3\":{\"data\":\"value3\",\"users\":[{\"tony\":\"stark\",\"spider\":\"man\"}]}}";
    }

    private TriFunction<InputStream, String, String, Entity> tf = (is, v, t) -> {
        try {
            return new Entity(v, t, MAPPER.readValue(is, JsonNode.class));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    };

    static class Entity {

        final JsonNode data;
        private final String contentType;
        private final String tag;

        public Entity(String tag, String contentType, JsonNode jsonNode) {
            this.tag = tag;
            this.contentType = contentType;
            this.data = jsonNode;
        }

        public String getTag() {
            return tag;
        }

        public String getContentType() {
            return contentType;
        }
    }
}
