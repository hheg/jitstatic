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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.io.UnsupportedEncodingException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.stream.Collectors;

import org.eclipse.jetty.http.HttpStatus;
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
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.RemoteRefUpdate.Status;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.hamcrest.CoreMatchers;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.codahale.metrics.health.HealthCheck.Result;
import com.fasterxml.jackson.core.JsonParser.Feature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.dropwizard.testing.ConfigOverride;
import io.dropwizard.testing.ResourceHelpers;
import io.dropwizard.testing.junit5.DropwizardAppExtension;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.jitstatic.client.APIException;
import io.jitstatic.client.CommitData;
import io.jitstatic.client.JitStaticCreatorClient;
import io.jitstatic.client.JitStaticCreatorClientBuilder;
import io.jitstatic.client.JitStaticUpdaterClient;
import io.jitstatic.client.MetaData;
import io.jitstatic.client.TriFunction;
import io.jitstatic.hosted.HostedFactory;

@ExtendWith(DropwizardExtensionsSupport.class)
public class HostOwnGitRepositoryTest {

    private static final String UTF_8 = "UTF-8";
    private static final String METADATA = ".metadata";
    private static final String STORE = "store";
    private static final String REFS_HEADS_NEWBRANCH = "refs/heads/newbranch";
    private static final String REFS_HEADS_MASTER = "refs/heads/master";
    private static final String USER = "suser";
    private static final String PASSWORD = "ssecret";

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(Feature.ALLOW_COMMENTS);

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
        SortedMap<String, Result> healthChecks = DW.getEnvironment().healthChecks().runHealthChecks();
        List<Throwable> errors = healthChecks.entrySet().stream().map(e -> e.getValue().getError()).filter(Objects::nonNull)
                .collect(Collectors.toList());
        errors.stream().forEach(e -> e.printStackTrace());
        assertThat(errors.toString(), errors.isEmpty(), Matchers.is(true));
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
            try (JitStaticUpdaterClient client = buildClient()) {
                client.getKey("key1", "master", tf);
            }
        }).getMessage(), CoreMatchers.containsString("/application/storage/key1?ref=refs%2Fheads%2Fmaster failed with: 404 Not Found"));
    }

    @Test
    public void testPushToOwnHostedRepositoryAndFetchResultFromKeyValuestorage() throws Exception {
        try (Git git = Git.cloneRepository().setDirectory(getFolder().toFile()).setURI(gitAdress).setCredentialsProvider(provider).call()) {
            createData(STORE, git);
        }
        try (JitStaticUpdaterClient client = buildClient()) {
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
        try (JitStaticUpdaterClient client = buildClient()) {
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
            assertTrue(remoteUpdate.getMessage().startsWith("Error in branch " + REFS_HEADS_MASTER),
                    () -> "Was '" + remoteUpdate.getMessage() + "'");

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
        try (JitStaticUpdaterClient client = buildClient()) {
            Entity key = client.getKey(STORE, REFS_HEADS_NEWBRANCH, tf);
            assertEquals(getData(2), key.data.toString());
            key = client.getKey(STORE, REFS_HEADS_MASTER, tf);
            assertEquals(getData(), key.data.toString());
        }
    }

    @Test
    public void testPushToOwnHostedRepositoryWithNewBranchAndThenDelete() throws Exception {
        assertThat(assertThrows(APIException.class, () -> {
            try (Git git = Git.cloneRepository().setDirectory(getFolder().toFile()).setURI(gitAdress).setCredentialsProvider(provider)
                    .call(); JitStaticUpdaterClient client = buildClient()) {
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

                verifyOkPush(git.push().setCredentialsProvider(provider).setRefSpecs(new RefSpec(":" + REFS_HEADS_NEWBRANCH)).call(),
                        REFS_HEADS_NEWBRANCH);
                refs = git.lsRemote().setCredentialsProvider(provider).callAsMap();
                assertEquals(ObjectId.zeroId(), refs.get(REFS_HEADS_NEWBRANCH).getObjectId());

                client.getKey(STORE, REFS_HEADS_NEWBRANCH, tf);
            }
        }).getMessage(), CoreMatchers.containsString("/application/storage/store?ref=refs%2Fheads%2Fnewbranch failed with: 404 Not Found"));
    }

    @Test
    public void testRemoveKey() throws Exception {
        assertThat(assertThrows(APIException.class, () -> {
            try (Git git = Git.cloneRepository().setDirectory(getFolder().toFile()).setURI(gitAdress).setCredentialsProvider(provider)
                    .call(); JitStaticUpdaterClient client = buildClient()) {
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

        try (JitStaticUpdaterClient client = buildClient()) {
            Entity key = client.getKey(STORE, "refs/tags/tag", tf);
            assertEquals(getData(), key.data.toString());
        }
    }

    @Test
    public void testModifyKeySeveralTimes() throws Exception {
        try (Git git = Git.cloneRepository().setDirectory(getFolder().toFile()).setURI(gitAdress).setCredentialsProvider(provider).call();
                JitStaticUpdaterClient client = buildClient()) {
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
                JitStaticUpdaterClient client = buildClient()) {
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
                JitStaticUpdaterClient client = buildClient()) {
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
    public void testReloadedAfterManualPush()
            throws IOException, InvalidRemoteException, TransportException, GitAPIException, URISyntaxException {
        File workingFolder = getFolder().toFile();
        try (Git git = Git.cloneRepository().setDirectory(workingFolder).setURI(gitAdress).setCredentialsProvider(provider).call();
                JitStaticUpdaterClient client = buildClient()) {
            createData(STORE, git);
            Entity first = client.getKey(STORE, REFS_HEADS_MASTER, tf);
            Files.write(workingFolder.toPath().resolve(STORE), getData(2).getBytes("UTF-8"), StandardOpenOption.TRUNCATE_EXISTING);
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
                JitStaticUpdaterClient updaterClient = buildClient();
                JitStaticCreatorClient client = buildCreatorClient().setUser(hf.getUserName()).setPassword(hf.getSecret()).build()) {
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
                    assertEquals(HttpStatus.NOT_FOUND_404,
                            assertThrows(APIException.class, () -> updaterClient.getKey(STORE + p, tf)).getStatusCode());
                }
            }
            for (int i = 10; i < 20; i++) {
                Entity createKey = client.createKey(getData(i).getBytes(UTF_8),
                        new CommitData(STORE + i, "master", "commit message", "user1", "user@mail"),
                        new MetaData(new HashSet<>(), "application/json"), tf);
                assertEquals(getData(i), createKey.data.toString());
            }
            for (int i = 10; i < 20; i++) {
                final int j = i;
                assertEquals(HttpStatus.CONFLICT_409,
                        assertThrows(APIException.class,
                                () -> client.createKey(getData(j).getBytes(UTF_8),
                                        new CommitData(STORE + j, "master", "commit message", "user1", "user@mail"),
                                        new MetaData(new HashSet<>(), "application/json"), tf)).getStatusCode());

            }
        }
    }

    private JitStaticCreatorClientBuilder buildCreatorClient() {
        return JitStaticCreatorClient.create().setHost("localhost").setPort(DW.getLocalPort()).setAppContext("/application/");
    }

    private void verifyOkPush(Iterable<PushResult> iterable) {
        verifyOkPush(iterable, REFS_HEADS_MASTER);
    }

    private void verifyOkPush(Iterable<PushResult> iterable, String branch) {
        PushResult pushResult = iterable.iterator().next();
        RemoteRefUpdate remoteUpdate = pushResult.getRemoteUpdate(branch);
        assertEquals(Status.OK, remoteUpdate.getStatus());
    }

    private Path createData(String localFilePath, Git git) throws IOException, UnsupportedEncodingException, GitAPIException,
            NoFilepatternException, NoHeadException, NoMessageException, UnmergedPathsException, ConcurrentRefUpdateException,
            WrongRepositoryStateException, AbortedByHookException, InvalidRemoteException, TransportException {
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

    private static String getFolderString() {
        try {
            return getFolder().toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path getFolder() throws IOException {
        return Files.createTempDirectory("junit");
    }

    private JsonNode readSource(final Path storage) throws IOException {
        assertTrue(Files.exists(storage));
        try (InputStream bc = Files.newInputStream(storage);) {
            return MAPPER.readValue(bc, JsonNode.class);
        }
    }

    private JitStaticUpdaterClient buildClient() throws URISyntaxException {
        return JitStaticUpdaterClient.create().setHost("localhost").setPort(DW.getLocalPort()).setScheme("http").setUser(USER)
                .setPassword(PASSWORD).setAppContext("/application/").build();
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
