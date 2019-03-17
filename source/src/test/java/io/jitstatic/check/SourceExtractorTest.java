package io.jitstatic.check;

import static io.jitstatic.JitStaticConstants.USERS;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Scanner;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.NoFilepatternException;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefDatabase;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.RemoteRefUpdate.Status;
import org.hamcrest.CoreMatchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import io.jitstatic.JitStaticConstants;
import io.jitstatic.source.SourceInfo;
import io.jitstatic.test.TemporaryFolder;
import io.jitstatic.test.TemporaryFolderExtension;
import io.jitstatic.utils.Pair;

@ExtendWith(TemporaryFolderExtension.class)
public class SourceExtractorTest {

    private static final Charset UTF_8 = StandardCharsets.UTF_8;
    private static final String REFS_HEADS_MASTER = "refs/heads/master";
    private static final String METADATA = ".metadata";
    private Git git;
    private TemporaryFolder tmpFolder;
    private File workingFolder;

    @BeforeEach
    public void setup() throws IllegalStateException, GitAPIException, IOException {
        workingFolder = getFolder();
        git = Git.init().setBare(true).setDirectory(workingFolder).call();
    }

    @AfterEach
    public void tearDown() {
        if (git != null) {
            git.close();
        }
    }

    @Test
    public void testCheckRepositoryUniqueBlobs() throws Exception {
        File tempGitFolder = getFolder();
        try (Git local = Git.cloneRepository().setURI(workingFolder.toURI().toString()).setDirectory(tempGitFolder).call()) {
            final String key = "file";
            for (int i = 0; i < 4; i++) {
                createFileAndCommitOnDifferentBranches(tempGitFolder, local, key, i);
            }
            createFileAndCommitOnDifferentBranches(tempGitFolder, local, "." + key, 0);
            SourceExtractor se = new SourceExtractor(git.getRepository());
            Map<Pair<AnyObjectId, Set<Ref>>, List<BranchData>> allExtracted = se.extractAll();
            assertTrue(allExtracted.keySet().size() == 4);
            checkForErrors();
        }
    }

    @Test
    public void testCheckRepositorySameBlobs() throws IOException, NoFilepatternException, GitAPIException {
        File tempGitFolder = getFolder();
        try (Git local = Git.cloneRepository().setURI(workingFolder.toURI().toString()).setDirectory(tempGitFolder).call()) {
            final String key = "file";
            for (int i = 0; i < 4; i++) {
                final String round = (i == 0 ? "" : String.valueOf(i));
                final String branchName = "master" + round;
                if (i > 0) {
                    local.checkout().setCreateBranch(true).setName(branchName).call();
                }
                String fileName = key + round;
                addFilesAndPush(fileName, tempGitFolder, local);
            }

            SourceExtractor se = new SourceExtractor(git.getRepository());
            checkForErrors();

            Map<Pair<AnyObjectId, Set<Ref>>, List<BranchData>> allExtracted = se.extractAll();
            assertTrue(allExtracted.keySet().size() == 4);

            Optional<Pair<ObjectId, Set<String>>> reduce = allExtracted.values().stream().flatMap(l -> l.stream()).flatMap(r -> {
                Stream<Pair<MetaFileData, SourceFileData>> stream = r.pair().stream();
                return stream;
            }).map(p -> {
                SourceFileData sdf = p.getRight();
                return Pair.of(sdf.getFileInfo(), sdf.getInputStreamHolder());
            }).map(m -> Pair.<ObjectId, Set<String>>of(m.getLeft().getObjectId(), new HashSet<>(Arrays.asList(m.getLeft().getFileName())))).reduce((a, b) -> {
                assertEquals(a.getLeft(), b.getLeft());
                a.getRight().addAll(b.getRight());
                return a;
            });
            assertTrue(reduce.isPresent());
            assertTrue(reduce.get().getRight().size() == 4);
        }
    }

