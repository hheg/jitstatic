package io.jitstatic.hosted;

import static io.jitstatic.source.ObjectStreamProvider.toProvider;

/*-
 * #%L
 * jitstatic
 * %%
 * Copyright (C) 2017 - 2019 H.Hegardt
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

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.CREATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand.FastForwardMode;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.api.errors.AbortedByHookException;
import org.eclipse.jgit.api.errors.CheckoutConflictException;
import org.eclipse.jgit.api.errors.ConcurrentRefUpdateException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.NoFilepatternException;
import org.eclipse.jgit.api.errors.NoHeadException;
import org.eclipse.jgit.api.errors.NoMessageException;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.api.errors.UnmergedPathsException;
import org.eclipse.jgit.api.errors.WrongRepositoryStateException;
import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.errors.UnmergedPathException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate.Result;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.PackParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.jitstatic.CommitMetaData;
import io.jitstatic.RepositoryUpdater;
import io.jitstatic.source.ObjectStreamProvider;
import io.jitstatic.test.TemporaryFolder;
import io.jitstatic.test.TemporaryFolderExtension;
import io.jitstatic.utils.Pair;

@ExtendWith(TemporaryFolderExtension.class)
public class RepoInserterTest {
    private static final String REF_HEADS_MASTER = Constants.R_HEADS + Constants.MASTER;
    private TemporaryFolder tmpFolder;

    Path getFolder() throws IOException {
        return tmpFolder.createTemporaryDirectory().toPath();
    }

    @Test
    public void testRecreateCommit() throws IOException, IllegalStateException, GitAPIException, InterruptedException {
        File base1 = getFolder().toFile();
        File base2 = getFolder().toFile();
        CommitMetaData cmd = new CommitMetaData("userInfo", "userMail", "message", "proxyUser", "proxyUserMail");

        List<Pair<String, ObjectStreamProvider>> files = List.of(Pair.of("wrote", toProvider("inserted".getBytes(UTF_8))));
        try (Git git1 = Git.init().setDirectory(base1).call();) {
            Path file = base1.toPath().resolve("other");
            Files.write(file, "data".getBytes(UTF_8), CREATE);
            git1.add().addFilepattern(".").call();
            git1.commit().setMessage("Initial commit").call();
            try (Git git2 = Git.cloneRepository().setURI(base1.getAbsolutePath()).setDirectory(base2).call();) {
                update(cmd, files, git2);
                TimeUnit.MILLISECONDS.sleep(10);
                update(cmd, files, git1);
                assertLogEquals(git1.log().call().iterator(), git2.log().call().iterator());
            }
        }
    }

    // TODO Test with signed commits
    @Test
    public void testRecreateLogSimple() throws IOException, IllegalStateException, GitAPIException, InterruptedException {
        File base1 = getFolder().toFile();
        File base2 = getFolder().toFile();
        CommitMetaData cmd = new CommitMetaData("userInfo", "userMail", "message", "proxyUser", "proxyUserMail");

        List<Pair<String, ObjectStreamProvider>> files = List.of(Pair.of("wrote", toProvider("inserted".getBytes(UTF_8))));
        try (Git git1 = Git.init().setDirectory(base1).call();) {
            writeAndCommit(base1, git1, "a", "data_a_1", "commit a 1");
            git1.checkout().setName("b").setCreateBranch(true).call();
            writeAndCommit(base1, git1, "b", "data_b_1", "commit b 1");
            writeAndCommit(base1, git1, "b", "data_b_2", "commit b 2");
            git1.checkout().setName(REF_HEADS_MASTER).call();
            writeAndCommit(base1, git1, "a", "data_a_2", "commit a 2");
            git1.checkout().setName("c").setCreateBranch(true).call();
            writeAndCommit(base1, git1, "c", "data_c_1", "commit c 1");
            writeAndCommit(base1, git1, "c", "data_c_2", "commit c 2");
            git1.checkout().setName("d").setCreateBranch(true).call();
            writeAndCommit(base1, git1, "d", "data_d_1", "commit d 1");
            writeAndCommit(base1, git1, "d", "data_d_2", "commit d 2");

            git1.checkout().setName("c").call();
            MergeResult mrd = git1.merge().setFastForward(FastForwardMode.NO_FF).include(git1.getRepository().findRef("d")).call();
            assertTrue(mrd.getMergeStatus().isSuccessful());
            git1.checkout().setName(REF_HEADS_MASTER).call();
            MergeResult mrc = git1.merge().setFastForward(FastForwardMode.NO_FF).include(git1.getRepository().findRef("c")).call();
            assertTrue(mrc.getMergeStatus().isSuccessful());
            MergeResult mrb = git1.merge().setFastForward(FastForwardMode.NO_FF).include(git1.getRepository().findRef("b")).call();
            assertTrue(mrb.getMergeStatus().isSuccessful());

            RevCommit last = writeAndCommit(base1, git1, "a", "data_a_3", "commit a 3");

            try (Git git2 = Git.cloneRepository().setURI(base1.getAbsolutePath()).setDirectory(base2).call();) {
                update(cmd, files, git2);
                cleanWorkDir(git2);

                RevCommit tip = writeAndCommit(base2, git2, "a", "data_a_4", "commit a 4");

                Set<ObjectId> tags = Set.of();
                Set<ObjectId> tips = Set.of(tip);
                Set<ObjectId> uninterestingObjects = Set.of(last);
                RepoInserter ri2 = new RepoInserter(git2.getRepository());
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ri2.packData(tags, tips, uninterestingObjects).accept(baos);

                RepoInserter ri1 = new RepoInserter(git1.getRepository());

                PackParser packParser = ri1.parse(new ByteArrayInputStream(baos.toByteArray()));

                assertTrue(packParser.getNewObjectIds().contains(tip));

                assertEquals(Result.FORCED, ri1.moveRef(last, tip, REF_HEADS_MASTER));
                cleanWorkDir(git1);

                assertLogEquals(git1.log().call().iterator(), git2.log().call().iterator());
            }
        }
    }

    @Test
    public void testRecreateLogBranched() throws IOException, IllegalStateException, GitAPIException, InterruptedException {
        File base1 = getFolder().toFile();
        File base2 = getFolder().toFile();

        try (Git git1 = Git.init().setDirectory(base1).call();) {

            RevCommit last = writeAndCommit(base1, git1, "a", "data_a_1", "commit a 1");

            try (Git git2 = Git.cloneRepository().setURI(base1.getAbsolutePath()).setDirectory(base2).call();) {

                git2.checkout().setName("b").setCreateBranch(true).call();
                writeAndCommit(base2, git2, "b", "data_b_1", "commit b 1");
                writeAndCommit(base2, git2, "b", "data_b_2", "commit b 2");
                git2.checkout().setName(REF_HEADS_MASTER).call();
                writeAndCommit(base2, git2, "a", "data_a_2", "commit a 2");
                git2.checkout().setName("c").setCreateBranch(true).call();
                writeAndCommit(base2, git2, "c", "data_c_1", "commit c 1");
                writeAndCommit(base2, git2, "c", "data_c_2", "commit c 2");
                git2.checkout().setName("d").setCreateBranch(true).call();
                writeAndCommit(base2, git2, "d", "data_d_1", "commit d 1");
                writeAndCommit(base2, git2, "d", "data_d_2", "commit d 2");
                git2.checkout().setName("c").call();
                MergeResult mrd = git2.merge().setFastForward(FastForwardMode.NO_FF).include(git2.getRepository().findRef("d")).call();
                assertTrue(mrd.getMergeStatus().isSuccessful());
                git2.checkout().setName(REF_HEADS_MASTER).call();
                MergeResult mrc = git2.merge().setFastForward(FastForwardMode.NO_FF).include(git2.getRepository().findRef("c")).call();
                assertTrue(mrc.getMergeStatus().isSuccessful());
                MergeResult mrb = git2.merge().setFastForward(FastForwardMode.NO_FF).include(git2.getRepository().findRef("b")).call();
                assertTrue(mrb.getMergeStatus().isSuccessful());

                writeAndCommit(base2, git2, "a", "data_a_3", "commit a 3");
                RevCommit tip = writeAndCommit(base2, git2, "d", "data_d_3", "commit d 3");

                Set<ObjectId> tags = Set.of();
                Set<ObjectId> tips = Set.of(tip);
                Set<ObjectId> uninterestingObjects = Set.of(last);
                RepoInserter ri2 = new RepoInserter(git2.getRepository());
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ri2.packData(tags, tips, uninterestingObjects).accept(baos);

                RepoInserter ri1 = new RepoInserter(git1.getRepository());

                PackParser packParser = ri1.parse(new ByteArrayInputStream(baos.toByteArray()));
                assertTrue(packParser.getNewObjectIds().contains(tip));

                assertEquals(Result.FORCED, ri1.moveRef(last, tip, REF_HEADS_MASTER));
                cleanWorkDir(git1);

                assertLogEquals(git1.log().call().iterator(), git2.log().call().iterator());
            }
        }
    }

    @Test
    public void testRecreateLogTag() throws IOException, IllegalStateException, GitAPIException, InterruptedException {
        File base1 = getFolder().toFile();
        File base2 = getFolder().toFile();
        CommitMetaData cmd = new CommitMetaData("userInfo", "userMail", "message", "proxyUser", "proxyUserMail");

        List<Pair<String, ObjectStreamProvider>> files = List.of(Pair.of("wrote", toProvider("inserted".getBytes(UTF_8))));
        try (Git git1 = Git.init().setDirectory(base1).call();) {

            writeAndCommit(base1, git1, "a", "data_a_1", "commit a 1");
            git1.checkout().setName("b").setCreateBranch(true).call();
            writeAndCommit(base1, git1, "b", "data_b_1", "commit b 1");
            writeAndCommit(base1, git1, "b", "data_b_2", "commit b 2");
            git1.checkout().setName(REF_HEADS_MASTER).call();
            writeAndCommit(base1, git1, "a", "data_a_2", "commit a 2");
            git1.checkout().setName("c").setCreateBranch(true).call();
            writeAndCommit(base1, git1, "c", "data_c_1", "commit c 1");
            writeAndCommit(base1, git1, "c", "data_c_2", "commit c 2");
            git1.checkout().setName("d").setCreateBranch(true).call();
            writeAndCommit(base1, git1, "d", "data_d_1", "commit d 1");
            writeAndCommit(base1, git1, "d", "data_d_2", "commit d 2");

            git1.checkout().setName("c").call();
            MergeResult mrd = git1.merge().setFastForward(FastForwardMode.NO_FF).include(git1.getRepository().findRef("d")).call();
            assertTrue(mrd.getMergeStatus().isSuccessful());
            git1.checkout().setName(REF_HEADS_MASTER).call();
            MergeResult mrc = git1.merge().setFastForward(FastForwardMode.NO_FF).include(git1.getRepository().findRef("c")).call();
            assertTrue(mrc.getMergeStatus().isSuccessful());
            MergeResult mrb = git1.merge().setFastForward(FastForwardMode.NO_FF).include(git1.getRepository().findRef("b")).call();
            assertTrue(mrb.getMergeStatus().isSuccessful());

            RevCommit last = writeAndCommit(base1, git1, "a", "data_a_3", "commit a 3");

            try (Git git2 = Git.cloneRepository().setURI(base1.getAbsolutePath()).setDirectory(base2).call();) {
                update(cmd, files, git2);
                cleanWorkDir(git2);

                RevCommit tip = writeAndCommit(base2, git2, "d", "data_d_1", "commit d 1");
                Ref tag = git2.tag().setAnnotated(true).setName("tag").call();

                Set<ObjectId> tags = Set.of(tag.getObjectId());
                Set<ObjectId> tips = Set.of(tip, tag.getObjectId());
                Set<ObjectId> uninterestingObjects = Set.of(last);
                RepoInserter ri2 = new RepoInserter(git2.getRepository());
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ri2.packData(tags, tips, uninterestingObjects).accept(baos);

                RepoInserter ri1 = new RepoInserter(git1.getRepository());

                PackParser packParser = ri1.parse(new ByteArrayInputStream(baos.toByteArray()));

                assertTrue(packParser.getNewObjectIds().contains(tip));
                assertTrue(packParser.getNewObjectIds().contains(tag.getObjectId()));

                assertEquals(Result.FORCED, ri1.moveRef(last, tip, REF_HEADS_MASTER));
                assertEquals(Result.NEW, ri1.moveRef(ObjectId.zeroId(), tag.getObjectId(), tag.getName()));
                cleanWorkDir(git1);

                assertLogEquals(git1.log().call().iterator(), git2.log().call().iterator());
                assertTrue(git1.getRepository().findRef(tag.getName()) != null);
            }
        }
    }

    private void update(CommitMetaData cmd, List<Pair<String, ObjectStreamProvider>> files, Git git)
            throws IOException, RefNotFoundException, MissingObjectException, IncorrectObjectTypeException, CorruptObjectException, UnmergedPathException {
        RepositoryUpdater updater = new RepositoryUpdater(git.getRepository());
        List<Pair<String, ObjectId>> commit = updater.buildDirCache(cmd, files, REF_HEADS_MASTER);
        assertFalse(commit.isEmpty());
    }

    private void cleanWorkDir(Git git) throws GitAPIException, CheckoutConflictException {
        // This is necessary because we are really working with a bare repo, but in these tests they are work repository.   
        git.reset().setMode(ResetType.HARD).setRef(REF_HEADS_MASTER).call();
        git.clean().setCleanDirectories(true).setForce(true).call();
    }

    private RevCommit writeAndCommit(File base, Git git, String fileName, String data, String commitMsg)
            throws IOException, GitAPIException, NoFilepatternException, NoHeadException, NoMessageException,
            UnmergedPathsException, ConcurrentRefUpdateException, WrongRepositoryStateException, AbortedByHookException {
        Path file = base.toPath().resolve(fileName);
        Files.write(file, data.getBytes(UTF_8), CREATE);
        git.add().addFilepattern(".").call();
        return git.commit().setMessage(commitMsg).call();
    }

    private void assertLogEquals(Iterator<RevCommit> iterator, Iterator<RevCommit> iterator2) {
        boolean same = true;
        while (iterator.hasNext()) {
            if (!iterator2.hasNext() || !iterator.next().equals(iterator2.next())) {
                same = false;
                break;
            }
        }
        assertTrue(same);
    }
}
