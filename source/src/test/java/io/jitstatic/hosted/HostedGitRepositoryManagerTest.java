package io.jitstatic.hosted;

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
import static io.jitstatic.JitStaticConstants.JITSTATIC_KEYADMIN_REALM;
import static io.jitstatic.JitStaticConstants.JITSTATIC_KEYUSER_REALM;
import static io.jitstatic.JitStaticConstants.JITSTATIC_NOWHERE;
import static io.jitstatic.JitStaticConstants.USERS;
import static io.jitstatic.source.ObjectStreamProvider.toProvider;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.commons.io.IOUtils;
import org.awaitility.Awaitility;
import org.awaitility.Durations;
import org.eclipse.jgit.api.CreateBranchCommand.SetupUpstreamMode;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.NoFilepatternException;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;
import org.eclipse.jgit.transport.resolver.ServiceNotEnabledException;
import org.hamcrest.CoreMatchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonParser.Feature;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.jitstatic.CommitMetaData;
import io.jitstatic.MetaData;
import io.jitstatic.Role;
import io.jitstatic.auth.UserData;
import io.jitstatic.check.CorruptedSourceException;
import io.jitstatic.check.RepositoryIsMissingIntendedBranch;
import io.jitstatic.hosted.events.AddRefEventListener;
import io.jitstatic.hosted.events.ReloadRefEventListener;
import io.jitstatic.source.SourceInfo;
import io.jitstatic.test.BaseTest;
import io.jitstatic.test.TemporaryFolder;
import io.jitstatic.test.TemporaryFolderExtension;
import io.jitstatic.utils.Pair;

@ExtendWith(TemporaryFolderExtension.class)
public class HostedGitRepositoryManagerTest extends BaseTest {

    private static final Logger LOG = LoggerFactory.getLogger(HostedGitRepositoryManagerTest.class);
    private static final String METADATA = ".metadata";
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(Feature.ALLOW_COMMENTS).enable(Feature.STRICT_DUPLICATE_DETECTION);
    private static final String ENDPOINT = "endpoint";
    private static final String REF_HEADS_MASTER = Constants.R_HEADS + Constants.MASTER;
    private static final String STORE = "store";
    private TemporaryFolder tmpFolder;
    private Path tempFile;
    private Path tempDir;

    private ExecutorService service;

