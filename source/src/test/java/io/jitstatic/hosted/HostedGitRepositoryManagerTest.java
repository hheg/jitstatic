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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.isA;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.io.UnsupportedEncodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

import org.apache.commons.io.IOUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.AbortedByHookException;
import org.eclipse.jgit.api.errors.ConcurrentRefUpdateException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.NoFilepatternException;
import org.eclipse.jgit.api.errors.NoHeadException;
import org.eclipse.jgit.api.errors.NoMessageException;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.api.errors.UnmergedPathsException;
import org.eclipse.jgit.api.errors.WrongRepositoryStateException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefDatabase;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.RemoteRefUpdate.Status;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;
import org.eclipse.jgit.transport.resolver.ServiceNotEnabledException;
import org.hamcrest.CoreMatchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonParser.Feature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.jitstatic.CorruptedSourceException;
import io.jitstatic.RepositoryIsMissingIntendedBranch;
import io.jitstatic.StorageData;
import io.jitstatic.source.SourceEventListener;
import io.jitstatic.source.SourceInfo;
import io.jitstatic.utils.Pair;
import io.jitstatic.utils.WrappingAPIException;

public class HostedGitRepositoryManagerTest {

    private static final String UTF_8 = "UTF-8";
    private static final String METADATA = ".metadata";
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(Feature.ALLOW_COMMENTS).enable(Feature.STRICT_DUPLICATE_DETECTION);
    private static final String ENDPOINT = "endpoint";
    private static final String REF_HEADS_MASTER = Constants.R_HEADS + Constants.MASTER;
    private static final String STORE = "store";

    private Path tempFile;
    private Path tempDir;
    private Executor service;

    @BeforeEach
    public void setup() throws IOException {
        tempDir = getFolder();
        tempFile = Files.createTempFile("junit", "");
        service = new PriorityExecutor();
    }

    Path getFolder() throws IOException {
        return Files.createTempDirectory("junit");
    }

