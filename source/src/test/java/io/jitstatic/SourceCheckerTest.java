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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.errors.AmbiguousObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.RevisionSyntaxException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.jitstatic.check.FileIsMissingMetaData;
import io.jitstatic.check.FileObjectIdStore;
import io.jitstatic.check.SourceChecker;
import io.jitstatic.hosted.RemoteTestUtils;
import io.jitstatic.test.TemporaryFolder;
import io.jitstatic.test.TemporaryFolderExtension;
import io.jitstatic.utils.Pair;

@ExtendWith(TemporaryFolderExtension.class)
public class SourceCheckerTest {

    private static final String REF_HEAD_MASTER = Constants.R_HEADS + "master";
    private static final String store = "data";
    private TemporaryFolder tmpFolder;
    private Git bareGit;
    private Git workingGit;

    @BeforeEach
    public void setup() throws Exception {
        final File base = createTempFiles();
        final File wBase = createTempFiles();
        bareGit = Git.init().setBare(true).setDirectory(base).call();
        workingGit = Git.cloneRepository().setURI(bareGit.getRepository().getDirectory().toURI().toString()).setDirectory(wBase).call();

        RemoteTestUtils.copy("/test3.json", wBase.toPath().resolve(store));
        workingGit.add().addFilepattern(store).call();
        workingGit.commit().setMessage("Initial commit").call();
        workingGit.push().call();
    }

    private File createTempFiles() throws IOException {
       return tmpFolder.createTemporaryDirectory();
    }

    @AfterEach
    public void tearDown() {
        bareGit.close();
        workingGit.close();
    }

    @Test
    public void testCheckSourceFileOnBareRepo()
            throws RevisionSyntaxException, AmbiguousObjectException, IncorrectObjectTypeException, IOException, RefNotFoundException {
        try (SourceChecker sc = new SourceChecker(bareGit.getRepository())) {
            List<Pair<Set<Ref>, List<Pair<FileObjectIdStore, Exception>>>> errors = sc.checkBranchForErrors(REF_HEAD_MASTER);
            Pair<Set<Ref>, List<Pair<FileObjectIdStore, Exception>>> pair = errors.get(0);
            Optional<Ref> firstRef = pair.getLeft().stream().findFirst();
            assertEquals(REF_HEAD_MASTER, firstRef.get().getName());
            List<Pair<FileObjectIdStore, Exception>> exceptions = pair.getRight();
            assertTrue(exceptions.size() == 1);
            Pair<FileObjectIdStore, Exception> fileExPair = exceptions.get(0);
            assertEquals(store, fileExPair.getLeft().getFileName());
            assertTrue(fileExPair.getRight() instanceof FileIsMissingMetaData);
        }
    }

    @Test
    public void testCheckSourceFileOnNormalRepo()
            throws RevisionSyntaxException, AmbiguousObjectException, IncorrectObjectTypeException, IOException, RefNotFoundException {
        try (SourceChecker sc = new SourceChecker(workingGit.getRepository())) {
            List<Pair<Set<Ref>, List<Pair<FileObjectIdStore, Exception>>>> errors = sc.checkBranchForErrors(REF_HEAD_MASTER);
            Pair<Set<Ref>, List<Pair<FileObjectIdStore, Exception>>> pair = errors.get(0);
            Optional<Ref> firstRef = pair.getLeft().stream().findFirst();
            assertEquals(REF_HEAD_MASTER, firstRef.get().getName());
            List<Pair<FileObjectIdStore, Exception>> exceptions = pair.getRight();
            assertTrue(exceptions.size() == 1);
            Pair<FileObjectIdStore, Exception> fileExPair = exceptions.get(0);
            assertEquals(store, fileExPair.getLeft().getFileName());
            assertTrue(fileExPair.getRight() instanceof FileIsMissingMetaData);
        }
    }

    @Test
    public void testCorrectFormattedBranchNotFound()
            throws RevisionSyntaxException, AmbiguousObjectException, IncorrectObjectTypeException, IOException, RefNotFoundException {
        final String branch = Constants.R_HEADS + "other";
        assertEquals(branch, assertThrows(RefNotFoundException.class, () -> {
            try (SourceChecker sc = new SourceChecker(bareGit.getRepository());) {
                sc.checkBranchForErrors(branch);
            }
        }).getLocalizedMessage());
    }

    @Test
    public void testNotCorrectFormattedBranchNotFound()
            throws RevisionSyntaxException, AmbiguousObjectException, IncorrectObjectTypeException, IOException, RefNotFoundException {
        final String branch = "other";
        assertEquals(branch, assertThrows(RefNotFoundException.class, () -> {
            try (SourceChecker sc = new SourceChecker(bareGit.getRepository());) {
                sc.checkBranchForErrors(branch);
            }
        }).getLocalizedMessage());
    }
}
