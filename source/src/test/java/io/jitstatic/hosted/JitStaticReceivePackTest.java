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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.AbortedByHookException;
import org.eclipse.jgit.api.errors.ConcurrentRefUpdateException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRefNameException;
import org.eclipse.jgit.api.errors.NoHeadException;
import org.eclipse.jgit.api.errors.NoMessageException;
import org.eclipse.jgit.api.errors.RefAlreadyExistsException;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.api.errors.UnmergedPathsException;
import org.eclipse.jgit.api.errors.WrongRepositoryStateException;
import org.eclipse.jgit.errors.AmbiguousObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.RevisionSyntaxException;
import org.eclipse.jgit.lib.BatchRefUpdate;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefDatabase;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.PushConnection;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceiveCommand.Result;
import org.eclipse.jgit.transport.ReceiveCommand.Type;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.RemoteRefUpdate.Status;
import org.eclipse.jgit.transport.RequestNotYetReadException;
import org.eclipse.jgit.transport.TestProtocol;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.URIish;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import com.spencerwi.either.Either;

import io.jitstatic.JitStaticConstants;
import io.jitstatic.check.SourceChecker;
import io.jitstatic.test.TemporaryFolder;
import io.jitstatic.test.TemporaryFolderExtension;

@ExtendWith(TemporaryFolderExtension.class)
public class JitStaticReceivePackTest {

    private static final String METADATA = ".metadata";
    private static final String REF_HEADS_MASTER = "refs/heads/master";
    private static final String STORE = "store";
    private static final String SHA_1 = "5f12e3846fef8c259efede1a55e12667effcc461";
    private final Object o = new Object();

    private static final byte[] data = brackets();
    private TemporaryFolder tmpFolder;
    private Git remoteBareGit;
    private Git clientGit;
    private Path storePath;
    private Path storeMetaPath;
    private TestProtocol<Object> protocol;
    private URIish uri;
    private ErrorReporter errorReporter;
    private RefLockHolderManager bus;
    private ExecutorService executor;

    @BeforeEach
    public void setup() throws IllegalStateException, GitAPIException, IOException {
        remoteBareGit = Git.init().setDirectory(getFolder().toFile()).setBare(true).call();
        File workingFolder = getFolder().toFile();
        clientGit = Git.cloneRepository().setURI(remoteBareGit.getRepository().getDirectory().getAbsolutePath()).setDirectory(workingFolder).call();
        storePath = workingFolder.toPath().resolve(STORE);
        storeMetaPath = workingFolder.toPath().resolve(STORE + METADATA);
        Files.write(storePath, data, StandardOpenOption.CREATE);
        Files.write(storeMetaPath, data, StandardOpenOption.CREATE);
        clientGit.add().addFilepattern(STORE).call();
        clientGit.commit().setMessage("Initial commit").call();
        clientGit.push().call();
        executor = Executors.newSingleThreadExecutor();
        errorReporter = new ErrorReporter();
        bus = new RefLockHolderManager();
        bus.setRefHolderFactory(this::refHolderFactory);
        protocol = new TestProtocol<Object>(null, (req, db) -> {
            final ReceivePack receivePack = new JitStaticReceivePack(db, REF_HEADS_MASTER, errorReporter, bus, new SourceChecker(db), new UserExtractor(db), false, new RepoInserter(db), executor);
            return receivePack;

        });
        uri = protocol.register(o, remoteBareGit.getRepository());
    }

    @AfterEach
    public void tearDown() throws InterruptedException {
        executor.shutdown();
        remoteBareGit.close();
        clientGit.close();
        Transport.unregister(protocol);
        Throwable fault = errorReporter.getFault();
        if (fault != null) {
            fault.printStackTrace();
        }
        assertEquals(null, fault);
        executor.awaitTermination(10, TimeUnit.SECONDS);
    }

