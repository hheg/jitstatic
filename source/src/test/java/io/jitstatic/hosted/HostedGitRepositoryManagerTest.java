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

import static io.jitstatic.JitStaticConstants.GIT_REALM;
import static io.jitstatic.JitStaticConstants.JITSTATIC_KEYADMIN_REALM;
import static io.jitstatic.JitStaticConstants.JITSTATIC_KEYUSER_REALM;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.isA;
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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.io.IOUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.NoFilepatternException;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectLoader;
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
import org.junit.jupiter.api.extension.ExtendWith;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonParser.Feature;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.jitstatic.CommitMetaData;
import io.jitstatic.JitStaticConstants;
import io.jitstatic.MetaData;
import io.jitstatic.Role;
import io.jitstatic.auth.UserData;
import io.jitstatic.check.CorruptedSourceException;
import io.jitstatic.check.RepositoryIsMissingIntendedBranch;
import io.jitstatic.source.SourceEventListener;
import io.jitstatic.source.SourceInfo;
import io.jitstatic.test.TemporaryFolder;
import io.jitstatic.test.TemporaryFolderExtension;
import io.jitstatic.utils.Functions.ThrowingSupplier;
import io.jitstatic.utils.Pair;
import io.jitstatic.utils.WrappingAPIException;

@ExtendWith(TemporaryFolderExtension.class)
public class HostedGitRepositoryManagerTest {

    private static final Charset UTF_8 = StandardCharsets.UTF_8;
    private static final String METADATA = ".metadata";
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(Feature.ALLOW_COMMENTS).enable(Feature.STRICT_DUPLICATE_DETECTION);
    private static final String ENDPOINT = "endpoint";
    private static final String REF_HEADS_MASTER = Constants.R_HEADS + Constants.MASTER;
    private static final String STORE = "store";
    private TemporaryFolder tmpFolder;
    private Path tempFile;
    private Path tempDir;

    @BeforeEach
    public void setup() throws IOException {
        tempDir = getFolder();
        tempFile = tmpFolder.createTemporaryFile();
    }

