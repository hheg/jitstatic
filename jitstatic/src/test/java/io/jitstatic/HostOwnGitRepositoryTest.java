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
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jgit.api.CreateBranchCommand.SetupUpstreamMode;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import com.fasterxml.jackson.databind.JsonNode;

import io.dropwizard.testing.ConfigOverride;
import io.dropwizard.testing.junit5.DropwizardAppExtension;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.jitstatic.UserProvider.UserArgument;
import io.jitstatic.auth.UserData;
import io.jitstatic.client.APIException;
import io.jitstatic.client.CommitData;
import io.jitstatic.client.JitStaticClient;
import io.jitstatic.client.JitStaticClientBuilder;
import io.jitstatic.client.MetaData;
import io.jitstatic.hosted.HostedFactory;
import io.jitstatic.test.BaseTest;
import io.jitstatic.test.TemporaryFolder;
import io.jitstatic.test.TemporaryFolderExtension;
import io.jitstatic.tools.AUtils;

@ExtendWith({ TemporaryFolderExtension.class, DropwizardExtensionsSupport.class })
public class HostOwnGitRepositoryTest extends BaseTest {

    private static final String TEST_USER = "testuser";
    private static final String METADATA = ".metadata";
    private static final String STORE = "store";
    private static final String REFS_HEADS_NEWBRANCH = "refs/heads/newbranch";

    private TemporaryFolder tmpFolder;
    private DropwizardAppExtension<JitstaticConfiguration> DW = new DropwizardAppExtension<>(JitstaticApplication.class, AUtils
            .getDropwizardConfigurationResource(), ConfigOverride.config("hosted.basePath", getFolder()));

    private UsernamePasswordCredentialsProvider provider;

    private String gitAdress;

    @BeforeEach
    public void setupClass() throws IOException {
        final HostedFactory hf = DW.getConfiguration().getHostedFactory();
        provider = new UsernamePasswordCredentialsProvider(hf.getUserName(), hf.getSecret());
        gitAdress = String.format("http://localhost:%d/application/%s/%s", DW.getLocalPort(), hf.getServletName(), hf.getHostedEndpoint());
    }

    @AfterEach
    public void after() {
        AUtils.checkContainerForErrors(DW);
    }

    @Test
    public void testCloneOwnHostedRepository() throws Exception {
        String localFilePath = STORE;
        try (Git git = Git.cloneRepository().setDirectory(getFolderFile()).setCredentialsProvider(provider).setURI(gitAdress).call()) {
            Path path = createData(localFilePath, git);
            assertTrue(Files.exists(path));
        }
        try (Git git = Git.cloneRepository().setDirectory(getFolderFile()).setCredentialsProvider(provider).setURI(gitAdress).call()) {
            Path path = Paths.get(getRepopath(git), localFilePath);
            Path mpath = Paths.get(getRepopath(git), localFilePath + METADATA);
            assertTrue(Files.exists(path));
            assertTrue(Files.exists(mpath));
        }
    }