    @Test
    public void testNewBranch() throws RefAlreadyExistsException, RefNotFoundException, InvalidRefNameException, GitAPIException, RevisionSyntaxException,
            AmbiguousObjectException, IncorrectObjectTypeException, IOException {
        Repository localRepository = clientGit.getRepository();
        RemoteTestUtils.copy("/test3.json", storePath);
        clientGit.add().addFilepattern(STORE).call();
        clientGit.commit().setMessage("New commit").call();
        clientGit.push().call();

        clientGit.branchCreate().setName("newbranch").call();

        clientGit.checkout().setName("newbranch").call();
        RemoteTestUtils.copy("/test4.json", storePath);
        clientGit.add().addFilepattern(STORE).call();
        clientGit.commit().setMessage("Newer commit").call();

        Ref head = localRepository.exactRef(Constants.HEAD);
        List<RefSpec> refSpecs = new ArrayList<>();
        final RefSpec refSpec = new RefSpec(head.getLeaf().getName());
        refSpecs.add(refSpec);

        try (Transport tn = protocol.open(uri, localRepository, "server");) {
            final Collection<RemoteRefUpdate> toPush = tn.findRemoteRefUpdatesFor(refSpecs);
            tn.push(NullProgressMonitor.INSTANCE, toPush);
            for (RemoteRefUpdate rru : toPush) {
                assertEquals(Status.REJECTED_OTHER_REASON, rru.getStatus());
                assertTrue(rru.getMessage().startsWith("Error in branch refs/heads/newbranch"));
            }
        }
    }

    @Test
    public void testPushCommand() throws IOException, NoHeadException, NoMessageException, UnmergedPathsException, ConcurrentRefUpdateException,
            WrongRepositoryStateException, AbortedByHookException, GitAPIException {
        RemoteTestUtils.copy("/test3.json", storePath);
        clientGit.add().addFilepattern(STORE).call();
        clientGit.commit().setMessage("New commit").call();
        clientGit.push().call();

        clientGit.branchCreate().setName("newbranch").call();

        clientGit.checkout().setName("newbranch").call();
        RemoteTestUtils.copy("/test4.json", storePath);
        clientGit.add().addFilepattern(STORE).call();
        clientGit.commit().setMessage("Newer commit").call();
        Iterable<PushResult> pushCall = clientGit.push().call();
        int cntr = 0;
        for (@SuppressWarnings("unused")
        PushResult pr : pushCall) {
            cntr++;
        }
        assertTrue(cntr == 1);
    }

    @Test
    public void testOnPreReceiveNewCommitValidJSON() throws Exception {
        bus.setRefHolderFactory(this::refHolderFactory);
        final Repository localRepository = clientGit.getRepository();
        final ObjectId oldId = localRepository.resolve(Constants.HEAD);
        RemoteTestUtils.copy("/test3.json", storePath);
        RemoteTestUtils.copy("/test3.md.json", storeMetaPath);
        clientGit.add().addFilepattern(".").call();
        RevCommit c = clientGit.commit().setMessage("New commit").call();
        assertFalse(oldId.equals(c));
        Ref ref = localRepository.findRef(REF_HEADS_MASTER);

        RemoteRefUpdate rru = new RemoteRefUpdate(localRepository, ref, REF_HEADS_MASTER, true, null, oldId);
        Map<String, RemoteRefUpdate> updates = new HashMap<>();
        updates.put(rru.getRemoteName(), rru);

        try (Transport tn = protocol.open(uri, localRepository, "server"); PushConnection connection = tn.openPush()) {
            connection.push(NullProgressMonitor.INSTANCE, updates);
            String messages = connection.getMessages();
            System.out.println(messages);
        }
        assertEquals(Status.OK, rru.getStatus());
        assertEquals(null, errorReporter.getFault());
    }

    @Test
    public void testOnPreReceiveFaultyJSONShouldDenyCommit() throws Exception {
        final Repository localRepository = clientGit.getRepository();
        final ObjectId oldId = localRepository.resolve(Constants.HEAD);
        RemoteTestUtils.copy("/test4.json", storePath);
        clientGit.add().addFilepattern(STORE).call();
        final RevCommit c = clientGit.commit().setMessage("New commit").call();
        assertFalse(oldId.equals(c));
        final Ref ref = localRepository.findRef(REF_HEADS_MASTER);

        final RemoteRefUpdate rru = new RemoteRefUpdate(localRepository, ref, REF_HEADS_MASTER, true, null, oldId);
        final Map<String, RemoteRefUpdate> updates = new HashMap<>();
        updates.put(rru.getRemoteName(), rru);

        try (Transport tn = protocol.open(uri, localRepository, "server"); PushConnection connection = tn.openPush()) {
            connection.push(NullProgressMonitor.INSTANCE, updates);
            String messages = connection.getMessages();
            System.out.println(messages);
        }
        assertEquals(Status.REJECTED_OTHER_REASON, rru.getStatus());
        assertTrue(rru.getMessage().startsWith("Error in branch " + REF_HEADS_MASTER));
        assertEquals(null, errorReporter.getFault());
        final File newFolder = getFolder().toFile();
        try (Git git = Git.cloneRepository().setDirectory(newFolder).setURI(remoteBareGit.getRepository().getDirectory().getAbsolutePath()).call()) {
            byte[] readAllBytes = Files.readAllBytes(newFolder.toPath().resolve(STORE));
            assertArrayEquals(data, readAllBytes);
        }
    }