    // This shouldn't happen since it would be illegal to push this commit
    @Test
    public void testCheckBranchWithRemovedObjects() throws IOException, NoFilepatternException, GitAPIException {
        final String key = "file";
        final File tempGitFolder = getFolder();
        try (final Git local = Git.cloneRepository().setURI(workingFolder.toURI().toString()).setDirectory(tempGitFolder).call()) {
            addFilesAndPush(key, tempGitFolder, local);
            final SourceExtractor se = new SourceExtractor(git.getRepository());
            Pair<Pair<AnyObjectId, Set<Ref>>, List<BranchData>> sourceBranchExtractor = se.sourceBranchExtractor(REFS_HEADS_MASTER);
            assertEquals(1, sourceBranchExtractor.getRight().size());
            BranchData p = sourceBranchExtractor.getRight().get(0);
            List<Pair<MetaFileData, SourceFileData>> pair = p.pair();
            assertEquals(1, pair.size());
            Pair<MetaFileData, SourceFileData> fileData = pair.get(0);
            MetaFileData left = fileData.getLeft();
            assertNotNull(left);
            assertEquals(key + METADATA, left.getFileInfo().getFileName());
            SourceFileData right = fileData.getRight();
            assertNotNull(right);
            assertEquals(key, right.getFileInfo().getFileName());

            local.rm().addFilepattern(key).call();
            local.add().addFilepattern(key).call();
            local.commit().setMessage("Removed file").call();
            local.push().call();

            sourceBranchExtractor = se.sourceBranchExtractor(REFS_HEADS_MASTER);
            BranchData first = sourceBranchExtractor.getRight().get(0);
            Pair<MetaFileData, SourceFileData> firstPair = first.getFirstPair();
            assertFalse(firstPair.isPresent());
            assertNull(firstPair.getLeft());
        }
    }

    @Test
    public void testReadASingleFileFromMaster() throws Exception {
        final String key = "file";
        File temporaryGitFolder = getFolder();
        try (Git local = Git.cloneRepository().setURI(workingFolder.toURI().toString()).setDirectory(temporaryGitFolder).call()) {
            addFilesAndPush(key, temporaryGitFolder, local);
        }
        SourceExtractor se = new SourceExtractor(git.getRepository());
        SourceInfo branch = se.openBranch(REFS_HEADS_MASTER, key);
        try (final BufferedReader is = new BufferedReader(new InputStreamReader(branch.getSourceProvider().getInputStream(), UTF_8));) {
            assertNotNull(is);
            String parsed = is.lines().collect(Collectors.joining());
            assertEquals(getData(), parsed);
        }

    }

    @Test
    public void testExtractNullTag() throws Exception {
        assertThrows(NullPointerException.class, () -> {
            SourceExtractor se = new SourceExtractor(git.getRepository());
            se.openTag(null, "file");
        });
    }

    @Test
    public void testExtractNoTag() throws Exception {
        assertThrows(RefNotFoundException.class, () -> {
            SourceExtractor se = new SourceExtractor(git.getRepository());
            se.openTag("tag", "file");
        });
    }

    @Test
    public void testExtractTag() throws Exception {
        final String key = "file";
        File temporaryGitFolder = getFolder();
        try (Git local = Git.cloneRepository().setURI(workingFolder.toURI().toString()).setDirectory(temporaryGitFolder).call()) {
            addFilesAndPush(key, temporaryGitFolder, local);
            local.tag().setName("tag").call();
            local.push().setPushTags().call();
        }
        SourceExtractor se = new SourceExtractor(git.getRepository());
        SourceInfo tag = se.openTag(Constants.R_TAGS + "tag", key);
        try (final BufferedReader is = new BufferedReader(new InputStreamReader(tag.getSourceProvider().getInputStream(), UTF_8));) {
            assertNotNull(is);
            String parsed = is.lines().collect(Collectors.joining());
            assertEquals(getData(), parsed);
        }
    }

    @Test
    public void testRefNotPresent() {
        assertThrows(RefNotFoundException.class, () -> {
            final String key = "file";
            File temporaryGitFolder = getFolder();
            try (Git local = Git.cloneRepository().setURI(workingFolder.toURI().toString()).setDirectory(temporaryGitFolder).call()) {
                final Path file = temporaryGitFolder.toPath().resolve(key);
                Files.write(file, getData().getBytes(UTF_8), CREATE_NEW, TRUNCATE_EXISTING);
                local.add().addFilepattern(key).call();
                local.commit().setMessage("Commit " + file.toString()).call();
                local.push().call();
            }
            SourceExtractor se = new SourceExtractor(git.getRepository());
            SourceInfo branch = se.openBranch(Constants.R_HEADS + "notexisting", key);
            try (final BufferedReader is = new BufferedReader(new InputStreamReader(branch.getSourceProvider().getInputStream(), UTF_8));) {
                assertNotNull(is);
                String parsed = is.lines().collect(Collectors.joining());
                assertEquals(getData(), parsed);
            }
        });
    }