    @Test
    public void testCreatedBareDirectory() throws CorruptedSourceException, IOException {
        try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER);) {
            assertTrue(Files.exists(Paths.get(grm.repositoryURI()).resolve(Constants.HEAD)));
        }
    }

    @Test()
    public void testForDirectory() throws CorruptedSourceException, IOException {
        assertThat(assertThrows(IllegalArgumentException.class, () -> {
            try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempFile, ENDPOINT, REF_HEADS_MASTER);) {
            }
        }).getLocalizedMessage(), CoreMatchers.containsString(String.format("Path %s is a file", tempFile)));
    }

    @Test()
    public void testForWritableDirectory() throws IOException, CorruptedSourceException {
        assertThat(assertThrows(IllegalArgumentException.class, () -> {
            Set<PosixFilePermission> perms = PosixFilePermissions.fromString("r-xr-x---");
            Files.setPosixFilePermissions(tempDir, perms);
            try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER);) {
            }
        }).getLocalizedMessage(), CoreMatchers.containsString(String.format("Path %s is not writeable", tempDir)));
    }

    @Test
    public void testForNullEndPoint() throws CorruptedSourceException, IOException {
        assertThrows(NullPointerException.class, () -> {
            try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, null, REF_HEADS_MASTER);) {
            }
        });
    }

    @Test
    public void testForEmptyEndPoint() throws CorruptedSourceException, IOException {
        assertThat(assertThrows(IllegalArgumentException.class, () -> {
            try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, "", REF_HEADS_MASTER);) {
            }
        }).getLocalizedMessage(), CoreMatchers.containsString("Parameter endPointName cannot be empty"));
    }
    
    @Test
    public void testForEmptyDefaultBranch() throws CorruptedSourceException, IOException {
        assertThat(assertThrows(IllegalArgumentException.class, () -> {
            try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, "endpoint", "");) {
            }
        }).getLocalizedMessage(), CoreMatchers.containsString("defaultBranch cannot be empty"));
    }
    
    @Test
    public void testForNullDefaultBranch() throws CorruptedSourceException, IOException {
        assertThat(assertThrows(NullPointerException.class, () -> {
            try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, "endpoint", null);) {
            }
        }).getLocalizedMessage(), CoreMatchers.containsString("defaultBranch cannot be null"));
    }

    @Test
    public void testGetRepositoryResolver()
            throws ServiceNotAuthorizedException, ServiceNotEnabledException, CorruptedSourceException, IOException {
        try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER);) {
            Repository open = grm.getRepositoryResolver().open(null, ENDPOINT);
            assertNotNull(open);
        }
    }

    @Test
    public void testNotFoundRepositoryResolver()
            throws ServiceNotAuthorizedException, ServiceNotEnabledException, CorruptedSourceException, IOException {
        assertThrows(RepositoryNotFoundException.class, () -> {
            try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER);) {
                grm.getRepositoryResolver().open(null, "something");
            }
        });
    }

    @Test
    public void testMountingOnExistingGitRepository() throws CorruptedSourceException, IOException {
        try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER)) {
        }

        try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER)) {
        }
    }

    @Test
    public void testGetTagSourceStream() throws CorruptedSourceException, IOException, NoFilepatternException, GitAPIException {
        File workFolder = getFolder().toFile();
        try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER);
                Git git = Git.cloneRepository().setDirectory(workFolder).setURI(tempDir.toUri().toString()).call();) {
            Path file = workFolder.toPath().resolve("other");
            Path mfile = workFolder.toPath().resolve("other" + METADATA);
            Files.write(mfile, getMetaData().getBytes(UTF_8), StandardOpenOption.CREATE);
            Files.write(file, getData().getBytes(UTF_8), StandardOpenOption.CREATE);
            git.add().addFilepattern(".").call();
            git.commit().setMessage("Initial commit").call();
            verifyOkPush(git.push().call());
            git.tag().setName("tag").call();
            git.push().setPushTags().call();
            SourceInfo sourceInfo = grm.getSourceInfo("other", "refs/tags/tag");
            try (InputStream is = sourceInfo.getSourceInputStream()) {
                assertNotNull(is);
            }
        }
    }

    @Test
    public void testGetSourceStreamNotValid() throws CorruptedSourceException, IOException, RefNotFoundException {
        String ref = "refs/somethingelse/ref";
        assertThat(assertThrows(RefNotFoundException.class, () -> {
            try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER)) {
                grm.getSourceInfo("key", ref);
            }
        }).getLocalizedMessage(), CoreMatchers.containsString(ref));
    }

    @Test
    public void testMountingOnDifferentBranches() throws Throwable {
        final String wrongBranch = "wrongbranch";
        final File tmpGit = getFolder().toFile();
        assertThat(assertThrows(RepositoryIsMissingIntendedBranch.class, () -> {
            try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER)) {
                // should create a bare repo with a branch other with a store file with content
            }
            try (Git git = Git.cloneRepository().setURI(tempDir.toUri().toString()).setDirectory(tmpGit).call();) {
                git.commit().setMessage("commit something").call();
                git.branchCreate().setName(wrongBranch).call();
                git.checkout().setName(wrongBranch).call();
                git.branchDelete().setBranchNames("master").call();
                verifyOkPush(git.push().call(), "refs/heads/wrongbranch");
            }
            try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER)) {
            }
        }).getLocalizedMessage(), CoreMatchers.containsString(REF_HEADS_MASTER));

    }

    @Test
    public void testInitializingValidRepository()
            throws InvalidRemoteException, TransportException, GitAPIException, IOException, CorruptedSourceException {
        File base = getFolder().toFile();
        try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER);
                Git git = Git.cloneRepository().setDirectory(base).setURI(tempDir.toUri().toString()).call()) {
        }
    }

    @Test
    public void testPushingANonJSONFormattedStorageFile() throws Exception {
        assertThat(assertThrows(CorruptedSourceException.class, () -> {
            try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER)) {
                final File localGitDir = getFolder().toFile();
                try (Git git = Git.cloneRepository().setURI(grm.repositoryURI().toString()).setDirectory(localGitDir).call()) {
                    final Path file = localGitDir.toPath().resolve(STORE);
                    final Path mfile = localGitDir.toPath().resolve(STORE + METADATA);
                    Files.write(file, getData().getBytes(UTF_8), StandardOpenOption.CREATE_NEW, StandardOpenOption.TRUNCATE_EXISTING);
                    Files.write(mfile, getMetaData().substring(1).getBytes(UTF_8), StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.TRUNCATE_EXISTING);
                    git.add().addFilepattern(".").call();
                    git.commit().setMessage("Test commit").call();
                    // This works since there's no check done on the repository
                    verifyOkPush(git.push().call());
                }
            }
            try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER)) {
            }
        }).getLocalizedMessage(), CoreMatchers.containsString("Error in branch " + REF_HEADS_MASTER));
    }

    private void addFilesAndPush(final File localGitDir, Git git) throws IOException, UnsupportedEncodingException, GitAPIException,
            NoFilepatternException, NoHeadException, NoMessageException, UnmergedPathsException, ConcurrentRefUpdateException,
            WrongRepositoryStateException, AbortedByHookException, InvalidRemoteException, TransportException {
        final Path file = localGitDir.toPath().resolve(STORE);
        final Path mfile = localGitDir.toPath().resolve(STORE + METADATA);
        Files.write(file, getData().getBytes(UTF_8), StandardOpenOption.CREATE_NEW, StandardOpenOption.TRUNCATE_EXISTING);
        Files.write(mfile, getMetaData().getBytes(UTF_8), StandardOpenOption.CREATE_NEW, StandardOpenOption.TRUNCATE_EXISTING);
        git.add().addFilepattern(".").call();
        git.commit().setMessage("Test commit").call();
        // This works since there's no check done on the repository
        verifyOkPush(git.push().call());
    }

    @Test
    public void testClosedRepositoryAndInputStream()
            throws IOException, CorruptedSourceException, InvalidRemoteException, TransportException, GitAPIException {
        try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER);) {
            final File localGitDir = getFolder().toFile();
            try (Git git = Git.cloneRepository().setURI(grm.repositoryURI().toString()).setDirectory(localGitDir).call()) {
                addFilesAndPush(localGitDir, git);
                SourceInfo sourceInfo = grm.getSourceInfo(STORE, REF_HEADS_MASTER);
                try (InputStream is = sourceInfo.getSourceInputStream()) {
                    JsonParser parser = MAPPER.getFactory().createParser(is);
                    while (parser.nextToken() != null)
                        ;
                }
            }
        }
    }

    @Test
    public void testListeners() throws CorruptedSourceException, IOException {
        SourceEventListener svl = mock(SourceEventListener.class);
        try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER);) {
            grm.addListener(svl);
        }
    }

    @Test
    public void testCheckHealth() throws CorruptedSourceException, IOException {
        try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER);) {
            grm.checkHealth();
        }
    }

    @Test
    public void testCheckHealthWithError() throws CorruptedSourceException, IOException {
        NullPointerException npe = new NullPointerException();
        assertSame(npe, assertThrows(RuntimeException.class, () -> {
            ErrorReporter reporter = new ErrorReporter();
            try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER, service, reporter);) {
                reporter.setFault(npe);
                grm.checkHealth();
            }
        }).getCause());
    }

    @Test
    public void testModifyKey() throws CorruptedSourceException, IOException, InvalidRemoteException, TransportException, GitAPIException,
            InterruptedException, ExecutionException {
        String message = "modified value";
        String userInfo = "test@test";
        File gitFolder = getFolder().toFile();
        try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER);
                Git git = Git.cloneRepository().setURI(tempDir.toUri().toString()).setDirectory(gitFolder).call()) {
            addFilesAndPush(gitFolder, git);
            SourceInfo firstSourceInfo = grm.getSourceInfo(STORE, null);
            assertNotNull(firstSourceInfo);
            JsonNode firstValue = readJsonData(firstSourceInfo);
            String firstVersion = firstSourceInfo.getSourceVersion();
            byte[] modified = "{\"one\":\"two\"}".getBytes(UTF_8);
            String newVersion = grm.modify(STORE, null, modified, firstVersion, message, userInfo, "user@somewhere.org");
            assertNotEquals(firstVersion, newVersion);
            SourceInfo secondSourceInfo = grm.getSourceInfo(STORE, null);
            JsonNode secondValue = readJsonData(secondSourceInfo);
            assertNotEquals(firstValue, secondValue);
            assertEquals(newVersion, secondSourceInfo.getSourceVersion());
            git.pull().call();
            RevCommit revCommit = getRevCommit(git.getRepository(), REF_HEADS_MASTER);
            assertEquals(message, revCommit.getShortMessage());
            assertEquals(userInfo, revCommit.getAuthorIdent().getName());
            assertEquals("JitStatic API update key operation", revCommit.getCommitterIdent().getName());
        }
    }

    @Test
    public void testModifyTag() throws CorruptedSourceException, IOException {
        assertThat(assertThrows(UnsupportedOperationException.class, () -> {
            try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER);) {
                grm.modify("key", "refs/tags/tag", new byte[] { 1, 2, 3, 4 }, "1", "m", "ui", "m");
            }
        }).getLocalizedMessage(), CoreMatchers.containsString("Tags cannot be modified"));
    }

    @Test
    public void testAddKey() throws CorruptedSourceException, IOException, InvalidRemoteException, TransportException, GitAPIException {
        File localGitDir = getFolder().toFile();
        try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER);
                Git git = Git.cloneRepository().setURI(tempDir.toUri().toString()).setDirectory(localGitDir).call()) {
            addFilesAndPush(localGitDir, git);
            Pair<String, String> addKey = grm.addKey("key", REF_HEADS_MASTER, new byte[] { 1 },
                    new StorageData(new HashSet<>(), null, false, false, List.of()), "message", "userinfo", "mail");
            String version = addKey.getLeft();
            assertNotNull(version);
            SourceInfo sourceInfo = grm.getSourceInfo("key", REF_HEADS_MASTER);
            assertNotNull(sourceInfo);
            assertEquals(version, sourceInfo.getSourceVersion());
            try (InputStream is = sourceInfo.getSourceInputStream()) {
                byte[] b = new byte[1];
                IOUtils.read(is, b);
                assertArrayEquals(new byte[] { 1 }, b);
            }
        }
    }

    @Test
    public void testAddKeyButNoBranch() throws Throwable {
        assertThat((RefNotFoundException) assertThrows(WrappingAPIException.class, () -> {
            try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER)) {
                grm.addKey("key", REF_HEADS_MASTER, new byte[] { 1 }, new StorageData(new HashSet<>(), null, false, false, List.of()),
                        "message", "userinfo", "mail");
            }
        }).getCause(), isA(RefNotFoundException.class));
    }

    @Test
    public void testModifyMetaData() throws Exception {
        String message = "modified value";
        String userInfo = "test@test";
        File gitFolder = getFolder().toFile();
        try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER);
                Git git = Git.cloneRepository().setURI(tempDir.toUri().toString()).setDirectory(gitFolder).call()) {
            addFilesAndPush(gitFolder, git);
            SourceInfo firstSourceInfo = grm.getSourceInfo(STORE, null);
            assertNotNull(firstSourceInfo);
            StorageData firstValue;
            try (InputStream is = firstSourceInfo.getMetadataInputStream()) {
                firstValue = MAPPER.readValue(is, StorageData.class);
            }

            String firstVersion = firstSourceInfo.getMetaDataVersion();
            StorageData newData = new StorageData(new HashSet<>(), "newcontent", false, false, List.of());
            String newVersion = grm.modify(newData, firstVersion, message, userInfo, "user@somewhere.org", STORE, null);
            assertNotEquals(firstVersion, newVersion);
            SourceInfo secondSourceInfo = grm.getSourceInfo(STORE, null);
            StorageData secondValue;
            try (InputStream is = secondSourceInfo.getMetadataInputStream()) {
                secondValue = MAPPER.readValue(is, StorageData.class);
            }
            assertNotEquals(firstValue, secondValue);
            assertEquals(newVersion, secondSourceInfo.getMetaDataVersion());
            assertEquals(firstSourceInfo.getSourceVersion(), secondSourceInfo.getSourceVersion());
            git.pull().call();
            RevCommit revCommit = getRevCommit(git.getRepository(), REF_HEADS_MASTER);
            assertEquals(message, revCommit.getShortMessage());
            assertEquals(userInfo, revCommit.getAuthorIdent().getName());
            assertEquals("JitStatic API update metadata operation", revCommit.getCommitterIdent().getName());
        }
    }

    @Test
    public void testAddKeyButKeyAlreadyExist() throws Exception {
        File gitFolder = getFolder().toFile();
        try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER);
                Git git = Git.cloneRepository().setURI(tempDir.toUri().toString()).setDirectory(gitFolder).call()) {
            addFilesAndPush(gitFolder, git);
            StorageData secondValue;
            try (InputStream is = grm.getSourceInfo(STORE, REF_HEADS_MASTER).getMetadataInputStream()) {
                secondValue = MAPPER.readValue(is, StorageData.class);
            }
            assertTrue(assertThrows(WrappingAPIException.class, () -> {
                grm.addKey(STORE, REF_HEADS_MASTER, new byte[] { 1 }, secondValue, "mess", "ui", "mail");
            }).getCause() instanceof KeyAlreadyExist);
        }
    }

    @Test
    public void testModifyMetadataWithTag() throws CorruptedSourceException, IOException {
        try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER)) {
            assertThrows(UnsupportedOperationException.class, () -> grm.modify(new StorageData(Set.of(), "", false, false, List.of()), "1",
                    "msg", "ui", "mail", STORE, "refs/tags/tag"));
        }
    }

    @Test
    public void testDelete() throws Exception {
        File gitFolder = getFolder().toFile();
        try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER);
                Git git = Git.cloneRepository().setURI(tempDir.toUri().toString()).setDirectory(gitFolder).call()) {
            addFilesAndPush(gitFolder, git);
            SourceInfo sourceInfo = grm.getSourceInfo(STORE, null);
            assertNotNull(sourceInfo);
            grm.delete(STORE, null, "user", "msg", "user");
            SourceInfo sourceInfo2 = grm.getSourceInfo(STORE, null);
            assertNull(sourceInfo2);
        }
    }

    @Test
    public void testRootMasterMetaData() throws Exception {
        File gitFolder = getFolder().toFile();
        try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER);
                Git git = Git.cloneRepository().setURI(tempDir.toUri().toString()).setDirectory(gitFolder).call()) {
            final Path file = gitFolder.toPath().resolve(STORE);
            final Path mfile = gitFolder.toPath().resolve(METADATA);
            Files.write(file, getData().getBytes(UTF_8), StandardOpenOption.CREATE_NEW, StandardOpenOption.TRUNCATE_EXISTING);
            Files.write(mfile, getMetaData().getBytes(UTF_8), StandardOpenOption.CREATE_NEW, StandardOpenOption.TRUNCATE_EXISTING);
            git.add().addFilepattern(".").call();
            git.commit().setMessage("Test commit").call();
            verifyOkPush(git.push().call());

            SourceInfo sourceInfoMasterMetaData = grm.getSourceInfo("/", null);
            assertTrue(sourceInfoMasterMetaData.isMetaDataSource() && !sourceInfoMasterMetaData.hasKeyMetaData());

        }
    }

    @Test
    public void testMasterMetaData() throws Exception {
        File gitFolder = getFolder().toFile();
        try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER);
                Git git = Git.cloneRepository().setURI(tempDir.toUri().toString()).setDirectory(gitFolder).call()) {
            final Path file = gitFolder.toPath().resolve("base/" + STORE);
            final Path mfile = gitFolder.toPath().resolve("base/" + METADATA);
            file.getParent().toFile().mkdirs();

            Files.write(file, getData().getBytes(UTF_8), StandardOpenOption.CREATE_NEW, StandardOpenOption.TRUNCATE_EXISTING);
            Files.write(mfile, getMetaData().getBytes(UTF_8), StandardOpenOption.CREATE_NEW, StandardOpenOption.TRUNCATE_EXISTING);
            git.add().addFilepattern(".").call();
            git.commit().setMessage("Test commit").call();
            verifyOkPush(git.push().call());

            SourceInfo sourceInfoMasterMetaData = grm.getSourceInfo("base/", null);
            assertNotNull(sourceInfoMasterMetaData);

        }
    }

    @Test
    public void testDeleteRootMasterMetaData() throws Exception {
        File gitFolder = getFolder().toFile();
        try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER);
                Git git = Git.cloneRepository().setURI(tempDir.toUri().toString()).setDirectory(gitFolder).call()) {
            final Path file = gitFolder.toPath().resolve(STORE);
            final Path mfile = gitFolder.toPath().resolve(METADATA);
            Files.write(file, getData().getBytes(UTF_8), StandardOpenOption.CREATE_NEW, StandardOpenOption.TRUNCATE_EXISTING);
            Files.write(mfile, getMetaData().getBytes(UTF_8), StandardOpenOption.CREATE_NEW, StandardOpenOption.TRUNCATE_EXISTING);
            git.add().addFilepattern(".").call();
            git.commit().setMessage("Test commit").call();
            verifyOkPush(git.push().call());

            SourceInfo sourceInfo = grm.getSourceInfo(STORE, null);
            assertNotNull(sourceInfo);
            SourceInfo sourceInfoMasterMetaData = grm.getSourceInfo("/", null);
            assertTrue(sourceInfoMasterMetaData.isMetaDataSource() && !sourceInfoMasterMetaData.hasKeyMetaData());
            grm.delete(STORE, null, "user", "msg", "user");
            SourceInfo sourceInfo2 = grm.getSourceInfo(STORE, null);
            assertTrue(sourceInfo2.isMetaDataSource() && !sourceInfo2.hasKeyMetaData());
            sourceInfoMasterMetaData = grm.getSourceInfo("/", null);
            assertNotNull(sourceInfoMasterMetaData);
        }
    }

    @Test
    public void testDeleteMasterMetaData() throws Exception {
        File gitFolder = getFolder().toFile();
        try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER);
                Git git = Git.cloneRepository().setURI(tempDir.toUri().toString()).setDirectory(gitFolder).call()) {
            final String base = "base/";
            final Path file = gitFolder.toPath().resolve(base + STORE);
            final Path mfile = gitFolder.toPath().resolve(base + METADATA);
            file.getParent().toFile().mkdirs();
            Files.write(file, getData().getBytes(UTF_8), StandardOpenOption.CREATE_NEW, StandardOpenOption.TRUNCATE_EXISTING);
            Files.write(mfile, getMetaData().getBytes(UTF_8), StandardOpenOption.CREATE_NEW, StandardOpenOption.TRUNCATE_EXISTING);
            git.add().addFilepattern(".").call();
            git.commit().setMessage("Test commit").call();
            verifyOkPush(git.push().call());

            SourceInfo sourceInfo = grm.getSourceInfo(base + STORE, null);
            assertNotNull(sourceInfo);
            SourceInfo sourceInfoMasterMetaData = grm.getSourceInfo(base, null);
            assertTrue(sourceInfoMasterMetaData.isMetaDataSource() && !sourceInfoMasterMetaData.hasKeyMetaData());
            grm.delete(base + STORE, null, "user", "msg", "user");
            SourceInfo sourceInfo2 = grm.getSourceInfo(base + STORE, null);
            assertTrue(sourceInfo2.isMetaDataSource() && !sourceInfo2.hasKeyMetaData());
            sourceInfoMasterMetaData = grm.getSourceInfo(base, null);
            assertTrue(sourceInfoMasterMetaData.isMetaDataSource() && !sourceInfoMasterMetaData.hasKeyMetaData());
        }
    }

    @Test
    public void testGetSourceInfoWithEmptyKey() throws Exception {
        File gitFolder = getFolder().toFile();
        try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER);
                Git git = Git.cloneRepository().setURI(tempDir.toUri().toString()).setDirectory(gitFolder).call()) {
            addFilesAndPush(gitFolder, git);
            assertThrows(IllegalArgumentException.class, () -> grm.getSourceInfo("", null));
            assertThrows(IllegalArgumentException.class, () -> grm.getSourceInfo("/key", null));
            assertNull(grm.getSourceInfo("/", null));
        }
    }

    private void verifyOkPush(Iterable<PushResult> iterable) {
        verifyOkPush(iterable, REF_HEADS_MASTER);
    }

    private void verifyOkPush(Iterable<PushResult> iterable, String branch) {
        PushResult pushResult = iterable.iterator().next();
        RemoteRefUpdate remoteUpdate = pushResult.getRemoteUpdate(branch);
        assertEquals(Status.OK, remoteUpdate.getStatus());
    }

    private RevCommit getRevCommit(Repository repository, String targetRef) throws IOException {
        try (RevWalk revWalk = new RevWalk(repository)) {
            revWalk.sort(RevSort.COMMIT_TIME_DESC);
            Map<String, Ref> allRefs = repository.getRefDatabase().getRefs(RefDatabase.ALL);
            Ref actualTargetRef = allRefs.get(targetRef);
            RevCommit commit = revWalk.parseCommit(actualTargetRef.getLeaf().getObjectId());
            revWalk.markStart(commit);
            return revWalk.next();
        }
    }

    private JsonNode readJsonData(SourceInfo sourceInfo) throws IOException, JsonParseException, JsonMappingException {
        try (InputStream is = sourceInfo.getSourceInputStream()) {
            return MAPPER.readValue(is, JsonNode.class);
        }
    }

    private String getMetaData() {
        return "{\"users\":[{\"password\":\"1234\",\"user\":\"user1\"}]}";
    }

    private String getData() {
        return getData(0);
    }

    private String getData(int i) {
        return "{\"key" + i
                + "\":{\"data\":\"value1\",\"users\":[{\"password\":\"1234\",\"user\":\"user1\"}]},\"key3\":{\"data\":\"value3\",\"users\":[{\"password\":\"1234\",\"user\":\"user1\"}]}}";
    }

}