    @Test
    public void testRepositoryFailsIOException() throws Exception {
        Repository remoteRepository = spy(remoteBareGit.getRepository());
        final String errormsg = "Triggered fault";
        RefDatabase refDatabase = mock(RefDatabase.class);
        when(refDatabase.firstExactRef(any())).thenThrow(new IOException(errormsg));
        when(remoteRepository.getRefDatabase()).thenReturn(refDatabase);

        RemoteTestUtils.copy("/test3.json", storePath);
        clientGit.add().addFilepattern(STORE).call();
        ObjectId oldRef = clientGit.getRepository().resolve(REF_HEADS_MASTER);
        RevCommit c = clientGit.commit().setMessage("New commit").call();

        ReceiveCommand rc = new ReceiveCommand(oldRef, c.getId(), REF_HEADS_MASTER, Type.UPDATE);
        JitStaticReceivePack rp = initUnit(remoteRepository, new SourceChecker(remoteRepository), new UserExtractor(remoteRepository), rc, bus);

        BatchRefUpdate bru = mock(BatchRefUpdate.class);
        when(remoteRepository.getRefDatabase()).thenReturn(refDatabase);
        when(refDatabase.newBatchUpdate()).thenReturn(bru);

        rp.executeCommands();

        assertEquals(Result.REJECTED_NOCREATE, rc.getResult());
        assertEquals("Couldn't create test branch for " + REF_HEADS_MASTER + " because " + errormsg, rc.getMessage());
        assertEquals(null, errorReporter.getFault());
    }

    @Test
    public void testRepositoryFailsIOExceptionWhenGettingRef() throws Exception {
        final String errormsg = "Triggered fault";
        RemoteTestUtils.copy("/test3.json", storePath);
        clientGit.add().addFilepattern(STORE).call();
        ObjectId oldRef = clientGit.getRepository().resolve(REF_HEADS_MASTER);
        RevCommit c = clientGit.commit().setMessage("New commit").call();
        Repository remoteRepository = mock(Repository.class);
        RefUpdate testUpdate = mock(RefUpdate.class);
        RefDatabase refDatabase = mock(RefDatabase.class);
        SourceChecker sc = mock(SourceChecker.class);
        UserExtractor ue = mock(UserExtractor.class);

        when(remoteRepository.getConfig()).thenReturn(remoteBareGit.getRepository().getConfig());
        ReceiveCommand rc = spy(new ReceiveCommand(oldRef, c.getId(), REF_HEADS_MASTER, Type.UPDATE));

        JitStaticReceivePack rp = initUnit(remoteRepository, sc, ue, rc, bus);
        when(remoteRepository.updateRef(startsWith(JitStaticConstants.REFS_JITSTATIC))).thenReturn(testUpdate);
        when(testUpdate.forceUpdate()).thenReturn(RefUpdate.Result.FORCED);
        BatchRefUpdate spyBatchRefUpdate = getSpyingBatchRefUpdate(refDatabase);
        doNothing().when(spyBatchRefUpdate).execute(any(), any());
        when(remoteRepository.getRefDatabase()).thenReturn(refDatabase);
        when(refDatabase.newBatchUpdate()).thenReturn(spyBatchRefUpdate);
        when(refDatabase.firstExactRef(any())).thenThrow(new IOException(errormsg));

        rp.executeCommands();

        assertEquals(errormsg, rc.getMessage());
        assertEquals(Result.REJECTED_OTHER_REASON, rc.getResult());
        Throwable fault = errorReporter.getFault();
        assertNotNull(fault);
        assertTrue(fault instanceof RepositoryException);
        assertEquals("Error while writing commit, repo is in an unknown state", fault.getMessage());
        assertEquals(errormsg, fault.getCause().getMessage());
    }