    @Test
    public void testOpeningRepositoryFails() throws Exception {
        IOException exception = new IOException("Error opening");
        final String key = "file";
        File temporaryGitFolder = getFolder();
        try (Git local = Git.cloneRepository().setURI(workingFolder.toURI().toString()).setDirectory(temporaryGitFolder).call()) {
            addFilesAndPush(key, temporaryGitFolder, local);
            local.tag().setName("tag").call();
            local.push().setPushTags().call();

            Repository spy = Mockito.spy(local.getRepository());
            Mockito.doThrow(exception).when(spy).open(Mockito.any());
            SourceExtractor se = new SourceExtractor(spy);
            Pair<Pair<AnyObjectId, Set<Ref>>, List<BranchData>> branch = se.sourceBranchExtractor(REFS_HEADS_MASTER);
            assertEquals(REFS_HEADS_MASTER, branch.getLeft().getRight().stream().findFirst().get().getName());
            List<BranchData> branchData = branch.getRight();
            assertTrue(branchData.size() == 1);
            BranchData onlyBranchData = branchData.stream().findFirst().get();
            Exception actual = onlyBranchData.getFirstPair().getRight().getInputStreamHolder().exception();
            assertSame(exception, actual);
        }
    }

    @Test
    public void testSourceTestBranchExtractorNullBranch() throws RefNotFoundException, IOException {
        assertThrows(NullPointerException.class, () -> {
            Repository repository = Mockito.mock(Repository.class);
            SourceExtractor se = new SourceExtractor(repository);
            se.sourceTestBranchExtractor(null);
        });
    }

    @Test
    public void testSourceTestBranchExtractorBranchNotFound() throws RefNotFoundException, IOException {
        assertThrows(RefNotFoundException.class, () -> {
            Repository repository = Mockito.mock(Repository.class);
            SourceExtractor se = new SourceExtractor(repository);
            se.sourceTestBranchExtractor("notfound");
        });
    }

    @Test
    public void testSourceTestBranchExtractorWrongRefName() {
        final String branch = JitStaticConstants.REFS_JITSTATIC + "something";
        assertThat(assertThrows(RefNotFoundException.class, () -> {
            final String key = "file";
            File temporaryGitFolder = getFolder();
            try (Git local = Git.cloneRepository().setURI(workingFolder.toURI().toString()).setDirectory(temporaryGitFolder).call()) {
                addFilesAndPush(key, temporaryGitFolder, local);
                SourceExtractor se = new SourceExtractor(local.getRepository());
                se.sourceTestBranchExtractor(branch);
            }
        }).getLocalizedMessage(), CoreMatchers.containsString(branch));
    }

    @Test
    public void testSourceBranchExtractorNullBranch() throws RefNotFoundException, IOException {
        assertThrows(NullPointerException.class, () -> {
            Repository repository = Mockito.mock(Repository.class);
            SourceExtractor se = new SourceExtractor(repository);
            se.sourceBranchExtractor(null);
        });
    }

    @Test
    public void testSourceBranchExtractorBranchNotFound() throws RefNotFoundException, IOException {
        assertThrows(RefNotFoundException.class, () -> {
            Repository repository = Mockito.mock(Repository.class);
            SourceExtractor se = new SourceExtractor(repository);
            se.sourceBranchExtractor("notfound");
        });
    }

    @Test
    public void testExtractNullBranch() {
        assertThrows(NullPointerException.class, () -> {
            SourceExtractor se = new SourceExtractor(git.getRepository());
            se.openBranch(null, null);
        });
    }

    @Test
    public void testExtractNoBranch() {
        assertThrows(RefNotFoundException.class, () -> {
            SourceExtractor se = new SourceExtractor(git.getRepository());
            se.openBranch("branch", null);
        });
    }

