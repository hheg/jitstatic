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
import java.io.UnsupportedEncodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.AbortedByHookException;
import org.eclipse.jgit.api.errors.CheckoutConflictException;
import org.eclipse.jgit.api.errors.ConcurrentRefUpdateException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRefNameException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.NoFilepatternException;
import org.eclipse.jgit.api.errors.NoHeadException;
import org.eclipse.jgit.api.errors.NoMessageException;
import org.eclipse.jgit.api.errors.RefAlreadyExistsException;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.api.errors.UnmergedPathsException;
import org.eclipse.jgit.api.errors.WrongRepositoryStateException;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.hamcrest.CoreMatchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import io.jitstatic.source.SourceInfo;
import io.jitstatic.utils.Pair;

public class SourceExtractorTest {

    private static final String UTF_8 = "UTF-8";
    private static final String REFS_HEADS_MASTER = "refs/heads/master";
    private static final String METADATA = ".metadata";
    private Git git;

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
    public void testCheckRepositoryUniqueBlobs() throws UnsupportedEncodingException, IOException, NoFilepatternException, GitAPIException {
        File tempGitFolder = getFolder();
        try (Git local = Git.cloneRepository().setURI(workingFolder.toURI().toString()).setDirectory(tempGitFolder).call()) {
            final String key = "file";
            for (int i = 0; i < 4; i++) {
                createFileAndCommit(tempGitFolder, local, key, i);
            }
            createFileAndCommit(tempGitFolder, local, "." + key, 0);
            SourceExtractor se = new SourceExtractor(git.getRepository());
            Map<Pair<AnyObjectId, Set<Ref>>, List<BranchData>> allExtracted = se.extractAll();
            assertTrue(allExtracted.keySet().size() == 4);
            checkForErrors();
        }
    }