    @Test
    public void testRepositoryFailsIOExceptionWhenDeletingTemporaryBranch() throws Exception {
        final String errormsg = "Test Triggered fault";
        Repository remoteRepository = mock(Repository.class);
        RefUpdate testUpdate = mock(RefUpdate.class);
        RefUpdate ru = mock(RefUpdate.class);
        RemoteTestUtils.copy("/test3.json", storePath);
        clientGit.add().addFilepattern(STORE).call();
        ObjectId oldRef = clientGit.getRepository().resolve(REF_HEADS_MASTER);
        RevCommit c = clientGit.commit().setMessage("New commit").call();
        RefDatabase refDatabase = mock(RefDatabase.class);
        when(remoteRepository.getConfig()).thenReturn(remoteBareGit.getRepository().getConfig());
        SourceChecker sc = mock(SourceChecker.class);
        UserExtractor ue = mock(UserExtractor.class);
        BatchRefUpdate batchRefUpdate = mock(BatchRefUpdate.class);
        ReceiveCommand rc = spy(new ReceiveCommand(oldRef, c.getId(), REF_HEADS_MASTER, Type.UPDATE));

        JitStaticReceivePack rp = initUnit(remoteRepository, sc, ue, rc, bus);

        Mockito.doReturn(testUpdate).doReturn(ru).when(remoteRepository).updateRef(startsWith(JitStaticConstants.REFS_JITSTATIC));
        when(testUpdate.forceUpdate()).thenReturn(RefUpdate.Result.FORCED);
        when(ru.delete()).thenThrow(new IOException(errormsg));
        BatchRefUpdate spyBatchRefUpdate = getSpyingBatchRefUpdate(refDatabase);
        doNothing().when(spyBatchRefUpdate).execute(any(), any());
        when(remoteRepository.getRefDatabase()).thenReturn(refDatabase);
        when(refDatabase.newBatchUpdate()).thenReturn(spyBatchRefUpdate).thenReturn(batchRefUpdate);
        rp.executeCommands();
        TimeUnit.SECONDS.sleep(2);
        Mockito.verify(ru).delete();
    }

    @Test
    public void testRepositoryFailsGeneralError() throws Exception {
        RemoteTestUtils.copy("/test3.json", storePath);
        final String errormsg = "Triggered error";
        clientGit.add().addFilepattern(STORE).call();
        ObjectId oldRef = clientGit.getRepository().resolve(REF_HEADS_MASTER);
        RevCommit c = clientGit.commit().setMessage("New commit").call();

        Repository remoteRepository = mock(Repository.class);
        RefUpdate testUpdate = mock(RefUpdate.class);
        RefDatabase refDatabase = mock(RefDatabase.class);
        ReceiveCommand rc = spy(new ReceiveCommand(oldRef, c.getId(), REF_HEADS_MASTER, Type.UPDATE));
        when(remoteRepository.getConfig()).thenReturn(remoteBareGit.getRepository().getConfig());
        SourceChecker sourceChecker = mock(SourceChecker.class);
        UserExtractor ue = mock(UserExtractor.class);

        JitStaticReceivePack rp = initUnit(remoteRepository, sourceChecker, ue, rc, bus);
        when(sourceChecker.checkTestBranchForErrors(anyString())).thenThrow(new RuntimeException(errormsg));
        when(remoteRepository.updateRef(startsWith(JitStaticConstants.REFS_JITSTATIC))).thenReturn(testUpdate);
        when(testUpdate.forceUpdate()).thenReturn(RefUpdate.Result.FORCED);
        BatchRefUpdate spyBatchRefUpdate = getSpyingBatchRefUpdate(refDatabase);
        doNothing().when(spyBatchRefUpdate).execute(any(), any());
        when(remoteRepository.getRefDatabase()).thenReturn(refDatabase);
        when(refDatabase.newBatchUpdate()).thenReturn(spyBatchRefUpdate);

        rp.executeCommands();

        assertEquals("General error " + errormsg, rc.getMessage());
        assertEquals(Result.REJECTED_OTHER_REASON, rc.getResult());
        assertEquals("General error " + errormsg, errorReporter.getFault().getMessage());
    }