    @ParameterizedTest
    @ArgumentsSource(UserProvider.class)
    public void testGetFromAnEmptyStorage(UserArgument user) throws Exception {
        assertThat(assertThrows(APIException.class, () -> {
            try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(user.getUserName()).setPassword(user.getPassword()).build()) {
                client.getKey("key1", "master", parse(JsonNode.class));
            }
        }).getMessage(), CoreMatchers.containsString("/application/storage/key1?ref=refs%2Fheads%2Fmaster failed with: 404 Not Found"));
    }

    @ParameterizedTest
    @ArgumentsSource(UserProvider.class)
    public void testPushToOwnHostedRepositoryAndFetchResultFromKeyValuestorage(UserArgument user) throws Exception {
        try (Git git = Git.cloneRepository().setDirectory(getFolderFile()).setURI(gitAdress).setCredentialsProvider(provider).call()) {
            createData(STORE, git);
        }
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(user.getUserName()).setPassword(user.getPassword()).build()) {
            Entity<JsonNode> key = client.getKey(STORE, REFS_HEADS_MASTER, parse(JsonNode.class));
            assertEquals(getData(), key.getData().toString());
        }
    }

    @ParameterizedTest
    @ArgumentsSource(UserProvider.class)
    public void testPushToOwnHostedRepositoryAndFetchResultFromDoubleKeyValuestorage(UserArgument user) throws Exception {
        String localFilePath = "key/key1";
        try (Git git = Git.cloneRepository().setDirectory(getFolderFile()).setURI(gitAdress).setCredentialsProvider(provider).call()) {
            createData(localFilePath, git);
        }
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(user.getUserName()).setPassword(user.getPassword()).build()) {
            Entity<JsonNode> key = client.getKey(localFilePath, REFS_HEADS_MASTER, parse(JsonNode.class));
            assertEquals(getData(), key.getData().toString());
        }
    }

    @Test
    public void testPushToOwnHostedRepositoryWithBrokenJSONStorage() throws Exception {
        String originalSHA = null;
        String localFilePath = STORE;
        try (Git git = Git.cloneRepository().setDirectory(getFolderFile()).setURI(gitAdress).setCredentialsProvider(provider).call()) {
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
        try (Git git = Git.cloneRepository().setDirectory(getFolderFile()).setURI(gitAdress).setCredentialsProvider(provider).call()) {
            assertNotNull(readSource(Paths.get(getRepopath(git), STORE)));
            assertEquals(originalSHA, git.getRepository().resolve(Constants.MASTER).getName());
        }
    }

    @ParameterizedTest
    @ArgumentsSource(UserProvider.class)
    public void testPushToOwnHostedRepositoryWithNewBranch(UserArgument user) throws Exception {
        try (Git git = Git.cloneRepository().setDirectory(getFolderFile()).setURI(gitAdress).setCredentialsProvider(provider).call()) {
            String localFilePath = STORE;
            Path path = createData(localFilePath, git);

            git.checkout().setCreateBranch(true).setName("newbranch").call();
            Files.write(path, getData(2).getBytes(UTF_8), StandardOpenOption.CREATE);
            git.add().addFilepattern(localFilePath).call();
            git.commit().setMessage("Newer commit").call();
            verifyOkPush(git.push().setCredentialsProvider(provider).call());

            Map<String, Ref> refs = git.lsRemote().setCredentialsProvider(provider).callAsMap();
            assertNotNull(refs.get(REFS_HEADS_NEWBRANCH));

        }
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(user.getUserName()).setPassword(user.getPassword()).build()) {
            Entity<JsonNode> key = client.getKey(STORE, REFS_HEADS_NEWBRANCH, parse(JsonNode.class));
            assertEquals(getData(2), key.getData().toString());
            key = client.getKey(STORE, REFS_HEADS_MASTER, parse(JsonNode.class));
            assertEquals(getData(), key.getData().toString());
        }
    }

    @ParameterizedTest
    @ArgumentsSource(UserProvider.class)
    public void testPushToOwnHostedRepositoryWithNewBranchAndThenDelete(UserArgument user) throws Exception {
        try (Git git = Git.cloneRepository().setDirectory(getFolderFile()).setURI(gitAdress).setCredentialsProvider(provider).call();
                JitStaticClient client = buildClient(DW.getLocalPort()).setUser(user.getUserName()).setPassword(user.getPassword()).build()) {
            Path path = createData(STORE, git);

            git.checkout().setCreateBranch(true).setName("newbranch").call();
            Files.write(path, getData(1).getBytes(UTF_8), StandardOpenOption.CREATE);
            git.add().addFilepattern(".").call();
            git.commit().setMessage("Newer commit").call();
            verifyOkPush(git.push().setCredentialsProvider(provider).call());

            Map<String, Ref> refs = git.lsRemote().setCredentialsProvider(provider).callAsMap();
            assertNotNull(refs.get(REFS_HEADS_NEWBRANCH));

            Entity<JsonNode> key = client.getKey(STORE, REFS_HEADS_NEWBRANCH, parse(JsonNode.class));
            assertEquals(getData(1), key.getData().toString());

            git.checkout().setName("master").call();
            List<String> call = git.branchDelete().setBranchNames("newbranch").setForce(true).call();
            assertTrue(call.stream().allMatch(b -> b.equals(REFS_HEADS_NEWBRANCH)));

            verifyOkPush(git.push().setForce(true).setCredentialsProvider(provider).setRefSpecs(new RefSpec(":" + REFS_HEADS_NEWBRANCH))
                    .call());
            assertEquals(HttpStatus.NOT_FOUND_404, assertThrows(APIException.class, () -> client.getKey(STORE, REFS_HEADS_NEWBRANCH, parse(JsonNode.class)))
                    .getStatusCode());
            refs = git.lsRemote().setCredentialsProvider(provider).callAsMap();
            assertNull(refs.get(REFS_HEADS_NEWBRANCH));
            assertThat(assertThrows(APIException.class, () -> {
                client.getKey(STORE, REFS_HEADS_NEWBRANCH, parse(JsonNode.class));
            }).getMessage(), CoreMatchers.containsString("/application/storage/store?ref=refs%2Fheads%2Fnewbranch failed with: 404 Not Found"));
        }
    }

    @ParameterizedTest
    @ArgumentsSource(UserProvider.class)
    public void testRemoveKey(UserArgument user) throws Exception {
        try (Git git = Git.cloneRepository().setDirectory(getFolderFile()).setURI(gitAdress).setCredentialsProvider(provider).call();
                JitStaticClient client = buildClient(DW.getLocalPort()).setUser(user.getUserName()).setPassword(user.getPassword()).build()) {
            createData(STORE, git);
            Entity<JsonNode> key = client.getKey(STORE, REFS_HEADS_MASTER, parse(JsonNode.class));
            String version = key.getTag();
            assertNotNull(version);
            assertEquals(getData(), key.getData().toString());

            git.rm().addFilepattern(STORE).call();
            git.rm().addFilepattern(STORE + METADATA).call();
            git.commit().setMessage("Removed file").call();
            verifyOkPush(git.push().setCredentialsProvider(provider).call());
            assertThat(assertThrows(APIException.class, () -> client.getKey(STORE, REFS_HEADS_MASTER, parse(JsonNode.class))).getMessage(), CoreMatchers.containsString("/application/storage/store?ref=refs%2Fheads%2Fmaster failed with: 404 Not Found"));
        }
    }

    @ParameterizedTest
    @ArgumentsSource(UserProvider.class)
    public void testGetTag(UserArgument user) throws Exception {
        String localFilePath = STORE;
        try (Git git = Git.cloneRepository().setDirectory(getFolderFile()).setURI(gitAdress).setCredentialsProvider(provider).call()) {
            createData(localFilePath, git);
            git.tag().setName("tag").call();
            verifyOkPush(git.push().setCredentialsProvider(provider).setPushTags().call());
        }

        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(user.getUserName()).setPassword(user.getPassword()).build()) {
            Entity<JsonNode> key = client.getKey(STORE, "refs/tags/tag", parse(JsonNode.class));
            assertEquals(getData(), key.getData().toString());
        }
    }

    @ParameterizedTest
    @ArgumentsSource(UserProvider.class)
    public void testModifyKeySeveralTimes(UserArgument user) throws Exception {
        try (Git git = Git.cloneRepository().setDirectory(getFolderFile()).setURI(gitAdress).setCredentialsProvider(provider).call();
                JitStaticClient client = buildClient(DW.getLocalPort()).setUser(user.getUserName()).setPassword(user.getPassword()).build()) {
            Path path = createData(STORE, git);
            git.tag().setName("tag").call();
            verifyOkPush(git.push().setCredentialsProvider(provider).setPushTags().call());

            Entity<JsonNode> key = client.getKey(STORE, REFS_HEADS_MASTER, parse(JsonNode.class));
            assertEquals(getData(), key.getData().toString());

            Files.write(path, getData(1).getBytes(UTF_8), StandardOpenOption.TRUNCATE_EXISTING);
            git.add().addFilepattern(".").call();
            git.commit().setMessage("Inital commit").call();
            verifyOkPush(git.push().setCredentialsProvider(provider).call());

            key = client.getKey(STORE, REFS_HEADS_MASTER, parse(JsonNode.class));
            assertEquals(getData(1), key.getData().toString());

            key = client.getKey(STORE, "refs/tags/tag", parse(JsonNode.class));

            assertEquals(getData(), key.getData().toString());
        }
    }

    @ParameterizedTest
    @ArgumentsSource(UserProvider.class)
    public void testModifyTwoKeySeparatly(UserArgument user) throws Exception {
        try (Git git = Git.cloneRepository().setDirectory(getFolderFile()).setURI(gitAdress).setCredentialsProvider(provider).call();
                JitStaticClient client = buildClient(DW.getLocalPort()).setUser(user.getUserName()).setPassword(user.getPassword()).build()) {
            String other = "other";
            createData(STORE, git);
            git.tag().setName("tag").call();
            verifyOkPush(git.push().setCredentialsProvider(provider).setPushTags().call());

            Entity<JsonNode> key = client.getKey(STORE, REFS_HEADS_MASTER, parse(JsonNode.class));

            assertEquals(getData(), key.getData().toString());

            Path otherPath = Paths.get(getRepopath(git), other);
            Path otherMpath = Paths.get(getRepopath(git), other + METADATA);

            Files.write(otherPath, getData(2).getBytes(UTF_8), StandardOpenOption.CREATE_NEW);
            Files.write(otherMpath, getMetaData().getBytes(UTF_8), StandardOpenOption.CREATE_NEW);

            git.add().addFilepattern(".").call();
            git.commit().setMessage("Other commit").call();
            verifyOkPush(git.push().setCredentialsProvider(provider).call());
            key = client.getKey(STORE, parse(JsonNode.class));

            assertEquals(getData(), key.getData().toString());

            key = client.getKey(other, parse(JsonNode.class));
            assertEquals(getData(2), key.getData().toString());

            key = client.getKey(STORE, "refs/tags/tag", parse(JsonNode.class));

            assertEquals(getData(), key.getData().toString());
        }
    }

    @ParameterizedTest
    @ArgumentsSource(UserProvider.class)
    public void testFormattedJson(UserArgument user) throws Exception {
        File workingFolder = getFolderFile();
        try (Git git = Git.cloneRepository().setDirectory(workingFolder).setURI(gitAdress).setCredentialsProvider(provider).call();
                JitStaticClient client = buildClient(DW.getLocalPort()).setUser(user.getUserName()).setPassword(user.getPassword()).build()) {
            createData(STORE, git);
            Entity<JsonNode> key = client.getKey(STORE, REFS_HEADS_MASTER, parse(JsonNode.class));

            byte[] bytes = MAPPER.writer().withDefaultPrettyPrinter().writeValueAsString(MAPPER.readTree(getData(2))).getBytes(UTF_8);

            client.modifyKey(bytes, new CommitData(STORE, "master", "Modified", "user", "noone@none.org"), key.getTag());

            git.pull().setCredentialsProvider(provider).call();

            byte[] readAllBytes = Files.readAllBytes(workingFolder.toPath().resolve(STORE));

            assertArrayEquals(bytes, readAllBytes);
        }
    }

    @ParameterizedTest
    @ArgumentsSource(UserProvider.class)
    public void testReloadedAfterManualPush(UserArgument user) throws Exception {
        File workingFolder = getFolderFile();
        try (Git git = Git.cloneRepository().setDirectory(workingFolder).setURI(gitAdress).setCredentialsProvider(provider).call();
                JitStaticClient client = buildClient(DW.getLocalPort()).setUser(user.getUserName()).setPassword(user.getPassword()).build()) {
            createData(STORE, git);
            Entity<JsonNode> first = client.getKey(STORE, REFS_HEADS_MASTER, parse(JsonNode.class));
            Files.write(workingFolder.toPath().resolve(STORE), getData(2).getBytes(UTF_8), StandardOpenOption.TRUNCATE_EXISTING);
            git.add().addFilepattern(".").call();
            git.commit().setMessage("New file data").call();
            verifyOkPush(git.push().setCredentialsProvider(provider).call());
            Entity<JsonNode> second = client.getKey(STORE, REFS_HEADS_MASTER, parse(JsonNode.class));
            assertNotEquals(first.getTag(), second.getTag());
        }
    }

    @ParameterizedTest
    @ArgumentsSource(UserProvider.class)
    public void testAddingKeyWithMasterMetaData(UserArgument user) throws Exception {
        final HostedFactory hf = DW.getConfiguration().getHostedFactory();
        File workingFolder = getFolderFile();
        try (Git git = Git.cloneRepository().setDirectory(workingFolder).setURI(gitAdress).setCredentialsProvider(provider).call();
                JitStaticClient updaterClient = buildClient(DW.getLocalPort()).setUser(user.getUserName()).setPassword(user.getPassword()).build();
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
                Entity<JsonNode> key = updaterClient.getKey(STORE + i, parse(JsonNode.class));
                assertNotNull(key);
            }

            for (int j = 0; j < 2; j++) {
                for (int i = 10; i < 20; i++) {
                    final int p = i;
                    assertEquals(HttpStatus.NOT_FOUND_404, assertThrows(APIException.class, () -> updaterClient.getKey(STORE + p, parse(JsonNode.class)))
                            .getStatusCode());
                }
            }
            for (int i = 10; i < 20; i++) {
                client.createKey(getData(i).getBytes(UTF_8), new CommitData(STORE
                        + i, "master", "commit message", "user1", "user@mail"), new MetaData(new HashSet<>(), "application/json"));
                assertEquals(getData(i), updaterClient.getKey(STORE + i, parse(JsonNode.class)).getData().toString());
            }
            for (int i = 10; i < 20; i++) {
                final int j = i;
                assertEquals(HttpStatus.CONFLICT_409, assertThrows(APIException.class, () -> client.createKey(getData(j).getBytes(UTF_8), new CommitData(STORE
                        + j, "master", "commit message", "user1", "user@mail"), new MetaData(new HashSet<>(), "application/json")))
                                .getStatusCode());
            }
        }
    }

    @ParameterizedTest
    @ArgumentsSource(UserProvider.class)
    public void testUserOnlyPullRights(UserArgument userarg) throws Exception {
        Path working = getFolderFile().toPath();
        try (Git git = Git.cloneRepository().setDirectory(working.toFile()).setURI(gitAdress).setCredentialsProvider(provider).call();
                JitStaticClient client = buildClient(DW.getLocalPort()).setUser(userarg.getUserName()).setPassword(userarg.getPassword()).build()) {
            git.commit().setMessage("initial commit").call();
            verifyOkPush(git.push().setCredentialsProvider(provider).call());
            Path users = working.resolve(JitStaticConstants.USERS);
            Path gitRealm = users.resolve(JitStaticConstants.GIT_REALM);
            Path user = gitRealm.resolve(TEST_USER);
            assertNotNull(git.checkout().setCreateBranch(true).setUpstreamMode(SetupUpstreamMode.TRACK).setName(JitStaticConstants.SECRETS).call());
            assertTrue(gitRealm.toFile().mkdirs());
            Files.write(user, MAPPER.writeValueAsBytes(new UserData(Set.of(new Role("pull")), "1234", null, null)), StandardOpenOption.CREATE);

            git.add().addFilepattern(".").call();
            git.commit().setMessage("msg").call();
            verifyOkPush(git.push().setCredentialsProvider(provider).call());
        }
        try (Git git2 = Git.cloneRepository().setDirectory(getFolderFile()).setURI(gitAdress)
                .setCredentialsProvider(new UsernamePasswordCredentialsProvider(TEST_USER, "1234")).call()) {
        }
        assertTrue(assertThrows(TransportException.class, () -> {
            try (Git git2 = Git.cloneRepository().setDirectory(getFolderFile()).setURI(gitAdress)
                    .setCredentialsProvider(new UsernamePasswordCredentialsProvider(TEST_USER, "2")).call()) {
            }
        }).getMessage().contains("not authorized"));
        Path workingFolder = getFolderFile().toPath();
        try (Git git2 = Git.cloneRepository().setDirectory(getFolderFile()).setURI(gitAdress)
                .setCredentialsProvider(new UsernamePasswordCredentialsProvider(TEST_USER, "1234")).call()) {
            Files.write(workingFolder.resolve(STORE), getData(2).getBytes("UTF-8"), StandardOpenOption.CREATE);
            git2.add().addFilepattern(".").call();
            git2.commit().setMessage("New file data").call();
            verifyOkPush(git2.push().setCredentialsProvider(provider).call());
        }
    }

    @ParameterizedTest
    @ArgumentsSource(UserProvider.class)
    public void testGetKeyWithValidUser(UserArgument userarg) throws Exception {
        Path working = getFolderFile().toPath();
        try (Git git = Git.cloneRepository().setDirectory(working.toFile()).setURI(gitAdress).setCredentialsProvider(provider).call();
                JitStaticClient client = buildClient(DW.getLocalPort()).setUser(userarg.getUserName()).setPassword(userarg.getPassword()).build()) {
            git.commit().setMessage("initial commit").call();
            verifyOkPush(git.push().setCredentialsProvider(provider).call());
            Path users = working.resolve(JitStaticConstants.USERS);
            Path gitRealm = users.resolve(JitStaticConstants.GIT_REALM);
            Path user = gitRealm.resolve(TEST_USER);
            assertTrue(gitRealm.toFile().mkdirs());
            Files.write(user, MAPPER.writeValueAsBytes(new UserData(Set.of(new Role("pull")), "1234", null, null)), StandardOpenOption.CREATE);
            assertNotNull(git.checkout().setCreateBranch(true).setUpstreamMode(SetupUpstreamMode.TRACK).setName(JitStaticConstants.SECRETS).call());
            git.add().addFilepattern(".").call();
            git.commit().setMessage("msg").call();
            verifyOkPush(git.push().setCredentialsProvider(provider).call());
        }
        try (Git git2 = Git.cloneRepository().setDirectory(getFolderFile()).setURI(gitAdress)
                .setCredentialsProvider(new UsernamePasswordCredentialsProvider(TEST_USER, "1234")).call()) {
        }
        assertEquals(HttpStatus.NOT_FOUND_404, assertThrows(APIException.class, () -> {
            try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(userarg.getUserName()).setPassword(userarg.getPassword()).build()) {
                client.getKey(JitStaticConstants.USERS + JitStaticConstants.GIT_REALM + "/blipp", parse(JsonNode.class));
            }
        }).getStatusCode());
        try (Git git2 = Git.cloneRepository().setDirectory(getFolderFile()).setURI(gitAdress)
                .setCredentialsProvider(new UsernamePasswordCredentialsProvider(TEST_USER, "1234")).call()) {
        }
    }

    @ParameterizedTest
    @ArgumentsSource(UserProvider.class)
    public void testGetKeyWithValidUserRole(UserArgument userarg) throws Exception {
        Path working = getFolderFile().toPath();
        try (Git git = Git.cloneRepository().setDirectory(working.toFile()).setURI(gitAdress).setCredentialsProvider(provider).call();
                JitStaticClient client = buildClient(DW.getLocalPort()).setUser(userarg.getUserName()).setPassword(userarg.getPassword()).build();) {
            Path users = working.resolve(JitStaticConstants.USERS);
            Path gitRealm = users.resolve(JitStaticConstants.JITSTATIC_KEYUSER_REALM);
            Path user = gitRealm.resolve(TEST_USER);
            assertTrue(gitRealm.toFile().mkdirs());
            Files.write(user, MAPPER.writeValueAsBytes(new UserData(Set.of(new Role("create")), "1234", null, null)), StandardOpenOption.CREATE);
            git.add().addFilepattern(".").call();
            git.commit().setMessage("msg").call();
            verifyOkPush(git.push().setCredentialsProvider(provider).call());
        }
        assertTrue(assertThrows(TransportException.class, () -> {
            try (Git git2 = Git.cloneRepository().setDirectory(getFolderFile()).setURI(gitAdress)
                    .setCredentialsProvider(new UsernamePasswordCredentialsProvider(TEST_USER, "1234")).call()) {
            }
        }).getMessage().contains("not authorized"));
        assertEquals(HttpStatus.NOT_FOUND_404, assertThrows(APIException.class, () -> {
            try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(userarg.getUserName()).setPassword(userarg.getPassword()).build()) {
                client.getKey(JitStaticConstants.USERS + JitStaticConstants.JITSTATIC_KEYUSER_REALM + "/blipp", parse(JsonNode.class));
            }
        }).getStatusCode());
        try (JitStaticClient cclient = buildCreatorClient().setPassword("1234").setUser(TEST_USER).build()) {
            assertNotNull(cclient.createKey(new byte[] { 1 }, new CommitData("string/key", "msg", "user", "mail"), new MetaData(Set
                    .of(new MetaData.User("news", "1234")), "application/json")));
        }
    }

    @ParameterizedTest
    @ArgumentsSource(UserProvider.class)
    public void testPhantomKeys(UserArgument userarg) throws Exception {
        String localFilePath = STORE;
        Path working = getFolderFile().toPath();
        try (Git git = Git.cloneRepository().setDirectory(working.toFile()).setURI(gitAdress).setCredentialsProvider(provider).call();
                JitStaticClient client = buildClient(DW.getLocalPort()).setUser(userarg.getUserName()).setPassword(userarg.getPassword()).build();) {
            String repopath = getRepopath(git);
            Path path = Paths.get(repopath, localFilePath);
            Path mpath = Paths.get(repopath, METADATA);
            Files.createDirectories(path.getParent());
            Files.write(path, getData().getBytes(UTF_8), StandardOpenOption.CREATE_NEW);
            Files.write(mpath, getMetaData().getBytes(UTF_8), StandardOpenOption.CREATE_NEW);
            git.add().addFilepattern(".").call();
            git.commit().setMessage("Inital commit").call();
            verifyOkPush(git.push().setCredentialsProvider(provider).call());
            Entity<JsonNode> key = client.getKey(STORE, parse(JsonNode.class));
            assertNotNull(key.getData());
            git.rm().addFilepattern(STORE).call();
            git.commit().setMessage("removed file").call();
            verifyOkPush(git.push().setCredentialsProvider(provider).call());
            assertEquals(HttpStatus.NOT_FOUND_404, assertThrows(APIException.class, () -> client.getKey(STORE, parse(JsonNode.class))).getStatusCode());
        }
    }

    @ParameterizedTest
    @ArgumentsSource(UserProvider.class)
    public void testDeleteFileButMetadataIsStillLeft(UserArgument user) throws Exception {
        String localFilePath = STORE;
        Path working = getFolderFile().toPath();
        try (Git git = Git.cloneRepository().setDirectory(working.toFile()).setURI(gitAdress).setCredentialsProvider(provider).call();
                JitStaticClient client = buildClient(DW.getLocalPort()).setUser(user.getUserName()).setPassword(user.getPassword()).build();) {
            assertNotNull(createData(localFilePath, git));
            Entity<JsonNode> key = client.getKey(STORE, parse(JsonNode.class));
            assertNotNull(key.getData());
            git.rm().addFilepattern(STORE).call();
            git.commit().setMessage("removed file").call();
            Iterable<PushResult> iterable = git.push().setCredentialsProvider(provider).call();
            PushResult pushResult = iterable.iterator().next();
            RemoteRefUpdate remoteUpdate = pushResult.getRemoteUpdate(REFS_HEADS_MASTER);
            assertEquals(Status.REJECTED_OTHER_REASON, remoteUpdate.getStatus());
        }
    }

    private JitStaticClientBuilder buildCreatorClient() {
        return JitStaticClient.create().setHost("localhost").setPort(DW.getLocalPort()).setAppContext("/application/");
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

    protected File getFolderFile() throws IOException { return tmpFolder.createTemporaryDirectory(); }

    private JsonNode readSource(final Path storage) throws IOException {
        assertTrue(Files.exists(storage));
        try (InputStream bc = Files.newInputStream(storage);) {
            return MAPPER.readValue(bc, JsonNode.class);
        }
    }

    private static String getRepopath(Git git) {
        return git.getRepository().getDirectory().getParentFile().getAbsolutePath();
    }
}