    @BeforeEach
    public void setup() throws IOException {
        tempDir = getFolderFile().toPath();
        tempFile = tmpFolder.createTemporaryFile();
        service = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    public void tearDown() throws InterruptedException {
        service.shutdown();
        service.awaitTermination(10, TimeUnit.SECONDS);
    }

    protected File getFolderFile() throws IOException { return tmpFolder.createTemporaryDirectory(); }

    @Test
    public void testCreatedBareDirectory() throws CorruptedSourceException, IOException {
        try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER, service);) {
            assertTrue(Files.exists(Paths.get(grm.repositoryURI()).resolve(Constants.HEAD)));
        }
    }

    @Test()
    public void testForDirectory() throws CorruptedSourceException, IOException {
        assertThat(assertThrows(IllegalArgumentException.class, () -> {
            try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempFile, ENDPOINT, REF_HEADS_MASTER, service);) {
            }
        }).getLocalizedMessage(), CoreMatchers.containsString(String.format("Path %s is a file", tempFile)));
    }

    @Test()
    public void testForWritableDirectory() throws IOException, CorruptedSourceException {
        assertThat(assertThrows(IllegalArgumentException.class, () -> {
            Set<PosixFilePermission> perms = PosixFilePermissions.fromString("r-xr-x---");
            Files.setPosixFilePermissions(tempDir, perms);
            try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER, service);) {
            }
        }).getLocalizedMessage(), CoreMatchers.containsString(String.format("Path %s is not writeable", tempDir)));
    }

    @Test
    public void testForNullEndPoint() throws CorruptedSourceException, IOException {
        assertThrows(NullPointerException.class, () -> {
            try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, null, REF_HEADS_MASTER, service);) {
            }
        });
    }

    @Test
    public void testForEmptyEndPoint() throws CorruptedSourceException, IOException {
        assertThat(assertThrows(IllegalArgumentException.class, () -> {
            try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, "", REF_HEADS_MASTER, service);) {
            }
        }).getLocalizedMessage(), CoreMatchers.containsString("Parameter endPointName cannot be empty"));
    }

    @Test
    public void testForEmptyDefaultBranch() throws CorruptedSourceException, IOException {
        assertThat(assertThrows(IllegalArgumentException.class, () -> {
            try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, "endpoint", "", service);) {
            }
        }).getLocalizedMessage(), CoreMatchers.containsString("defaultBranch cannot be empty"));
    }

    @Test
    public void testForNullDefaultBranch() throws CorruptedSourceException, IOException {
        assertThat(assertThrows(NullPointerException.class, () -> {
            try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, "endpoint", null, service);) {
            }
        }).getLocalizedMessage(), CoreMatchers.containsString("defaultBranch cannot be null"));
    }

    @Test
    public void testGetRepositoryResolver() throws ServiceNotAuthorizedException, ServiceNotEnabledException, CorruptedSourceException, IOException {
        try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER, service);) {
            Repository open = grm.getRepositoryResolver().open(null, ENDPOINT);
            assertNotNull(open);
        }
    }

    @Test
    public void testNotFoundRepositoryResolver() throws ServiceNotAuthorizedException, ServiceNotEnabledException, CorruptedSourceException, IOException {
        assertThrows(RepositoryNotFoundException.class, () -> {
            try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER, service);) {
                grm.getRepositoryResolver().open(null, "something");
            }
        });
    }

    @Test
    public void testMountingOnExistingGitRepository() throws CorruptedSourceException, IOException {
        try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER, service)) {
        }

        try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER, service)) {
        }
    }

    @Test
    public void deleteDefaultRef() throws Exception {
        try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER, service)) {
            assertThrows(IllegalArgumentException.class, () -> grm.deleteRef(REF_HEADS_MASTER));
        }
    }

    @Test
    public void testGetTagSourceStream() throws CorruptedSourceException, IOException, NoFilepatternException, GitAPIException {
        File workFolder = getFolderFile();
        try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER, service);
                Git git = Git.cloneRepository().setDirectory(workFolder).setURI(tempDir.toUri().toString()).call();) {
            Path file = workFolder.toPath().resolve("other");
            Path mfile = workFolder.toPath().resolve("other" + METADATA);
            Files.write(mfile, getMetaData().getBytes(UTF_8), CREATE);
            Files.write(file, getData().getBytes(UTF_8), CREATE);
            git.add().addFilepattern(".").call();
            git.commit().setMessage("Initial commit").call();
            verifyOkPush(git.push().call());
            git.tag().setName("tag").call();
            git.push().setPushTags().call();
            SourceInfo sourceInfo = grm.getSourceInfo("other", "refs/tags/tag");
            assertNotNull(sourceInfo.getStreamProvider());
        }
    }

    @Test
    public void testGetSourceStreamNotValid() throws CorruptedSourceException, IOException, RefNotFoundException {
        String ref = "refs/somethingelse/ref";
        assertThat(assertThrows(RefNotFoundException.class, () -> {
            try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER, service)) {
                grm.getSourceInfo("key", ref);
            }
        }).getLocalizedMessage(), CoreMatchers.containsString(ref));
    }

    @Test
    public void testMountingOnDifferentBranches() throws Throwable {
        final String wrongBranch = "wrongbranch";
        final File tmpGit = getFolderFile();
        assertThat(assertThrows(RepositoryIsMissingIntendedBranch.class, () -> {
            try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER, service)) {
                // should create a bare repo with a branch other with a store file with content
            }
            try (Git git = Git.cloneRepository().setURI(tempDir.toUri().toString()).setDirectory(tmpGit).call();) {
                git.commit().setMessage("commit something").call();
                git.branchCreate().setName(wrongBranch).call();
                git.checkout().setName(wrongBranch).call();
                git.branchDelete().setBranchNames("master").call();
                verifyOkPush(git.push().call());
            }
            try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER, service)) {
            }
        }).getLocalizedMessage(), CoreMatchers.containsString(REF_HEADS_MASTER));

    }

    @Test
    public void testInitializingValidRepository() throws InvalidRemoteException, TransportException, GitAPIException, IOException, CorruptedSourceException {
        File base = getFolderFile();
        try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER, service);
                Git git = Git.cloneRepository().setDirectory(base).setURI(tempDir.toUri().toString()).call()) {
            // TODO
        }
    }

    @Test
    public void testPushingANonJSONFormattedStorageFile() throws Exception {
        assertThat(assertThrows(CorruptedSourceException.class, () -> {
            try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER, service)) {
                final File localGitDir = getFolderFile();
                try (Git git = Git.cloneRepository().setURI(grm.repositoryURI().toString()).setDirectory(localGitDir).call()) {
                    final Path file = localGitDir.toPath().resolve(STORE);
                    final Path mfile = localGitDir.toPath().resolve(STORE + METADATA);
                    Files.write(file, getData().getBytes(UTF_8), CREATE_NEW, TRUNCATE_EXISTING);
                    Files.write(mfile, getMetaData().substring(1).getBytes(UTF_8), CREATE_NEW, TRUNCATE_EXISTING);
                    git.add().addFilepattern(".").call();
                    git.commit().setMessage("Test commit").call();
                    // This works since there's no check done on the repository
                    verifyOkPush(git.push().call());
                }
            }
            try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER, service)) {
            }
        }).getLocalizedMessage(), CoreMatchers.containsString("Error in branch " + REF_HEADS_MASTER));
    }

    @Test
    public void testClosedRepositoryAndInputStream() throws Exception {
        try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER, service);) {
            final File localGitDir = getFolderFile();
            try (Git git = Git.cloneRepository().setURI(grm.repositoryURI().toString()).setDirectory(localGitDir).call()) {
                addFilesAndPush(localGitDir, git);
                SourceInfo sourceInfo = grm.getSourceInfo(STORE, REF_HEADS_MASTER);
                try (InputStream is = sourceInfo.getStreamProvider().getInputStream()) {
                    JsonParser parser = MAPPER.getFactory().createParser(is);
                    while (parser.nextToken() != null)
                        ;
                }
            }
        }
    }

    @Test
    public void testListeners() throws CorruptedSourceException, IOException {
        ReloadRefEventListener svl = mock(ReloadRefEventListener.class);
        try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER, service);) {
            grm.addListener(svl, ReloadRefEventListener.class);
        }
    }

    @Test
    public void testCheckHealth() throws CorruptedSourceException, IOException {
        try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER, service);) {
            grm.checkHealth();
        }
    }

    @Test
    public void testCheckHealthWithError() throws CorruptedSourceException, IOException {
        NullPointerException npe = new NullPointerException();
        assertSame(npe, assertThrows(RuntimeException.class, () -> {
            ErrorReporter reporter = new ErrorReporter();
            try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER, null, reporter);) {
                reporter.setFault(npe);
                grm.checkHealth();
            }
        }).getCause());
    }

    @Test
    public void testModifyKey() throws Exception {
        CommitMetaData cmd = new CommitMetaData("user", "mail", "msg", "Test", JITSTATIC_NOWHERE);
        File gitFolder = getFolderFile();
        try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER, service);
                Git git = Git.cloneRepository().setURI(tempDir.toUri().toString()).setDirectory(gitFolder).call();) {

            addFilesAndPush(gitFolder, git);
            SourceInfo firstSourceInfo = grm.getSourceInfo(STORE, null);
            assertNotNull(firstSourceInfo);
            JsonNode firstValue = readJsonData(firstSourceInfo);
            String firstVersion = firstSourceInfo.getSourceVersion();
            byte[] modified = "{\"one\":\"two\"}".getBytes(UTF_8);
            var newVersion = grm.modifyKey(STORE, REF_HEADS_MASTER, toProvider(modified), cmd);
            assertNotEquals(firstVersion, newVersion.getLeft());
            SourceInfo secondSourceInfo = grm.getSourceInfo(STORE, null);
            JsonNode secondValue = readJsonData(secondSourceInfo);
            assertNotEquals(firstValue, secondValue);
            assertEquals(newVersion.getLeft(), secondSourceInfo.getSourceVersion());
            git.pull().call();
            RevCommit revCommit = getRevCommit(git.getRepository(), REF_HEADS_MASTER);
            assertEquals(cmd.getMessage(), revCommit.getShortMessage());
            assertEquals(cmd.getUserInfo(), revCommit.getAuthorIdent().getName());
            assertEquals("Test", revCommit.getCommitterIdent().getName());
        }
    }

    @Test
    public void testModifyKeysIntransaction() throws Exception {
        CommitMetaData cmd1 = new CommitMetaData("user", "mail", "msg1", "Test", JITSTATIC_NOWHERE);
        CommitMetaData cmd2 = new CommitMetaData("user", "mail", "msg2", "Test", JITSTATIC_NOWHERE);
        File gitFolder = getFolderFile();
        try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER, service);
                Git git = Git.cloneRepository().setURI(tempDir.toUri().toString()).setDirectory(gitFolder).call();) {

            addFilesAndPush(gitFolder, git);
            SourceInfo firstSourceInfo = grm.getSourceInfo(STORE, null);
            assertNotNull(firstSourceInfo);
            JsonNode firstValue = readJsonData(firstSourceInfo);
            String firstVersion = firstSourceInfo.getSourceVersion();
            byte[] modified = "{\"one\":\"two\"}".getBytes(UTF_8);
            byte[] modified2 = "{\"one\":\"three\"}".getBytes(UTF_8);
            var newVersion = grm.modifyKey(STORE, REF_HEADS_MASTER, toProvider(modified), cmd1);
            var secondNewVersion = grm.modifyKey(STORE, REF_HEADS_MASTER, toProvider(modified2), cmd2);
            assertNotEquals(firstVersion, newVersion.getLeft());
            SourceInfo secondSourceInfo = grm.getSourceInfo(STORE, null);
            JsonNode secondValue = readJsonData(secondSourceInfo);
            assertNotEquals(firstValue, secondValue);
            assertEquals(secondNewVersion.getLeft(), secondSourceInfo.getSourceVersion());
            git.pull().call();
            Iterator<RevCommit> call = git.log().call().iterator();
            RevCommit last = call.next();
            RevCommit next = call.next();
            assertEquals(cmd2.getMessage(), last.getShortMessage());
            assertEquals(cmd2.getUserInfo(), last.getAuthorIdent().getName());
            assertEquals("Test", last.getCommitterIdent().getName());
            assertEquals(cmd1.getMessage(), next.getShortMessage());
            assertEquals(cmd1.getUserInfo(), next.getAuthorIdent().getName());
            assertEquals("Test", next.getCommitterIdent().getName());
        }
    }

    @Test
    public void testModifyTag() throws CorruptedSourceException, IOException {
        assertThat(assertThrows(UnsupportedOperationException.class, () -> {
            try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER, service)) {
                grm.modifyKey("key", "refs/tags/tag", toProvider(new byte[] { 1, 2, 3, 4 }), new CommitMetaData("user", "mail", "msg", "Test", JITSTATIC_NOWHERE));
            }
        }).getLocalizedMessage(), CoreMatchers.containsString("Tags cannot be modified"));
    }

    @Test
    public void testAddKey() throws Exception {
        File localGitDir = getFolderFile();
        try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER, service);
                Git git = Git.cloneRepository().setURI(tempDir.toUri().toString()).setDirectory(localGitDir).call();) {
            addFilesAndPush(localGitDir, git);
            var addKey = grm.addKey("key", REF_HEADS_MASTER, toProvider(new byte[] { 1 }), new MetaData(null, false, false, List
                    .of(), Set.of(), Set.of()), new CommitMetaData("user", "mail", "msg", "Test", JITSTATIC_NOWHERE));
            String version = addKey.getLeft().getRight();
            assertNotNull(version);
            SourceInfo sourceInfo = grm.getSourceInfo("key", REF_HEADS_MASTER);
            assertNotNull(sourceInfo);
            assertEquals(version, sourceInfo.getSourceVersion());
            try (InputStream is = sourceInfo.getStreamProvider().getInputStream()) {
                byte[] b = new byte[1];
                IOUtils.read(is, b);
                assertArrayEquals(new byte[] { 1 }, b);
            }
        }
    }

    @Test
    public void testModifyMetaData() throws Exception {
        CommitMetaData cmd = new CommitMetaData("user", "mail", "msg", "Test", JITSTATIC_NOWHERE);
        File gitFolder = getFolderFile();
        try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER, service);
                Git git = Git.cloneRepository().setURI(tempDir.toUri().toString()).setDirectory(gitFolder).call();) {
            addFilesAndPush(gitFolder, git);
            SourceInfo firstSourceInfo = grm.getSourceInfo(STORE, null);
            assertNotNull(firstSourceInfo);
            MetaData firstValue = readMetaData(firstSourceInfo);
            String firstVersion = firstSourceInfo.getMetaDataVersion();
            MetaData newData = new MetaData("newcontent", false, false, List.of(), Set.of(), Set.of());
            String newVersion = grm.modifyMetadata(newData, firstVersion, STORE, REF_HEADS_MASTER, cmd);
            assertNotNull(newVersion);
            assertNotEquals(firstVersion, newVersion);
            SourceInfo secondSourceInfo = grm.getSourceInfo(STORE, null);
            MetaData secondValue = readMetaData(secondSourceInfo);
            assertNotEquals(firstValue, secondValue);
            assertEquals(newVersion, secondSourceInfo.getMetaDataVersion());
            assertEquals(firstSourceInfo.getSourceVersion(), secondSourceInfo.getSourceVersion());
            git.pull().call();
            RevCommit revCommit = getRevCommit(git.getRepository(), REF_HEADS_MASTER);
            assertEquals(cmd.getMessage(), revCommit.getShortMessage());
            assertEquals(cmd.getUserInfo(), revCommit.getAuthorIdent().getName());
            assertEquals("Test", revCommit.getCommitterIdent().getName());
        }
    }

    @Test
    public void testModifyMetadataWithTag() throws CorruptedSourceException, IOException {
        try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER, service);) {
            assertThrows(UnsupportedOperationException.class, () -> grm.modifyMetadata(new MetaData("", false, false, List
                    .of(), Set.of(), Set.of()), null, STORE, "refs/tags/tag", new CommitMetaData("user", "mail", "msg", "Test", JITSTATIC_NOWHERE)));
        }
    }

    @Test
    public void testDelete() throws Exception {
        File gitFolder = getFolderFile();
        try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER, service);
                Git git = Git.cloneRepository().setURI(tempDir.toUri().toString()).setDirectory(gitFolder).call()) {
            addFilesAndPush(gitFolder, git);
            SourceInfo sourceInfo = grm.getSourceInfo(STORE, null);
            assertNotNull(sourceInfo);
            grm.deleteKey(STORE, REF_HEADS_MASTER, new CommitMetaData("user", "mail", "msg", "Test", JITSTATIC_NOWHERE));
            SourceInfo sourceInfo2 = grm.getSourceInfo(STORE, null);
            assertNull(sourceInfo2);
        }
    }

    @Test
    public void testRootMasterMetaData() throws Exception {
        File gitFolder = getFolderFile();
        try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER, service);
                Git git = Git.cloneRepository().setURI(tempDir.toUri().toString()).setDirectory(gitFolder).call()) {
            final Path file = gitFolder.toPath().resolve(STORE);
            final Path mfile = gitFolder.toPath().resolve(METADATA);
            Files.write(file, getData().getBytes(UTF_8), CREATE_NEW, TRUNCATE_EXISTING);
            Files.write(mfile, getMetaData().getBytes(UTF_8), CREATE_NEW, TRUNCATE_EXISTING);
            git.add().addFilepattern(".").call();
            git.commit().setMessage("Test commit").call();
            verifyOkPush(git.push().call());

            SourceInfo sourceInfoMasterMetaData = grm.getSourceInfo("/", null);
            assertTrue(sourceInfoMasterMetaData.isMetaDataSource() && !sourceInfoMasterMetaData.hasKeyMetaData());

        }
    }

    @Test
    public void testMasterMetaData() throws Exception {
        File gitFolder = getFolderFile();
        try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER, service);
                Git git = Git.cloneRepository().setURI(tempDir.toUri().toString()).setDirectory(gitFolder).call()) {
            final Path file = gitFolder.toPath().resolve("base/" + STORE);
            final Path mfile = gitFolder.toPath().resolve("base/" + METADATA);
            file.getParent().toFile().mkdirs();

            Files.write(file, getData().getBytes(UTF_8), CREATE_NEW, TRUNCATE_EXISTING);
            Files.write(mfile, getMetaData().getBytes(UTF_8), CREATE_NEW, TRUNCATE_EXISTING);
            git.add().addFilepattern(".").call();
            git.commit().setMessage("Test commit").call();
            verifyOkPush(git.push().call());

            SourceInfo sourceInfoMasterMetaData = grm.getSourceInfo("base/", null);
            assertNotNull(sourceInfoMasterMetaData);

        }
    }

    @Test
    public void testDeleteRootMasterMetaData() throws Exception {
        File gitFolder = getFolderFile();
        try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER, service);
                Git git = Git.cloneRepository().setURI(tempDir.toUri().toString()).setDirectory(gitFolder).call()) {
            final Path file = gitFolder.toPath().resolve(STORE);
            final Path mfile = gitFolder.toPath().resolve(METADATA);
            Files.write(file, getData().getBytes(UTF_8), CREATE_NEW, TRUNCATE_EXISTING);
            Files.write(mfile, getMetaData().getBytes(UTF_8), CREATE_NEW, TRUNCATE_EXISTING);
            git.add().addFilepattern(".").call();
            git.commit().setMessage("Test commit").call();
            verifyOkPush(git.push().call());

            SourceInfo sourceInfo = grm.getSourceInfo(STORE, null);
            assertNotNull(sourceInfo);
            SourceInfo sourceInfoMasterMetaData = grm.getSourceInfo("/", null);
            assertTrue(sourceInfoMasterMetaData.isMetaDataSource() && !sourceInfoMasterMetaData.hasKeyMetaData());
            grm.deleteKey(STORE, REF_HEADS_MASTER, new CommitMetaData("user", "mail", "msg", "Test", JITSTATIC_NOWHERE));
            SourceInfo sourceInfo2 = grm.getSourceInfo(STORE, null);
            assertTrue(sourceInfo2.isMetaDataSource() && !sourceInfo2.hasKeyMetaData());
            sourceInfoMasterMetaData = grm.getSourceInfo("/", null);
            assertNotNull(sourceInfoMasterMetaData);
        }
    }

    @Test
    public void testDeleteMasterMetaData() throws Exception {
        File gitFolder = getFolderFile();
        try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER, service);
                Git git = Git.cloneRepository().setURI(tempDir.toUri().toString()).setDirectory(gitFolder).call()) {
            final String base = "base/";
            final Path file = gitFolder.toPath().resolve(base + STORE);
            final Path mfile = gitFolder.toPath().resolve(base + METADATA);
            file.getParent().toFile().mkdirs();
            Files.write(file, getData().getBytes(UTF_8), CREATE_NEW, TRUNCATE_EXISTING);
            Files.write(mfile, getMetaData().getBytes(UTF_8), CREATE_NEW, TRUNCATE_EXISTING);
            git.add().addFilepattern(".").call();
            git.commit().setMessage("Test commit").call();
            verifyOkPush(git.push().call());

            SourceInfo sourceInfo = grm.getSourceInfo(base + STORE, null);
            assertNotNull(sourceInfo);
            SourceInfo sourceInfoMasterMetaData = grm.getSourceInfo(base, null);
            assertTrue(sourceInfoMasterMetaData.isMetaDataSource() && !sourceInfoMasterMetaData.hasKeyMetaData());
            grm.deleteKey(base + STORE, REF_HEADS_MASTER, new CommitMetaData("user", "mail", "msg", "Test", JITSTATIC_NOWHERE));
            SourceInfo sourceInfo2 = grm.getSourceInfo(base + STORE, null);
            assertTrue(sourceInfo2.isMetaDataSource() && !sourceInfo2.hasKeyMetaData());
            sourceInfoMasterMetaData = grm.getSourceInfo(base, null);
            assertTrue(sourceInfoMasterMetaData.isMetaDataSource() && !sourceInfoMasterMetaData.hasKeyMetaData());
        }
    }

    @Test
    public void testGetSourceInfoWithEmptyKey() throws Exception {
        File gitFolder = getFolderFile();
        try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER, service);
                Git git = Git.cloneRepository().setURI(tempDir.toUri().toString()).setDirectory(gitFolder).call()) {
            addFilesAndPush(gitFolder, git);
            assertThrows(IllegalArgumentException.class, () -> grm.getSourceInfo("", null));
            assertThrows(IllegalArgumentException.class, () -> grm.getSourceInfo("/key", null));
            assertNull(grm.getSourceInfo("/", null));
        }
    }

    @Test
    public void testCreateAndDeleteRef() throws Exception {
        File gitFolder = getFolderFile();
        String branch = "refs/heads/test";
        try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER, service);
                Git git = Git.cloneRepository().setURI(tempDir.toUri().toString()).setDirectory(gitFolder).call()) {
            addFilesAndPush(gitFolder, git);
            Collection<Ref> branches = git.lsRemote().call();
            assertFalse(branches.stream().filter(b -> b.getName().equals(branch)).findAny().isPresent());
            grm.createRef(branch);
            branches = git.lsRemote().call();
            assertTrue(branches.stream().filter(b -> b.getName().equals(branch)).findAny().isPresent());
            grm.deleteRef(branch);
            branches = git.lsRemote().call();
            assertFalse(branches.stream().filter(b -> b.getName().equals(branch)).findAny().isPresent());
        }
    }

    @Test
    public void testGetList() throws Exception {
        File gitFolder = getFolderFile();
        try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER, service);
                Git git = Git.cloneRepository().setURI(tempDir.toUri().toString()).setDirectory(gitFolder).call()) {
            Path dir = gitFolder.toPath().resolve("dir");
            dir.toFile().mkdirs();
            Files.write(dir.resolve("store"), new byte[] { 1, 2 }, CREATE_NEW);
            git.add().addFilepattern(".").call();
            git.commit().setMessage("init").call();
            git.push().call();
            List<String> keys = grm.getList("dir/", REF_HEADS_MASTER, false);
            assertEquals(List.of("dir/store"), keys);
        }
    }

    @Test
    public void testGetListFromNotExistingBranch() throws Exception {
        File gitFolder = getFolderFile();
        try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER, service);
                Git git = Git.cloneRepository().setURI(tempDir.toUri().toString()).setDirectory(gitFolder).call()) {
            addFilesAndPush(gitFolder, git);
            assertThrows(RefNotFoundException.class, () -> grm.getList("store/", "refs/heads/notexisting", false));
        }
    }

    @Test
    public void testHostedGitRepositoryWithUsers() throws Exception {
        File base = createTempDirectory();
        setupGitRepoWithUsers(base);
        try (HostedGitRepositoryManager hgrm = new HostedGitRepositoryManager(base.toPath(), ENDPOINT, REF_HEADS_MASTER, service)) {

        }
    }

    @Test
    public void testGetUser()
            throws IOException, CorruptedSourceException, InvalidRemoteException, TransportException, NoFilepatternException, GitAPIException {
        File base = createTempDirectory();
        setupGitRepoWithUsers(base);
        try (HostedGitRepositoryManager hgrm = new HostedGitRepositoryManager(base.toPath(), ENDPOINT, REF_HEADS_MASTER, service)) {
            Pair<String, UserData> userDataHolder = hgrm.getUser(".users/git/gituser", REF_HEADS_MASTER);
            assertTrue(userDataHolder.isPresent());
            assertEquals("1234", userDataHolder.getRight().getBasicPassword());
        }
    }

    @Test
    public void testGetUserNotFound()
            throws IOException, CorruptedSourceException, InvalidRemoteException, TransportException, NoFilepatternException, GitAPIException {
        File base = createTempDirectory();
        setupGitRepoWithUsers(base);
        try (HostedGitRepositoryManager hgrm = new HostedGitRepositoryManager(base.toPath(), ENDPOINT, REF_HEADS_MASTER, service)) {
            assertFalse(hgrm.getUser(".users/git/kit", REF_HEADS_MASTER).isPresent());
        }
    }

    @Test
    public void testAddUser()
            throws IOException, CorruptedSourceException, InvalidRemoteException, TransportException, NoFilepatternException, GitAPIException {
        File base = createTempDirectory();
        setupGitRepoWithUsers(base);
        try (HostedGitRepositoryManager hgrm = new HostedGitRepositoryManager(base.toPath(), ENDPOINT, REF_HEADS_MASTER, service)) {
            assertNotNull(hgrm.addUser(".users/git/kit", REF_HEADS_MASTER, "doc", new UserData(Set.of(new Role("role")), "1234", null, null)));
        }
    }

    @Test
    public void testUpdateUser()
            throws IOException, CorruptedSourceException, InvalidRemoteException, TransportException, NoFilepatternException, GitAPIException {
        File base = createTempDirectory();
        setupGitRepoWithUsers(base);
        try (HostedGitRepositoryManager hgrm = new HostedGitRepositoryManager(base.toPath(), ENDPOINT, REF_HEADS_MASTER, service)) {
            assertNotNull(hgrm.updateUser(".users/git/kit", REF_HEADS_MASTER, "doc", new UserData(Set.of(new Role("role")), "1234", null, null)));
        }
    }

    @Test
    public void testMountOnCorruptUser()
            throws InvalidRemoteException, TransportException, JsonProcessingException, NoFilepatternException, IOException, GitAPIException {
        File base = createTempDirectory();
        setupGitRepoWithUsers(base);
        File wBase = createTempDirectory();
        try (Git workingGit = Git.cloneRepository().setURI(base.getAbsolutePath()).setDirectory(wBase).call();) {
            Path users = wBase.toPath().resolve(USERS);
            Path creatorRealm = users.resolve(JITSTATIC_KEYADMIN_REALM);
            Files.write(creatorRealm.resolve("error"), new byte[] { 1, 2, 4 }, CREATE_NEW);
            commit(workingGit);
            LOG.info("", assertThrows(CorruptedSourceException.class, () -> new HostedGitRepositoryManager(base
                    .toPath(), ENDPOINT, REF_HEADS_MASTER, service)));
        }
    }

    @Test
    public void testMountOnUserWithoutPassword() throws Exception {
        File base = createTempDirectory();
        setupGitRepoWithUsers(base);
        File wBase = createTempDirectory();
        try (Git workingGit = Git.cloneRepository().setURI(base.getAbsolutePath()).setDirectory(wBase).call();) {
            Path corruptuser = wBase.toPath()
                    .resolve(USERS)
                    .resolve(JITSTATIC_KEYADMIN_REALM)
                    .resolve("corruptuser");
            write(corruptuser, new UserData(Set.of(new Role("role")), null, null, null));
            commit(workingGit);
        }
        LOG.info("", assertThrows(CorruptedSourceException.class, () -> new HostedGitRepositoryManager(base
                .toPath(), ENDPOINT, REF_HEADS_MASTER, service)));
    }

    @Test
    public void testMountOnUserWithoutHash() throws Exception {
        File base = createTempDirectory();
        setupGitRepoWithUsers(base);
        File wBase = createTempDirectory();
        try (Git workingGit = Git.cloneRepository().setURI(base.getAbsolutePath()).setDirectory(wBase).call();) {
            Path corruptuser = wBase.toPath()
                    .resolve(USERS)
                    .resolve(JITSTATIC_KEYADMIN_REALM)
                    .resolve("corruptuser");
            write(corruptuser, new UserData(Set.of(new Role("role")), null, "salt", null));
            commit(workingGit);
        }
        LOG.info("", assertThrows(CorruptedSourceException.class, () -> new HostedGitRepositoryManager(base
                .toPath(), ENDPOINT, REF_HEADS_MASTER, service)));
    }

    @Test
    public void testMountOnUserHashedPassword() throws Exception {
        File base = createTempDirectory();
        setupGitRepoWithUsers(base);
        File wBase = createTempDirectory();
        try (Git workingGit = Git.cloneRepository().setURI(base.getAbsolutePath()).setDirectory(wBase).call();) {
            Path corruptuser = wBase.toPath()
                    .resolve(USERS)
                    .resolve(JITSTATIC_KEYADMIN_REALM)
                    .resolve("corruptuser");
            write(corruptuser, new UserData(Set.of(new Role("role")), null, "salt", "hash"));
            commit(workingGit);
        }
        try (HostedGitRepositoryManager hrm = new HostedGitRepositoryManager(base.toPath(), ENDPOINT, REF_HEADS_MASTER, service)) {
        }
    }

    @Test
    public void testMountOnUserHashedPasswordAndCtxPass() throws Exception {
        File base = createTempDirectory();
        setupGitRepoWithUsers(base);
        File wBase = createTempDirectory();
        try (Git workingGit = Git.cloneRepository().setURI(base.getAbsolutePath()).setDirectory(wBase).call();) {
            Path corruptuser = wBase.toPath()
                    .resolve(USERS)
                    .resolve(JITSTATIC_KEYADMIN_REALM)
                    .resolve("corruptuser");
            write(corruptuser, new UserData(Set.of(new Role("role")), "pass", "salt", "hash"));
            commit(workingGit);
        }
        try (HostedGitRepositoryManager hrm = new HostedGitRepositoryManager(base.toPath(), ENDPOINT, REF_HEADS_MASTER, service)) {
        }
    }

    @Test
    public void testReadAllRefs() throws IOException, InvalidRemoteException, TransportException, NoFilepatternException, GitAPIException,
            CorruptedSourceException, ServiceNotAuthorizedException, ServiceNotEnabledException {
        File base = createTempDirectory();
        setupGitRepoWithUsers(base);
        File wBase = createTempDirectory();
        try (Git workingGit = Git.cloneRepository().setURI(base.getAbsolutePath()).setDirectory(wBase).call();) {
            workingGit.checkout().setCreateBranch(true).setName("other").setUpstreamMode(SetupUpstreamMode.TRACK).call();
            commit(workingGit);
        }
        try (HostedGitRepositoryManager hrm = new HostedGitRepositoryManager(base.toPath(), ENDPOINT, REF_HEADS_MASTER, service)) {
            Repository repository = hrm.getRepositoryResolver().open(null, ENDPOINT);
            Map<String, String> refs = new ConcurrentHashMap<>();
            repository.getListenerList().addListener(AddRefEventListener.class, e -> {
                refs.put(e, "dummy");
            });
            hrm.readAllRefs();
            Awaitility.await().atMost(Durations.TEN_SECONDS).until(() -> refs.keySet().size(), equalTo(2));
            assertEquals(Set.of("refs/heads/master", "refs/heads/other"), refs.keySet());
        }
    }

    @Test
    public void testMountMetaDataWithUsers() throws Exception {
        File base = createTempDirectory();
        setupGitRepoWithUsers(base);
        File wBase = createTempDirectory();
        try (Git git = Git.cloneRepository().setURI(base.getAbsolutePath()).setDirectory(wBase).call();) {
            final Path file = wBase.toPath().resolve(STORE);
            final Path mfile = wBase.toPath().resolve(STORE + METADATA);
            file.getParent().toFile().mkdirs();
            RemoteTestUtils.copy("/test3.json", file);
            RemoteTestUtils.copy("/test3.md2.json", mfile);
            git.add().addFilepattern(".").call();
            git.commit().setMessage("Test commit").call();
            verifyOkPush(git.push().call());
        }
        assertThrows(CorruptedSourceException.class, () -> new HostedGitRepositoryManager(base.toPath(), ENDPOINT, REF_HEADS_MASTER, service));
    }

    private void setupGitRepoWithUsers(File base)
            throws IOException, GitAPIException, InvalidRemoteException, TransportException, JsonProcessingException, NoFilepatternException {
        File wBase = createTempDirectory();
        try (Git bareGit = Git.init().setBare(true).setDirectory(base).call();
                Git workingGit = Git.cloneRepository().setURI(bareGit.getRepository().getDirectory().toURI().toString()).setDirectory(wBase).call();) {

            Path users = wBase.toPath().resolve(USERS);
            assertTrue(users.toFile().mkdirs());
            Path gitRealm = users.resolve(JITSTATIC_GIT_REALM);

            Path creatorRealm = users.resolve(JITSTATIC_KEYADMIN_REALM);
            Path updaterRealm = users.resolve(JITSTATIC_KEYUSER_REALM);
            mkdirs(gitRealm, creatorRealm, updaterRealm);

            String gitUserKey = "gituser";
            String creatorUserKey = "creatorUser";
            String updaterUserKey = "updaterUser";

            Path gituser = gitRealm.resolve(gitUserKey);
            Path creatorUser = creatorRealm.resolve(creatorUserKey);
            Path updaterUser = updaterRealm.resolve(updaterUserKey);

            Path sgituser = gitRealm.resolve("sgituser");
            Path screatorUser = creatorRealm.resolve("screatorUser");
            Path supdaterUser = updaterRealm.resolve("supdaterUser");

            UserData gitUserData = new UserData(Set.of(new Role("pull"), new Role("push"), new Role("forcepush")), "1234", null, null); // Full admin rights
            UserData creatorUserData = new UserData(Set.of(new Role("files")), "2345", null, null);
            UserData updaterUserData = new UserData(Set.of(new Role("files")), "3456", null, null);

            UserData sgitUserData = new UserData(Set.of(new Role("pull"), new Role("push"), new Role("forcepush")), "s1234", null, null); // Full admin rights
            UserData screatorUserData = new UserData(Set.of(new Role("files")), "s2345", null, null);
            UserData supdaterUserData = new UserData(Set.of(new Role("files")), "s3456", null, null);

            write(gituser, gitUserData);
            write(creatorUser, creatorUserData);
            write(updaterUser, updaterUserData);

            write(sgituser, sgitUserData);
            write(screatorUser, screatorUserData);
            write(supdaterUser, supdaterUserData);

            commit(workingGit);
        }
    }

    private void addFilesAndPush(final File localGitDir,
            Git git) throws Exception {
        final Path file = localGitDir.toPath().resolve(STORE);
        final Path mfile = localGitDir.toPath().resolve(STORE + METADATA);
        Files.write(file, getData().getBytes(UTF_8), CREATE_NEW, TRUNCATE_EXISTING);
        Files.write(mfile, getMetaData().getBytes(UTF_8), CREATE_NEW, TRUNCATE_EXISTING);
        git.add().addFilepattern(".").call();
        git.commit().setMessage("Test commit").call();
        // This works since there's no check done on the repository
        verifyOkPush(git.push().call());
    }

    public void write(Path userPath,
            UserData userData) throws IOException, JsonProcessingException {
        Files.write(userPath, MAPPER.writeValueAsBytes(userData), CREATE);
    }

    private MetaData readMetaData(SourceInfo secondSourceInfo) throws IOException, JsonParseException, JsonMappingException {
        try (InputStream is = secondSourceInfo.getMetadataInputStream()) {
            return MAPPER.readValue(is, MetaData.class);
        }
    }

    private void commit(Git workingGit) throws NoFilepatternException, GitAPIException {
        workingGit.add().addFilepattern(".").call();
        workingGit.commit().setMessage("Initial commit").call();
        verifyOkPush(workingGit.push().call());
    }

    private File createTempDirectory() throws IOException {
        return tmpFolder.createTemporaryDirectory();
    }

    private RevCommit getRevCommit(Repository repository,
            String targetRef) throws IOException {
        try (RevWalk revWalk = new RevWalk(repository)) {
            revWalk.sort(RevSort.COMMIT_TIME_DESC);
            Ref actualTargetRef = repository.getRefDatabase().exactRef(targetRef);
            RevCommit commit = revWalk.parseCommit(actualTargetRef.getLeaf().getObjectId());
            revWalk.markStart(commit);
            return revWalk.next();
        }
    }

    private JsonNode readJsonData(SourceInfo sourceInfo) throws IOException, JsonParseException, JsonMappingException {
        try (InputStream is = sourceInfo.getStreamProvider().getInputStream()) {
            return MAPPER.readValue(is, JsonNode.class);
        }
    }
}