    Path getFolder() throws IOException {
        return tmpFolder.createTemporaryDirectory().toPath();
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
    public void testGetRepositoryResolver() throws ServiceNotAuthorizedException, ServiceNotEnabledException, CorruptedSourceException, IOException {
        try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER);) {
            Repository open = grm.getRepositoryResolver().open(null, ENDPOINT);
            assertNotNull(open);
        }
    }

    @Test
    public void testNotFoundRepositoryResolver() throws ServiceNotAuthorizedException, ServiceNotEnabledException, CorruptedSourceException, IOException {
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
    public void deleteDefaultRef() throws Exception {
        try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER)) {
            assertThrows(IllegalArgumentException.class, () -> grm.deleteRef(REF_HEADS_MASTER));
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
            assertNotNull(sourceInfo.getSourceProvider());            
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
    public void testInitializingValidRepository() throws InvalidRemoteException, TransportException, GitAPIException, IOException, CorruptedSourceException {
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
                    Files.write(file, getData().getBytes(UTF_8), CREATE_NEW, TRUNCATE_EXISTING);
                    Files.write(mfile, getMetaData().substring(1).getBytes(UTF_8), CREATE_NEW, TRUNCATE_EXISTING);
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

    @Test
    public void testCheckVersionRefNotFound() throws Exception {
        try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER) {
            public SourceInfo getSourceInfo(String key, String ref) throws RefNotFoundException {
                throw new RefNotFoundException("test");
            };
        }) {
            assertSame(RefNotFoundException.class,
                    assertThrows(WrappingAPIException.class, () -> grm.modifyKey("", "master", new byte[] {}, "", new CommitMetaData("user", "mail", "msg")))
                            .getCause().getClass());
        }
    }

    private void addFilesAndPush(final File localGitDir, Git git) throws Exception {
        final Path file = localGitDir.toPath().resolve(STORE);
        final Path mfile = localGitDir.toPath().resolve(STORE + METADATA);
        Files.write(file, getData().getBytes(UTF_8), CREATE_NEW, TRUNCATE_EXISTING);
        Files.write(mfile, getMetaData().getBytes(UTF_8), CREATE_NEW, TRUNCATE_EXISTING);
        git.add().addFilepattern(".").call();
        git.commit().setMessage("Test commit").call();
        // This works since there's no check done on the repository
        verifyOkPush(git.push().call());
    }

    @Test
    public void testClosedRepositoryAndInputStream() throws Exception {
        try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER);) {
            final File localGitDir = getFolder().toFile();
            try (Git git = Git.cloneRepository().setURI(grm.repositoryURI().toString()).setDirectory(localGitDir).call()) {
                addFilesAndPush(localGitDir, git);
                SourceInfo sourceInfo = grm.getSourceInfo(STORE, REF_HEADS_MASTER);
                try (InputStream is = sourceInfo.getSourceProvider().getInputStream()) {
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
            try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER, reporter);) {
                reporter.setFault(npe);
                grm.checkHealth();
            }
        }).getCause());
    }

    @Test
    public void testModifyKey() throws Exception {
        CommitMetaData cmd = new CommitMetaData("user", "mail", "msg");
        File gitFolder = getFolder().toFile();
        try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER);
                Git git = Git.cloneRepository().setURI(tempDir.toUri().toString()).setDirectory(gitFolder).call()) {
            addFilesAndPush(gitFolder, git);
            SourceInfo firstSourceInfo = grm.getSourceInfo(STORE, null);
            assertNotNull(firstSourceInfo);
            JsonNode firstValue = readJsonData(firstSourceInfo);
            String firstVersion = firstSourceInfo.getSourceVersion();
            byte[] modified = "{\"one\":\"two\"}".getBytes(UTF_8);
            Pair<String, ThrowingSupplier<ObjectLoader, IOException>> newVersion = grm.modifyKey(STORE, null, modified, firstVersion, cmd);
            assertNotEquals(firstVersion, newVersion.getLeft());
            SourceInfo secondSourceInfo = grm.getSourceInfo(STORE, null);
            JsonNode secondValue = readJsonData(secondSourceInfo);
            assertNotEquals(firstValue, secondValue);
            assertEquals(newVersion.getLeft(), secondSourceInfo.getSourceVersion());
            git.pull().call();
            RevCommit revCommit = getRevCommit(git.getRepository(), REF_HEADS_MASTER);
            assertEquals(cmd.getMessage(), revCommit.getShortMessage());
            assertEquals(cmd.getUserInfo(), revCommit.getAuthorIdent().getName());
            assertEquals("JitStatic API update key operation", revCommit.getCommitterIdent().getName());
        }
    }

    @Test
    public void testModifyTag() throws CorruptedSourceException, IOException {
        assertThat(assertThrows(UnsupportedOperationException.class, () -> {
            try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER);) {
                grm.modifyKey("key", "refs/tags/tag", new byte[] { 1, 2, 3, 4 }, "1", new CommitMetaData("user", "mail", "msg"));
            }
        }).getLocalizedMessage(), CoreMatchers.containsString("Tags cannot be modified"));
    }

    @Test
    public void testAddKey() throws Exception {
        File localGitDir = getFolder().toFile();
        try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER);
                Git git = Git.cloneRepository().setURI(tempDir.toUri().toString()).setDirectory(localGitDir).call()) {
            addFilesAndPush(localGitDir, git);
            Pair<Pair<ThrowingSupplier<ObjectLoader, IOException>, String>, String> addKey = grm.addKey("key", REF_HEADS_MASTER, new byte[] { 1 },
                    new MetaData(new HashSet<>(), null, false, false, List.of(), null, null), new CommitMetaData("user", "mail", "msg"));
            String version = addKey.getLeft().getRight();
            assertNotNull(version);
            SourceInfo sourceInfo = grm.getSourceInfo("key", REF_HEADS_MASTER);
            assertNotNull(sourceInfo);
            assertEquals(version, sourceInfo.getSourceVersion());
            try (InputStream is = sourceInfo.getSourceProvider().getInputStream()) {
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
                grm.addKey("key", REF_HEADS_MASTER, new byte[] { 1 }, new MetaData(new HashSet<>(), null, false, false, List.of(), null, null),
                        new CommitMetaData("user", "mail", "msg"));
            }
        }).getCause(), isA(RefNotFoundException.class));
    }

    @Test
    public void testModifyMetaData() throws Exception {
        CommitMetaData cmd = new CommitMetaData("user", "mail", "msg");
        File gitFolder = getFolder().toFile();
        try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER);
                Git git = Git.cloneRepository().setURI(tempDir.toUri().toString()).setDirectory(gitFolder).call()) {
            addFilesAndPush(gitFolder, git);
            SourceInfo firstSourceInfo = grm.getSourceInfo(STORE, null);
            assertNotNull(firstSourceInfo);
            MetaData firstValue;
            try (InputStream is = firstSourceInfo.getMetadataInputStream()) {
                firstValue = MAPPER.readValue(is, MetaData.class);
            }

            String firstVersion = firstSourceInfo.getMetaDataVersion();
            MetaData newData = new MetaData(new HashSet<>(), "newcontent", false, false, List.of(), null, null);
            String newVersion = grm.modifyMetadata(newData, firstVersion, STORE, null, cmd);
            assertNotNull(newVersion);
            assertNotEquals(firstVersion, newVersion);
            SourceInfo secondSourceInfo = grm.getSourceInfo(STORE, null);
            MetaData secondValue;
            try (InputStream is = secondSourceInfo.getMetadataInputStream()) {
                secondValue = MAPPER.readValue(is, MetaData.class);
            }
            assertNotEquals(firstValue, secondValue);
            assertEquals(newVersion, secondSourceInfo.getMetaDataVersion());
            assertEquals(firstSourceInfo.getSourceVersion(), secondSourceInfo.getSourceVersion());
            git.pull().call();
            RevCommit revCommit = getRevCommit(git.getRepository(), REF_HEADS_MASTER);
            assertEquals(cmd.getMessage(), revCommit.getShortMessage());
            assertEquals(cmd.getUserInfo(), revCommit.getAuthorIdent().getName());
            assertEquals("JitStatic API update metadata operation", revCommit.getCommitterIdent().getName());
        }
    }

    @Test
    public void testAddKeyButKeyAlreadyExist() throws Exception {
        File gitFolder = getFolder().toFile();
        try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER);
                Git git = Git.cloneRepository().setURI(tempDir.toUri().toString()).setDirectory(gitFolder).call()) {
            addFilesAndPush(gitFolder, git);
            MetaData secondValue;
            try (InputStream is = grm.getSourceInfo(STORE, REF_HEADS_MASTER).getMetadataInputStream()) {
                secondValue = MAPPER.readValue(is, MetaData.class);
            }
            assertTrue(assertThrows(WrappingAPIException.class, () -> {
                grm.addKey(STORE, REF_HEADS_MASTER, new byte[] { 1 }, secondValue, new CommitMetaData("user", "mail", "msg"));
            }).getCause() instanceof KeyAlreadyExist);
        }
    }

    @Test
    public void testModifyMetadataWithTag() throws CorruptedSourceException, IOException {
        try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER)) {
            assertThrows(UnsupportedOperationException.class,
                    () -> grm.modifyMetadata(new MetaData(Set.of(), "", false, false, List.of(), null, null), "1", STORE,
                            "refs/tags/tag", new CommitMetaData("user", "mail", "msg")));
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
            grm.deleteKey(STORE, null, new CommitMetaData("user", "mail", "msg"));
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
        File gitFolder = getFolder().toFile();
        try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER);
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
        File gitFolder = getFolder().toFile();
        try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER);
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
            grm.deleteKey(STORE, null, new CommitMetaData("user", "mail", "msg"));
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
            Files.write(file, getData().getBytes(UTF_8), CREATE_NEW, TRUNCATE_EXISTING);
            Files.write(mfile, getMetaData().getBytes(UTF_8), CREATE_NEW, TRUNCATE_EXISTING);
            git.add().addFilepattern(".").call();
            git.commit().setMessage("Test commit").call();
            verifyOkPush(git.push().call());

            SourceInfo sourceInfo = grm.getSourceInfo(base + STORE, null);
            assertNotNull(sourceInfo);
            SourceInfo sourceInfoMasterMetaData = grm.getSourceInfo(base, null);
            assertTrue(sourceInfoMasterMetaData.isMetaDataSource() && !sourceInfoMasterMetaData.hasKeyMetaData());
            grm.deleteKey(base + STORE, null, new CommitMetaData("user", "mail", "msg"));
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

    @Test
    public void testCreateAndDeleteRef() throws Exception {
        File gitFolder = getFolder().toFile();
        String branch = "refs/heads/test";
        try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER);
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
        File gitFolder = getFolder().toFile();
        try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER);
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
        File gitFolder = getFolder().toFile();
        try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER);
                Git git = Git.cloneRepository().setURI(tempDir.toUri().toString()).setDirectory(gitFolder).call()) {
            addFilesAndPush(gitFolder, git);
            assertThrows(RefNotFoundException.class, () -> grm.getList("store/", "refs/heads/notexisting", false));
        }
    }

    @Test
    public void testHostedGitRepositoryWithUsers() throws Exception {
        File base = createTempFiles();
        setupGitRepoWithUsers(base);
        try (HostedGitRepositoryManager hgrm = new HostedGitRepositoryManager(base.toPath(), ENDPOINT, REF_HEADS_MASTER)) {

        }
    }

    private void setupGitRepoWithUsers(File base)
            throws IOException, GitAPIException, InvalidRemoteException, TransportException, JsonProcessingException, NoFilepatternException {
        File wBase = createTempFiles();
        Git bareGit = Git.init().setBare(true).setDirectory(base).call();
        Git workingGit = Git.cloneRepository().setURI(bareGit.getRepository().getDirectory().toURI().toString()).setDirectory(wBase).call();

        Path users = wBase.toPath().resolve(JitStaticConstants.USERS);
        assertTrue(users.toFile().mkdirs());
        Path gitRealm = users.resolve(GIT_REALM);

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

        UserData gitUserData = new UserData(Set.of(new Role("pull"), new Role("push"), new Role("forcepush")), "1234"); // Full admin rights
        UserData creatorUserData = new UserData(Set.of(new Role("files")), "2345");
        UserData updaterUserData = new UserData(Set.of(new Role("files")), "3456");

        UserData sgitUserData = new UserData(Set.of(new Role("pull"), new Role("push"), new Role("forcepush")), "s1234"); // Full admin rights
        UserData screatorUserData = new UserData(Set.of(new Role("files")), "s2345");
        UserData supdaterUserData = new UserData(Set.of(new Role("files")), "s3456");

        write(gituser, gitUserData);
        write(creatorUser, creatorUserData);
        write(updaterUser, updaterUserData);

        write(sgituser, sgitUserData);
        write(screatorUser, screatorUserData);
        write(supdaterUser, supdaterUserData);

        commit(workingGit);
    }

    @Test
    public void testGetUser()
            throws IOException, CorruptedSourceException, InvalidRemoteException, TransportException, NoFilepatternException, GitAPIException {
        File base = createTempFiles();
        setupGitRepoWithUsers(base);
        try (HostedGitRepositoryManager hgrm = new HostedGitRepositoryManager(base.toPath(), ENDPOINT, REF_HEADS_MASTER)) {
            Pair<String, UserData> userDataHolder = hgrm.getUser(".users/git/gituser", REF_HEADS_MASTER);
            assertTrue(userDataHolder.isPresent());
            assertEquals("1234", userDataHolder.getRight().getBasicPassword());
        }
    }

    @Test
    public void testGetUserNotFound()
            throws IOException, CorruptedSourceException, InvalidRemoteException, TransportException, NoFilepatternException, GitAPIException {
        File base = createTempFiles();
        setupGitRepoWithUsers(base);
        try (HostedGitRepositoryManager hgrm = new HostedGitRepositoryManager(base.toPath(), ENDPOINT, REF_HEADS_MASTER)) {
            assertFalse(hgrm.getUser(".users/git/kit", REF_HEADS_MASTER).isPresent());
        }
    }

    @Test
    public void testAddUser()
            throws IOException, CorruptedSourceException, InvalidRemoteException, TransportException, NoFilepatternException, GitAPIException {
        File base = createTempFiles();
        setupGitRepoWithUsers(base);
        try (HostedGitRepositoryManager hgrm = new HostedGitRepositoryManager(base.toPath(), ENDPOINT, REF_HEADS_MASTER)) {
            assertNotNull(hgrm.addUser(".users/git/kit", REF_HEADS_MASTER, "doc", new UserData(Set.of(new Role("role")), "1234")));
        }
    }

    @Test
    public void testUpdateUser()
            throws IOException, CorruptedSourceException, InvalidRemoteException, TransportException, NoFilepatternException, GitAPIException {
        File base = createTempFiles();
        setupGitRepoWithUsers(base);
        try (HostedGitRepositoryManager hgrm = new HostedGitRepositoryManager(base.toPath(), ENDPOINT, REF_HEADS_MASTER)) {
            assertNotNull(hgrm.updateUser(".users/git/kit", REF_HEADS_MASTER, "doc", new UserData(Set.of(new Role("role")), "1234")));
        }
    }

    @Test
    public void testMountOnCorruptUser()
            throws InvalidRemoteException, TransportException, JsonProcessingException, NoFilepatternException, IOException, GitAPIException {
        File base = createTempFiles();
        setupGitRepoWithUsers(base);
        File wBase = createTempFiles();
        Git workingGit = Git.cloneRepository().setURI(base.getAbsolutePath()).setDirectory(wBase).call();

        Path users = wBase.toPath().resolve(JitStaticConstants.USERS);
        Path creatorRealm = users.resolve(JITSTATIC_KEYADMIN_REALM);
        Files.write(creatorRealm.resolve("error"), new byte[] { 1, 2, 4 }, StandardOpenOption.CREATE_NEW);
        commit(workingGit);
        System.out.println(assertThrows(CorruptedSourceException.class, () -> new HostedGitRepositoryManager(base.toPath(), ENDPOINT, REF_HEADS_MASTER)));
    }

    public void write(Path userPath, UserData userData) throws IOException, JsonProcessingException {
        Files.write(userPath, MAPPER.writeValueAsBytes(userData), StandardOpenOption.CREATE);
    }

    private void commit(Git workingGit) throws NoFilepatternException, GitAPIException {
        workingGit.add().addFilepattern(".").call();
        workingGit.commit().setMessage("Initial commit").call();
        workingGit.push().call();
    }

    private File createTempFiles() throws IOException {
        return tmpFolder.createTemporaryDirectory();
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
        try (InputStream is = sourceInfo.getSourceProvider().getInputStream()) {
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

    private void mkdirs(Path... realms) {
        for (Path realm : realms) {
            assertTrue(realm.toFile().mkdirs());
        }
    }

}