    @Test
    public void testDeleteDefaultBranch() throws RefAlreadyExistsException, RefNotFoundException, InvalidRefNameException, GitAPIException, IOException {
        clientGit.branchCreate().setName("other").call();
        Ref ref = clientGit.getRepository().findRef(REF_HEADS_MASTER);
        clientGit.checkout().setName("other").call();
        clientGit.branchDelete().setBranchNames(REF_HEADS_MASTER).call();
        ReceiveCommand rc = new ReceiveCommand(ref.getObjectId(), ObjectId.zeroId(), REF_HEADS_MASTER, Type.DELETE);
        Repository repository = remoteBareGit.getRepository();
        JitStaticReceivePack rp = initUnit(repository, new SourceChecker(repository), new UserExtractor(repository), rc, bus);

        rp.executeCommands();
        assertEquals(Result.REJECTED_NODELETE, rc.getResult());
        assertEquals(null, errorReporter.getFault());
    }

    @Test
    public void testDeleteDefaultBranchMessage() throws RefAlreadyExistsException, RefNotFoundException, InvalidRefNameException, GitAPIException, IOException {
        clientGit.branchCreate().setName("other").call();
        Ref ref = clientGit.getRepository().findRef(REF_HEADS_MASTER);
        clientGit.checkout().setName("other").call();
        clientGit.branchDelete().setBranchNames(REF_HEADS_MASTER).call();
        ReceiveCommand rc = new ReceiveCommand(ref.getObjectId(), ObjectId.zeroId(), REF_HEADS_MASTER, Type.DELETE);
        Repository repository = remoteBareGit.getRepository();
        JitStaticReceivePack rp = initUnit(repository, new SourceChecker(repository), new UserExtractor(repository), rc, bus);

        rp.executeCommands();
        assertEquals(Result.REJECTED_NODELETE, rc.getResult());
        assertEquals(null, errorReporter.getFault());
    }

    @Test
    public void testBranchIsMissingWhenCommitting() throws Exception {
        RefDatabase refDatabase = mock(RefDatabase.class);
        RemoteTestUtils.copy("/test3.json", storePath);
        clientGit.add().addFilepattern(STORE).call();
        RevCommit c = clientGit.commit().setMessage("New commit").call();
        clientGit.getRepository().findRef(REF_HEADS_MASTER);
        ReceiveCommand rc = spy(new ReceiveCommand(c.getId(), ObjectId.fromString(SHA_1), REF_HEADS_MASTER, Type.UPDATE));
        RefUpdate testUpdate = mock(RefUpdate.class);
        Repository remoteRepository = mock(Repository.class);
        when(remoteRepository.getConfig()).thenReturn(remoteBareGit.getRepository().getConfig());
        SourceChecker sc = mock(SourceChecker.class);
        UserExtractor ue = mock(UserExtractor.class);
        when(ue.checkOnTestBranch(anyString(), anyString())).thenReturn(List.of());
        JitStaticReceivePack rp = initUnit(remoteRepository, sc, ue, rc, bus);
        when(remoteRepository.updateRef(startsWith(JitStaticConstants.REFS_JITSTATIC))).thenReturn(testUpdate);
        when(testUpdate.forceUpdate()).thenReturn(RefUpdate.Result.FORCED);
        BatchRefUpdate spyBatchRefUpdate = getSpyingBatchRefUpdate(refDatabase);
        doNothing().when(spyBatchRefUpdate).execute(any(), any());
        when(remoteRepository.getRefDatabase()).thenReturn(refDatabase);
        when(refDatabase.newBatchUpdate()).thenReturn(spyBatchRefUpdate);
        when(refDatabase.firstExactRef(any())).thenReturn(null);

        rp.executeCommands();
        assertEquals(Result.REJECTED_MISSING_OBJECT, rc.getResult());
    }