    @Test
    public void testCheckNotExistingFile() throws IOException, InvalidRemoteException, TransportException, GitAPIException {
        final String key = "file";
        final File tempGitFolder = getFolder();
        try (final Git local = Git.cloneRepository().setURI(workingFolder.toURI().toString()).setDirectory(tempGitFolder).call()) {
            final Path file = tempGitFolder.toPath().resolve(key);
            Files.write(file, getData().getBytes(UTF_8), CREATE_NEW, TRUNCATE_EXISTING);
            local.add().addFilepattern(key).call();
            local.commit().setMessage("Init commit").call();
            local.push().call();
        }
        SourceExtractor se = new SourceExtractor(git.getRepository());
        assertNull(se.openBranch(REFS_HEADS_MASTER, "notexisting"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "key", "base/key" })
    public void testMasterMetaData(String key) throws IOException, InvalidRemoteException, TransportException, GitAPIException {
        final File tempGitFolder = getFolder();
        try (final Git local = Git.cloneRepository().setURI(workingFolder.toURI().toString()).setDirectory(tempGitFolder).call()) {
            final Path file = tempGitFolder.toPath().resolve(key);
            Path metadata = Objects.requireNonNull(file.getParent()).resolve(METADATA);
            if (key.contains("/")) {
                assertTrue(Objects.requireNonNull(file.getParent()).toFile().mkdirs());
            }
            Files.write(file, getData().getBytes(UTF_8), CREATE_NEW, TRUNCATE_EXISTING);
            Files.write(metadata, getMetaData().getBytes(UTF_8), CREATE_NEW, TRUNCATE_EXISTING);
            local.add().addFilepattern(".").call();
            local.commit().setMessage("Init commit").call();
            local.push().call();
        }
        SourceExtractor se = new SourceExtractor(git.getRepository());
        SourceInfo openBranch = se.openBranch(REFS_HEADS_MASTER, key);
        assertNotNull(openBranch);
        try (InputStream is = openBranch.getMetadataInputStream()) {
            assertNotNull(is);
        }
        try (InputStream is = openBranch.getSourceProvider().getInputStream()) {
            assertNotNull(is);
        }
    }

    @Test
    public void testMasterMetaDataOverride() throws IOException, InvalidRemoteException, TransportException, GitAPIException {
        String key1 = "key1";
        String key2 = "key2";
        final File tempGitFolder = getFolder();
        try (final Git local = Git.cloneRepository().setURI(workingFolder.toURI().toString()).setDirectory(tempGitFolder).call()) {
            Path file = tempGitFolder.toPath().resolve(key1);
            Objects.requireNonNull(file.getParent()).toFile().mkdirs();
            Path metadata = Objects.requireNonNull(file.getParent()).resolve(METADATA);
            Files.write(file, getData().getBytes(UTF_8), CREATE_NEW, TRUNCATE_EXISTING);
            Files.write(metadata, getMetaData().getBytes(UTF_8), CREATE_NEW, TRUNCATE_EXISTING);

            Path file2 = tempGitFolder.toPath().resolve(key2);
            Path metadata2 = file2.resolveSibling(key2 + METADATA);
            Files.write(file2, getData(2).getBytes(UTF_8), CREATE_NEW, TRUNCATE_EXISTING);
            Files.write(metadata2, getMetaData(2).getBytes(UTF_8), CREATE_NEW, TRUNCATE_EXISTING);

            local.add().addFilepattern(".").call();
            local.commit().setMessage("Init commit").call();
            local.push().call();
        }
        SourceExtractor se = new SourceExtractor(git.getRepository());
        SourceInfo keydata = se.openBranch(REFS_HEADS_MASTER, key1);
        assertNotNull(keydata);
        assertEquals(getData(), readData(keydata.getSourceProvider().getInputStream()));
        assertEquals(getMetaData(), readData(keydata.getMetadataInputStream()));
        SourceInfo keydata2 = se.openBranch(REFS_HEADS_MASTER, key2);
        assertNotNull(keydata2);
        assertEquals(getData(2), readData(keydata2.getSourceProvider().getInputStream()));
        assertEquals(getMetaData(2), readData(keydata2.getMetadataInputStream()));
    }

    @Test
    public void testMetaDataOverrideWithHiddenUsers() throws Exception {
        String key1 = "key1";
        String key2 = "key2";
        final File tempGitFolder = getFolder();
        try (final Git local = Git.cloneRepository().setURI(workingFolder.toURI().toString()).setDirectory(tempGitFolder).call()) {
            Path file = tempGitFolder.toPath().resolve(key1);
            Objects.requireNonNull(file.getParent()).toFile().mkdirs();

            // key1 + .metadata
            Path metadata = Objects.requireNonNull(file.getParent()).resolve(METADATA);
            Files.write(file, getData().getBytes(UTF_8), CREATE_NEW, TRUNCATE_EXISTING);
            Files.write(metadata, getMetaData().getBytes(UTF_8), CREATE_NEW, TRUNCATE_EXISTING);

            // key2 + key2.metadata
            Path file2 = tempGitFolder.toPath().resolve(key2);
            Path metadata2 = file2.resolveSibling(key2 + METADATA);
            Files.write(file2, getData(2).getBytes(UTF_8), CREATE_NEW, TRUNCATE_EXISTING);
            Files.write(metadata2, getMetaData(2).getBytes(UTF_8), CREATE_NEW, TRUNCATE_EXISTING);

            // .users/ + .users/key1 + ./users/key1.metadata
            Path users = tempGitFolder.toPath().resolve(".users");
            assertTrue(users.toFile().mkdirs());
            Path usersKey = users.resolve(key1);
            Files.write(usersKey, getData(3).getBytes(UTF_8), CREATE_NEW, TRUNCATE_EXISTING);
            Files.write(users.resolve(key1 + METADATA), getMetaData(3).getBytes(UTF_8), CREATE_NEW, TRUNCATE_EXISTING);

            // somedir/.users + somedir/.users/key1 + somedir/.users/key1.metadata
            Path someDir = tempGitFolder.toPath().resolve("somedir").resolve(".users");
            assertTrue(someDir.toFile().mkdirs());
            Path someKey = someDir.resolve(key1);
            Files.write(someKey, getData(4).getBytes(UTF_8), CREATE_NEW, TRUNCATE_EXISTING);
            Files.write(someDir.resolve(key1 + METADATA), getMetaData(4).getBytes(UTF_8), CREATE_NEW, TRUNCATE_EXISTING);

            local.add().addFilepattern(".").call();
            local.commit().setMessage("Init commit").call();
            local.push().call();
        }
        SourceExtractor se = new SourceExtractor(git.getRepository());
        SourceInfo keydata = se.openBranch(REFS_HEADS_MASTER, key1);
        assertNotNull(keydata);
        assertEquals(getData(), readData(keydata.getSourceProvider().getInputStream()));
        assertEquals(getMetaData(), readData(keydata.getMetadataInputStream()));
        SourceInfo keydata2 = se.openBranch(REFS_HEADS_MASTER, key2);
        assertNotNull(keydata2);
        assertEquals(getData(2), readData(keydata2.getSourceProvider().getInputStream()));
        assertEquals(getMetaData(2), readData(keydata2.getMetadataInputStream()));
        SourceInfo usersKeydata = se.openBranch(REFS_HEADS_MASTER, ".users/key1");
        assertNull(usersKeydata);
        SourceInfo somedirKey = se.openBranch(REFS_HEADS_MASTER, "somedir/.users/key1");
        assertNotNull(somedirKey);
        assertEquals(getData(4), readData(somedirKey.getSourceProvider().getInputStream()));
        assertEquals(getMetaData(4), readData(somedirKey.getMetadataInputStream()));
    }

    @ParameterizedTest
    @ValueSource(strings = { "key", "base/key" })
    public void testOrdinaryKeys(String key) throws Exception {
        File temporaryGitFolder = getFolder();
        try (Git local = Git.cloneRepository().setURI(workingFolder.toURI().toString()).setDirectory(temporaryGitFolder).call()) {
            addFilesAndPush(key, temporaryGitFolder, local);
        }
        SourceExtractor se = new SourceExtractor(git.getRepository());
        SourceInfo openBranch = se.openBranch(REFS_HEADS_MASTER, key);
        assertNotNull(openBranch);
        try (InputStream is = openBranch.getMetadataInputStream()) {
            assertNotNull(is);
        }
        try (InputStream is = openBranch.getSourceProvider().getInputStream()) {
            assertNotNull(is);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "key/", "base/key/" })
    public void testGetActualMasterMetaKey(String key) throws IOException, InvalidRemoteException, TransportException, GitAPIException {
        File temporaryGitFolder = getFolder();
        try (Git local = Git.cloneRepository().setURI(workingFolder.toURI().toString()).setDirectory(temporaryGitFolder).call()) {
            addFilesAndPush(key, temporaryGitFolder, local);
        }
        SourceExtractor se = new SourceExtractor(git.getRepository());
        SourceInfo openBranch = se.openBranch(REFS_HEADS_MASTER, key);
        assertNotNull(openBranch);
        try (InputStream is = openBranch.getMetadataInputStream()) {
            assertNotNull(is);
        }
        assertNull(openBranch.getSourceProvider());
    }

    @Test
    public void testHandleRepositoryError() throws Exception {
        IOException e = new IOException("Test");
        Repository repo = Mockito.mock(Repository.class);
        ObjectReader reader = Mockito.mock(ObjectReader.class);
        Ref ref = Mockito.mock(Ref.class);
        RefDatabase refDatabase = Mockito.mock(RefDatabase.class);
        Mockito.when(refDatabase.getRef(Mockito.anyString())).thenReturn(ref);
        Mockito.when(repo.getRefDatabase()).thenReturn(refDatabase);
        Mockito.when(ref.getObjectId()).thenReturn(ObjectId.zeroId());
        Mockito.when(repo.newObjectReader()).thenReturn(reader);
        Mockito.when(reader.open(Mockito.any())).thenThrow(e);
        SourceExtractor se = new SourceExtractor(repo);
        Pair<Pair<AnyObjectId, Set<Ref>>, List<BranchData>> sbe = se.sourceBranchExtractor(REFS_HEADS_MASTER);
        List<BranchData> right = sbe.getRight();
        assertTrue(right.size() == 1);
        BranchData branchData = right.get(0);
        RepositoryDataError fileDataError = branchData.getFileDataError();
        assertNotNull(fileDataError);
        assertSame(e, fileDataError.getInputStreamHolder().exception());
        assertSame(e, assertThrows(IOException.class, () -> fileDataError.getInputStream()));
        assertNotNull(fileDataError.getFileObjectIdStore());
    }

    @ParameterizedTest
    @CsvSource({ "/,'key1,key1.metadata,key2,key2.metadata,key3,key3.metadata'",
            "data/,'data/key1,data/key1.metadata,data/key2,data/key2.metadata,data/key3,data/key3.metadata'",
            "data/data/,'data/data/key1,data/data/key1.metadata,data/data/key2,data/data/key2.metadata,data/data/key3,data/data/key3.metadata'",
            "data/data/data/,''" })
    public void testListFiles(String key, String result) throws Exception {
        File temporaryGitFolder = getFolder();
        try (Git local = Git.cloneRepository().setURI(workingFolder.toURI().toString()).setDirectory(temporaryGitFolder).call()) {
            for (String k : List.of("key1", "key2", "key3", "data/key1", "data/key2", "data/key3", "data/data/key1", "data/data/key2", "data/data/key3",
                    "decoy/key1", "decoy/decoy/key1")) {
                addFilesAndPush(k, temporaryGitFolder, local);
            }
        }
        SourceExtractor se = new SourceExtractor(git.getRepository());
        List<String> listForKey = se.getListForKey(key, REFS_HEADS_MASTER, false);
        String[] values = result.isEmpty() ? new String[0] : result.split(",");
        assertEquals(List.of(values), listForKey);
    }

    @Test
    public void testLitUsersFromExtractor() throws Exception {
        String key = USERS;
        File temporaryGitFolder = getFolder();
        try (Git local = Git.cloneRepository().setURI(workingFolder.toURI().toString()).setDirectory(temporaryGitFolder).call()) {
            for (String k : List.of(USERS + "key1", USERS + "key2", "key3", USERS + "data/key1", "data/key2", "data/key3", "data/data/key1", "data/data/key2",
                    "data/data/key3", "decoy/key1", USERS + "decoy/decoy/key1")) {
                addFilesAndPush(k, temporaryGitFolder, local);
            }
        }
        SourceExtractor se = new SourceExtractor(git.getRepository());
        List<String> listForKey = se.getListForKey(key, REFS_HEADS_MASTER, false);
        assertTrue(listForKey.isEmpty());
    }

    @Test
    public void testCheckedInMetaKeyWithoutKey() throws Exception {
        File temporaryGitFolder = getFolder();
        try (Git local = Git.cloneRepository().setURI(workingFolder.toURI().toString()).setDirectory(temporaryGitFolder).call()) {
            addFilesAndPush("key", temporaryGitFolder, local);
            SourceExtractor se = new SourceExtractor(local.getRepository());
            Pair<Pair<AnyObjectId, Set<Ref>>, List<BranchData>> extracted = se.sourceBranchExtractor(REFS_HEADS_MASTER);
            assertTrue(extracted.getRight().stream().map(m -> m.pair()).flatMap(m -> m.stream()).allMatch(p -> p.getKey() != null && p.getRight() != null));
            local.rm().addFilepattern("key").call();
            local.commit().setMessage("removed key").call();
            Pair<Pair<AnyObjectId, Set<Ref>>, List<BranchData>> extracted2 = se.sourceBranchExtractor(REFS_HEADS_MASTER);
            assertTrue(extracted2.getRight().stream().map(m -> m.pair()).flatMap(m -> m.stream()).allMatch(p -> p.getKey() != null && p.getRight() == null));
        }
    }

    private void addFilesAndPush(final String key, File temporaryGitFolder, Git local) throws IOException, NoFilepatternException, GitAPIException {
        final Path file = temporaryGitFolder.toPath().resolve(key);
        final Path mfile = temporaryGitFolder.toPath().resolve(key + METADATA);
        if (key.endsWith("/")) {
            assertTrue(file.toFile().mkdirs());
        } else {
            if (!Files.exists(Objects.requireNonNull(file.getParent()))) {
                assertTrue(Objects.requireNonNull(file.getParent().toFile().mkdirs()));
            }
            Files.write(file, getData().getBytes(UTF_8), StandardOpenOption.CREATE, TRUNCATE_EXISTING);
        }
        Files.write(mfile, getMetaData().getBytes(UTF_8), StandardOpenOption.CREATE, TRUNCATE_EXISTING);
        local.add().addFilepattern(".").call();
        local.commit().setMessage("Commit " + file.toString()).call();        
        verifyOkPush(local.push().call(),local.getRepository().getFullBranch());
    }

    private void verifyOkPush(Iterable<PushResult> iterable, String branch) {
        PushResult pushResult = iterable.iterator().next();
        RemoteRefUpdate remoteUpdate = pushResult.getRemoteUpdate(branch);
        assertEquals(Status.OK, remoteUpdate.getStatus());
    }

    File getFolder() throws IOException {
        return tmpFolder.createTemporaryDirectory();
    }

    private void createFileAndCommitOnDifferentBranches(File tempGitFolder, Git local, final String key, int i) throws Exception {
        final String round = (i == 0 ? "" : String.valueOf(i));
        final String branchName = "master" + round;
        if (i > 0) {
            local.checkout().setCreateBranch(true).setName(branchName).call();
        }
        final String fileName = key + round;
        final Path file = tempGitFolder.toPath().resolve(fileName);
        final Path mfile = tempGitFolder.toPath().resolve(fileName + METADATA);
        Files.write(file, getData(i).getBytes(UTF_8), CREATE_NEW, TRUNCATE_EXISTING);
        Files.write(mfile, getMetaData().getBytes(UTF_8), CREATE_NEW, TRUNCATE_EXISTING);
        local.add().addFilepattern(".").call();
        local.commit().setMessage("Commit " + round).call();
        local.push().call();
    }

    private void checkForErrors() {
        SourceChecker sc = new SourceChecker(git.getRepository());
        List<Pair<Set<Ref>, List<Pair<FileObjectIdStore, Exception>>>> check = sc.check();
        List<Pair<FileObjectIdStore, Exception>> errors = check.stream().map(Pair::getRight).flatMap(List::stream).filter(Pair::isPresent)
                .filter(p -> p.getRight() != null).collect(Collectors.toList());
        assertEquals(Arrays.asList(), errors);
    }

    private String getData() {
        return getData(0);
    }

    private String getMetaData(int i) {
        return "{\"users\":[{\"password\":\"" + i + "234\",\"user\":\"user1\"}]}";
    }

    private String getMetaData() {
        return getMetaData(0);
    }

    private String getData(int i) {
        return "{\"key" + i
                + "\":{\"data\":\"value1\",\"users\":[{\"password\":\"1234\",\"user\":\"user1\"}]},\"mkey3\":{\"data\":\"value3\",\"users\":[{\"password\":\"1234\",\"user\":\"user1\"}]}}";
    }

    private String readData(InputStream data) throws IOException {
        try (data; Scanner s = new Scanner(data, UTF_8)) {
            s.useDelimiter("\\A");
            return s.hasNext() ? s.next() : "";
        }
    }
}