    @Test
    public void testCheckRepositorySameBlobs() throws UnsupportedEncodingException, IOException, NoFilepatternException, GitAPIException {
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
    public void testCheckBranchWithRemovedObjects() throws UnsupportedEncodingException, IOException, NoFilepatternException, GitAPIException {
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
            Pair<MetaFileData, SourceFileData> firstPair = first.getFirstPair(null);
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
        try (final InputStream is = branch.getSourceInputStream();) {
            assertNotNull(is);
            String parsed = new BufferedReader(new InputStreamReader(is)).lines().collect(Collectors.joining());
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
        try (final InputStream is = tag.getSourceInputStream();) {
            assertNotNull(is);
            String parsed = new BufferedReader(new InputStreamReader(is)).lines().collect(Collectors.joining());
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
                Files.write(file, getData().getBytes(UTF_8), StandardOpenOption.CREATE_NEW, StandardOpenOption.TRUNCATE_EXISTING);
                local.add().addFilepattern(key).call();
                local.commit().setMessage("Commit " + file.toString()).call();
                local.push().call();
            }
            SourceExtractor se = new SourceExtractor(git.getRepository());
            SourceInfo branch = se.openBranch(Constants.R_HEADS + "notexisting", key);
            try (final InputStream is = branch.getSourceInputStream();) {
                assertNotNull(is);
                String parsed = new BufferedReader(new InputStreamReader(is)).lines().collect(Collectors.joining());
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
            Exception actual = onlyBranchData.getFirstPair(null).getRight().getInputStreamHolder().exception();
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
        final String branch = JitStaticConstants.REFS_JISTSTATIC + "something";
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
            Files.write(file, getData().getBytes(UTF_8), StandardOpenOption.CREATE_NEW, StandardOpenOption.TRUNCATE_EXISTING);
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
            file.getParent().toFile().mkdirs();
            Path metadata = file.getParent().resolve(METADATA);
            Files.write(file, getData().getBytes(UTF_8), StandardOpenOption.CREATE_NEW, StandardOpenOption.TRUNCATE_EXISTING);
            Files.write(metadata, getMetaData().getBytes(UTF_8), StandardOpenOption.CREATE_NEW, StandardOpenOption.TRUNCATE_EXISTING);
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
        try (InputStream is = openBranch.getSourceInputStream()) {
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
            file.getParent().toFile().mkdirs();
            Path metadata = file.getParent().resolve(METADATA);
            Files.write(file, getData().getBytes(UTF_8), StandardOpenOption.CREATE_NEW, StandardOpenOption.TRUNCATE_EXISTING);
            Files.write(metadata, getMetaData().getBytes(UTF_8), StandardOpenOption.CREATE_NEW, StandardOpenOption.TRUNCATE_EXISTING);

            Path file2 = tempGitFolder.toPath().resolve(key2);
            Path metadata2 = file2.resolveSibling(key2 + METADATA);
            Files.write(file2, getData().getBytes(UTF_8), StandardOpenOption.CREATE_NEW, StandardOpenOption.TRUNCATE_EXISTING);
            Files.write(metadata2, getMetaData(2).getBytes(UTF_8), StandardOpenOption.CREATE_NEW, StandardOpenOption.TRUNCATE_EXISTING);

            local.add().addFilepattern(".").call();
            local.commit().setMessage("Init commit").call();
            local.push().call();
        }
        SourceExtractor se = new SourceExtractor(git.getRepository());
        SourceInfo keydata = se.openBranch(REFS_HEADS_MASTER, key1);
        assertNotNull(keydata);
        assertEquals(getMetaData(), readData(keydata));
        SourceInfo keydata2 = se.openBranch(REFS_HEADS_MASTER, key2);
        assertNotNull(keydata2);
        assertEquals(getMetaData(2), readData(keydata2));
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
        try (InputStream is = openBranch.getSourceInputStream()) {
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
        try (InputStream is = openBranch.getSourceInputStream()) {
            assertNull(is);
        }
    }

    private void addFilesAndPush(final String key, File temporaryGitFolder, Git local)
            throws UnsupportedEncodingException, IOException, NoFilepatternException, GitAPIException {
        final Path file = temporaryGitFolder.toPath().resolve(key);
        final Path mfile = temporaryGitFolder.toPath().resolve(key + METADATA);
        if (key.endsWith("/")) {
            file.toFile().mkdirs();
        } else {
            file.getParent().toFile().mkdirs();
            Files.write(file, getData().getBytes(UTF_8), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }
        Files.write(mfile, getMetaData().getBytes(UTF_8), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        local.add().addFilepattern(".").call();
        local.commit().setMessage("Commit " + file.toString()).call();
        local.push().call();
    }

    File getFolder() throws IOException {
        return Files.createTempDirectory("junit").toFile();
    }

    private void createFileAndCommit(File tempGitFolder, Git local, final String key, int i)
            throws GitAPIException, RefAlreadyExistsException, RefNotFoundException, InvalidRefNameException, CheckoutConflictException, IOException,
            UnsupportedEncodingException, NoFilepatternException, NoHeadException, NoMessageException, UnmergedPathsException, ConcurrentRefUpdateException,
            WrongRepositoryStateException, AbortedByHookException, InvalidRemoteException, TransportException {
        final String round = (i == 0 ? "" : String.valueOf(i));
        final String branchName = "master" + round;
        if (i > 0) {
            local.checkout().setCreateBranch(true).setName(branchName).call();
        }
        final String fileName = key + round;
        final Path file = tempGitFolder.toPath().resolve(fileName);
        final Path mfile = tempGitFolder.toPath().resolve(fileName + METADATA);
        Files.write(file, getData(i).getBytes(UTF_8), StandardOpenOption.CREATE_NEW, StandardOpenOption.TRUNCATE_EXISTING);
        Files.write(mfile, getMetaData().getBytes(UTF_8), StandardOpenOption.CREATE_NEW, StandardOpenOption.TRUNCATE_EXISTING);
        local.add().addFilepattern(".").call();
        local.commit().setMessage("Commit " + round).call();
        local.push().call();
    }

    private void checkForErrors() {
        try (SourceChecker sc = new SourceChecker(git.getRepository())) {
            List<Pair<Set<Ref>, List<Pair<FileObjectIdStore, Exception>>>> check = sc.check();
            List<Pair<FileObjectIdStore, Exception>> errors = check.stream().map(Pair::getRight).flatMap(List::stream).filter(Pair::isPresent)
                    .filter(p -> p.getRight() != null).collect(Collectors.toList());
            assertEquals(Arrays.asList(), errors);
        }
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

    private String readData(SourceInfo openBranch) throws IOException {
        try (InputStream is = openBranch.getMetadataInputStream(); Scanner s = new Scanner(is)) {
            s.useDelimiter("\\A");
            return s.hasNext() ? s.next() : "";
        }
    }
}