    @Test
    public void testBranchIsStaleWhenCommitting() throws Exception {
        RefDatabase refDatabase = mock(RefDatabase.class);
        RemoteTestUtils.copy("/test3.json", storePath);
        clientGit.getRepository().findRef(REF_HEADS_MASTER);
        clientGit.add().addFilepattern(STORE).call();
        RevCommit c = clientGit.commit().setMessage("New commit").call();
        ReceiveCommand rc = spy(new ReceiveCommand(c.getId(), ObjectId.fromString(SHA_1), REF_HEADS_MASTER, Type.UPDATE));
        RefUpdate testUpdate = mock(RefUpdate.class);
        Repository remoteRepository = mock(Repository.class);
        when(remoteRepository.getConfig()).thenReturn(remoteBareGit.getRepository().getConfig());
        SourceChecker sc = mock(SourceChecker.class);
        UserExtractor ue = mock(UserExtractor.class);
        JitStaticReceivePack rp = initUnit(remoteRepository, sc, ue, rc, bus);
        Ref mock = mock(Ref.class);
        when(remoteRepository.updateRef(startsWith(JitStaticConstants.REFS_JITSTATIC))).thenReturn(testUpdate);
        when(testUpdate.forceUpdate()).thenReturn(RefUpdate.Result.FORCED);
        BatchRefUpdate spyBatchRefUpdate = getSpyingBatchRefUpdate(refDatabase);
        doNothing().when(spyBatchRefUpdate).execute(any(), any());
        when(remoteRepository.getRefDatabase()).thenReturn(refDatabase);
        when(refDatabase.newBatchUpdate()).thenReturn(spyBatchRefUpdate);
        when(refDatabase.firstExactRef(any())).thenReturn(mock);
        when(mock.getObjectId()).thenReturn(ObjectId.fromString(SHA_1));

        rp.executeCommands();

        verify(rc).setResult(eq(Result.REJECTED_NONFASTFORWARD), anyString());
    }

    @Test
    public void testFailedToLockBranch() throws Exception {
        RemoteTestUtils.copy("/test3.json", storePath);
        clientGit.getRepository().findRef(REF_HEADS_MASTER);
        clientGit.add().addFilepattern(STORE).call();
        RevCommit c = clientGit.commit().setMessage("New commit").call();
        ReceiveCommand rc = spy(new ReceiveCommand(c.getId(), ObjectId.fromString(SHA_1), REF_HEADS_MASTER, Type.UPDATE));
        RefUpdate testUpdate = mock(RefUpdate.class);
        Repository remoteRepository = mock(Repository.class);
        when(remoteRepository.getConfig()).thenReturn(remoteBareGit.getRepository().getConfig());
        SourceChecker sc = mock(SourceChecker.class);
        RefDatabase refDatabase = mock(RefDatabase.class);
        UserExtractor ue = mock(UserExtractor.class);
        RefLockHolderManager repobus = mock(RefLockHolderManager.class);
        when(ue.checkOnTestBranch(anyString(), anyString())).thenReturn(List.of());
        RefLockHolder refholder = mock(RefLockHolder.class);
        JitStaticReceivePack rp = initUnit(remoteRepository, sc, ue, rc, repobus);
        when(remoteRepository.updateRef(startsWith(JitStaticConstants.REFS_JITSTATIC))).thenReturn(testUpdate);
        when(testUpdate.forceUpdate()).thenReturn(RefUpdate.Result.FORCED);
        BatchRefUpdate spyBatchRefUpdate = getSpyingBatchRefUpdate(refDatabase);
        doNothing().when(spyBatchRefUpdate).execute(any(), any());
        when(remoteRepository.getRefDatabase()).thenReturn(refDatabase);
        when(refDatabase.newBatchUpdate()).thenReturn(spyBatchRefUpdate);
        when(repobus.getRefHolder(eq(REF_HEADS_MASTER))).thenReturn(refholder);
        when(refholder.enqueueAndBlock(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(Either.right(new FailedToLock(""))));

        rp.executeCommands();

        verify(rc).setResult(eq(Result.LOCK_FAILURE), anyString());
    }

    @Test
    public void testStorageIsCorrupt() throws Exception {
        final String errormsg = "Triggered error";
        RefDatabase refDatabase = mock(RefDatabase.class);
        RemoteTestUtils.copy("/test3.json", storePath);
        clientGit.getRepository().findRef(REF_HEADS_MASTER);
        clientGit.add().addFilepattern(STORE).call();
        RevCommit c = clientGit.commit().setMessage("New commit").call();
        ReceiveCommand rc = spy(new ReceiveCommand(c.getId(), ObjectId.fromString(SHA_1), REF_HEADS_MASTER, Type.UPDATE));
        RefUpdate testUpdate = mock(RefUpdate.class);
        Repository remoteRepository = mock(Repository.class);
        when(remoteRepository.getConfig()).thenReturn(remoteBareGit.getRepository().getConfig());
        SourceChecker sc = mock(SourceChecker.class);
        UserExtractor ue = mock(UserExtractor.class);
        JitStaticReceivePack rp = initUnit(remoteRepository, sc, ue, rc, bus);

        when(remoteRepository.updateRef(startsWith(JitStaticConstants.REFS_JITSTATIC))).thenReturn(testUpdate);
        when(testUpdate.forceUpdate()).thenReturn(RefUpdate.Result.FORCED);
        BatchRefUpdate spyBatchRefUpdate = getSpyingBatchRefUpdate(refDatabase);
        doNothing().when(spyBatchRefUpdate).execute(any(), any());
        when(remoteRepository.getRefDatabase()).thenReturn(refDatabase);
        when(refDatabase.newBatchUpdate()).thenReturn(spyBatchRefUpdate);
        when(sc.checkTestBranchForErrors(startsWith(JitStaticConstants.REFS_JITSTATIC))).thenThrow(new IOException(errormsg));

        rp.executeCommands();

        verify(rc).setResult(eq(Result.REJECTED_OTHER_REASON), eq(errormsg));
        Throwable e = errorReporter.getFault();
        assertTrue(e instanceof RepositoryException);
        assertEquals(errormsg, e.getCause().getMessage());
    }

    @Test
    public void testFailToCreateTempBranch() throws Exception {
        RemoteTestUtils.copy("/test3.json", storePath);
        clientGit.getRepository().findRef(REF_HEADS_MASTER);
        clientGit.add().addFilepattern(STORE).call();
        RevCommit c = clientGit.commit().setMessage("New commit").call();
        ReceiveCommand rc = spy(new ReceiveCommand(c.getId(), ObjectId.fromString(SHA_1), REF_HEADS_MASTER, Type.UPDATE));
        RefUpdate testUpdate = mock(RefUpdate.class);
        Repository remoteRepository = mock(Repository.class);
        when(remoteRepository.getConfig()).thenReturn(remoteBareGit.getRepository().getConfig());
        SourceChecker sc = mock(SourceChecker.class);
        UserExtractor ue = mock(UserExtractor.class);
        JitStaticReceivePack rp = initUnit(remoteRepository, sc, ue, rc, bus);
        when(remoteRepository.updateRef(startsWith(JitStaticConstants.REFS_JITSTATIC))).thenReturn(testUpdate);
        when(testUpdate.forceUpdate()).thenReturn(RefUpdate.Result.LOCK_FAILURE);

        rp.executeCommands();

        verify(rc).setResult(eq(Result.LOCK_FAILURE), eq("Failed to lock " + REF_HEADS_MASTER));
    }

    @Test
    public void testDeletedBranch() throws Exception {
        RemoteTestUtils.copy("/test3.json", storePath);
        clientGit.add().addFilepattern(STORE).call();
        ObjectId oldRef = clientGit.getRepository().resolve(REF_HEADS_MASTER);
        clientGit.commit().setMessage("New commit").call();
        Repository remoteRepository = mock(Repository.class);
        RefUpdate testUpdate = mock(RefUpdate.class);
        RefDatabase refDatabase = mock(RefDatabase.class);
        SourceChecker sc = mock(SourceChecker.class);
        UserExtractor ue = mock(UserExtractor.class);
        Ref ref = mock(Ref.class);
        when(remoteRepository.getConfig()).thenReturn(remoteBareGit.getRepository().getConfig());
        ReceiveCommand rc = spy(new ReceiveCommand(oldRef, ObjectId.zeroId(), "refs/heads/abranch", Type.DELETE));

        JitStaticReceivePack rp = initUnit(remoteRepository, sc, ue, rc, bus);
        when(testUpdate.delete(any())).thenReturn(RefUpdate.Result.FAST_FORWARD);
        when(remoteRepository.updateRef("refs/heads/abranch")).thenReturn(testUpdate);
        BatchRefUpdate spyBatchRefUpdate = getSpyingBatchRefUpdate(refDatabase);
        doNothing().when(spyBatchRefUpdate).execute(any(), any());
        when(remoteRepository.getRefDatabase()).thenReturn(refDatabase);
        when(refDatabase.newBatchUpdate()).thenReturn(spyBatchRefUpdate);
        when(refDatabase.firstExactRef(any())).thenReturn(ref);
        when(ref.getObjectId()).thenReturn(oldRef);

        rp.executeCommands();
        verify(rc).setResult(eq(Result.OK));
    }

    @Test
    public void testIOExceptionWhenReceiving() throws Exception {
        final String errormsg = "Triggered error";
        RemoteTestUtils.copy("/test3.json", storePath);
        clientGit.add().addFilepattern(STORE).call();
        ObjectId oldRef = clientGit.getRepository().resolve(REF_HEADS_MASTER);
        clientGit.commit().setMessage("New commit").call();
        Repository remoteRepository = mock(Repository.class);
        RefUpdate testUpdate = mock(RefUpdate.class);
        RefDatabase refDatabase = mock(RefDatabase.class);
        SourceChecker sc = mock(SourceChecker.class);
        UserExtractor ue = mock(UserExtractor.class);
        when(remoteRepository.getConfig()).thenReturn(remoteBareGit.getRepository().getConfig());
        ReceiveCommand rc = spy(new ReceiveCommand(oldRef, ObjectId.fromString(SHA_1), "refs/heads/abranch", Type.UPDATE));

        JitStaticReceivePack rp = initUnit(remoteRepository, sc, ue, rc, bus);
        when(remoteRepository.updateRef(startsWith(JitStaticConstants.REFS_JITSTATIC))).thenReturn(testUpdate);
        when(testUpdate.forceUpdate()).thenReturn(RefUpdate.Result.FORCED);
        BatchRefUpdate batchRefUpdate = mock(BatchRefUpdate.class);
        when(remoteRepository.getRefDatabase()).thenReturn(refDatabase);
        when(refDatabase.newBatchUpdate()).thenReturn(batchRefUpdate);

        doThrow(new IOException(errormsg)).doNothing().when(batchRefUpdate).execute(any(), any());

        rp.executeCommands();

        verify(rc).setResult(eq(Result.REJECTED_OTHER_REASON), eq("lock error: " + errormsg));
    }

    private BatchRefUpdate getSpyingBatchRefUpdate(RefDatabase refDatabase) {
        BatchRefUpdate spyBatchRefUpdate = spy(new BatchRefUpdate(refDatabase) {
            @Override
            public BatchRefUpdate addCommand(Collection<ReceiveCommand> cmd) {
                cmd.stream().forEach(r -> r.setResult(Result.OK));
                return this;
            }
        });
        return spyBatchRefUpdate;
    }

    private JitStaticReceivePack initUnit(Repository remoteRepository, SourceChecker sc, UserExtractor ue, ReceiveCommand rc, RefLockHolderManager bus) {
        JitStaticReceivePack rp = new JitStaticReceivePack(remoteRepository, REF_HEADS_MASTER, errorReporter, bus, sc, ue, false, mock(RepoInserter.class), executor) {
            @Override
            protected List<ReceiveCommand> filterCommands(Result want) {
                return Arrays.asList(rc);
            }

            @Override
            public boolean isSideBand() throws RequestNotYetReadException { return false; }
        };
        return rp;
    }

    RefLockHolder refHolderFactory(String ref) {
        return new RefLockHolder() {

            @Override
            public <T> CompletableFuture<Either<T, FailedToLock>> enqueueAndReadBlock(Supplier<T> supplier) {
                return CompletableFuture.completedFuture(Either.left(supplier.get()));
            }

            @Override
            public CompletableFuture<Either<String, FailedToLock>> enqueueAndBlock(Supplier<Exception> preRequisite, Supplier<DistributedData> action, Consumer<Exception> postAction) {
                return CompletableFuture.supplyAsync(() -> {
                    Exception exception = preRequisite.get();
                    try {
                        if (exception == null) {
                            DistributedData holder = action.get();
                            return Either.left(holder.getTip());
                        }
                    } finally {
                        postAction.accept(exception);
                    }
                    FailedToLock ftl = new FailedToLock(ref);
                    ftl.addSuppressed(exception);
                    return Either.right(ftl);
                });
            }
        };
    }

    Path getFolder() throws IOException { return tmpFolder.createTemporaryDirectory().toPath(); }

    private static byte[] brackets() {
        return "{}".getBytes(StandardCharsets.UTF_8);

    }
}
