package jitstatic.hosted;

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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
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
import org.eclipse.jgit.transport.TestProtocol;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.URIish;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;

public class JitStaticReceivePackTest {

	private static final String REF_HEADS_MASTER = "refs/heads/master";
	private static final String STORE = "store";
	private final Object o = new Object();

	private static final byte[] data;
	static {
		try {
			data = "{}".getBytes("UTF-8");
		} catch (UnsupportedEncodingException e) {
			throw new Error(e);
		}
	}

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private Git remoteBareGit;
	private Git clientGit;
	private Path storePath;
	private TestProtocol<Object> protocol;
	private URIish uri;

	@Before
	public void setup() throws IllegalStateException, GitAPIException, IOException {
		remoteBareGit = Git.init().setDirectory(folder.newFolder()).setBare(true).call();
		File workingFolder = folder.newFolder();
		clientGit = Git.cloneRepository().setURI(remoteBareGit.getRepository().getDirectory().getAbsolutePath())
				.setDirectory(workingFolder).call();
		storePath = workingFolder.toPath().resolve(STORE);
		Files.write(storePath, data, StandardOpenOption.CREATE);
		clientGit.add().addFilepattern(STORE).call();
		clientGit.commit().setMessage("Initial commit").call();
		clientGit.push().call();

		protocol = new TestProtocol<Object>(null, (req, db) -> {
			final ReceivePack receivePack = new JitstaticReceivePack(db, REF_HEADS_MASTER, null, null);
			return receivePack;

		});
		uri = protocol.register(o, remoteBareGit.getRepository());
	}

	@After
	public void tearDown() {
		remoteBareGit.close();
		clientGit.close();
		Transport.unregister(protocol);
	}

	@Test
	public void testNewBranch()
			throws RefAlreadyExistsException, RefNotFoundException, InvalidRefNameException, GitAPIException,
			RevisionSyntaxException, AmbiguousObjectException, IncorrectObjectTypeException, IOException {
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
				assertEquals("" + rru.getMessage(), Status.REJECTED_OTHER_REASON, rru.getStatus());
				assertEquals("Error in branch refs/heads/newbranch", rru.getMessage());
			}
		}
	}

	@Test
	public void testPushCommand() throws IOException, NoHeadException, NoMessageException, UnmergedPathsException,
			ConcurrentRefUpdateException, WrongRepositoryStateException, AbortedByHookException, GitAPIException {
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
		final Repository localRepository = clientGit.getRepository();
		final ObjectId oldId = localRepository.resolve(Constants.HEAD);
		RemoteTestUtils.copy("/test3.json", storePath);
		clientGit.add().addFilepattern(STORE).call();
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
		assertEquals("Error in branch " + REF_HEADS_MASTER, rru.getMessage());
		final File newFolder = folder.newFolder();
		try (Git git = Git.cloneRepository().setDirectory(newFolder)
				.setURI(remoteBareGit.getRepository().getDirectory().getAbsolutePath()).call()) {
			byte[] readAllBytes = Files.readAllBytes(newFolder.toPath().resolve(STORE));
			assertArrayEquals(data, readAllBytes);
		}
	}

	@Test
	public void testRepositoryFailsIOException() throws Exception {
		Repository remoteRepository = Mockito.spy(remoteBareGit.getRepository());
		final String errormsg = "Triggered fault";
		Mockito.doThrow(new IOException(errormsg)).when(remoteRepository).findRef(Mockito.any());
		RemoteTestUtils.copy("/test3.json", storePath);
		clientGit.add().addFilepattern(STORE).call();
		ObjectId oldRef = clientGit.getRepository().resolve(REF_HEADS_MASTER);
		RevCommit c = clientGit.commit().setMessage("New commit").call();

		ReceiveCommand rc = new ReceiveCommand(oldRef, c.getId(), REF_HEADS_MASTER, Type.UPDATE);

		JitstaticReceivePack rp = new JitstaticReceivePack(remoteRepository, REF_HEADS_MASTER, null, null) {
			@Override
			protected List<ReceiveCommand> filterCommands(Result want) {
				return Arrays.asList(rc);
			}
		};
		rp.executeCommands();

		assertEquals(Result.REJECTED_NOCREATE, rc.getResult());
		assertEquals("Couldn't create test branch for " + REF_HEADS_MASTER + " because " + errormsg, rc.getMessage());
		assertEquals(null, rp.getFault());
	}

	@Test
	public void testRepositoryFailsIOExceptionWhenGettingRef() throws Exception {
		Repository remoteRepository = Mockito.mock(Repository.class);
		RefUpdate ru = Mockito.mock(RefUpdate.class);

		final String errormsg = "Triggered fault";

		RemoteTestUtils.copy("/test3.json", storePath);
		clientGit.add().addFilepattern(STORE).call();
		ObjectId oldRef = clientGit.getRepository().resolve(REF_HEADS_MASTER);
		RevCommit c = clientGit.commit().setMessage("New commit").call();

		Mockito.when(remoteRepository.getConfig()).thenReturn(remoteBareGit.getRepository().getConfig());

		ReceiveCommand rc = Mockito.spy(new ReceiveCommand(oldRef, c.getId(), REF_HEADS_MASTER, Type.UPDATE));

		Mockito.when(remoteRepository.updateRef(Mockito.any())).thenReturn(ru);
		Mockito.when(ru.update(Mockito.any())).thenReturn(RefUpdate.Result.NEW);
		Mockito.when(ru.forceUpdate()).thenReturn(RefUpdate.Result.NEW);
		Mockito.when(ru.delete()).thenReturn(RefUpdate.Result.FAST_FORWARD);
		Mockito.when(rc.getResult()).thenReturn(Result.OK).thenCallRealMethod();
		Mockito.when(remoteRepository.findRef(Mockito.anyString())).thenThrow(new IOException(errormsg));

		JitstaticReceivePack rp = new JitstaticReceivePack(remoteRepository, REF_HEADS_MASTER, null, null) {
			@Override
			protected List<ReceiveCommand> filterCommands(Result want) {
				return Arrays.asList(rc);
			}
		};
		rp.executeCommands();

		assertEquals(errormsg, rc.getMessage());
		assertEquals(Result.REJECTED_OTHER_REASON, rc.getResult());
		assertEquals(errormsg, rp.getFault().getMessage());
	}

	@Test
	public void testRepositoryFailsIOExceptionWhenDeletingTemporaryBranch() throws Exception {
		Repository remoteRepository = Mockito.mock(Repository.class);
		RefUpdate ru = Mockito.mock(RefUpdate.class);

		final String errormsg = "Test Triggered fault";

		RemoteTestUtils.copy("/test3.json", storePath);
		clientGit.add().addFilepattern(STORE).call();
		ObjectId oldRef = clientGit.getRepository().resolve(REF_HEADS_MASTER);
		RevCommit c = clientGit.commit().setMessage("New commit").call();

		Mockito.when(remoteRepository.getConfig()).thenReturn(remoteBareGit.getRepository().getConfig());

		ReceiveCommand rc = Mockito.spy(new ReceiveCommand(oldRef, c.getId(), REF_HEADS_MASTER, Type.UPDATE));

		Mockito.when(remoteRepository.updateRef(Mockito.any())).thenReturn(ru);
		Mockito.when(ru.update(Mockito.any())).thenReturn(RefUpdate.Result.NEW);
		Mockito.when(ru.forceUpdate()).thenReturn(RefUpdate.Result.NEW);
		Mockito.when(ru.delete()).thenThrow(new RuntimeException(errormsg));
		Mockito.when(rc.getResult()).thenReturn(Result.OK).thenCallRealMethod();
		Mockito.when(remoteRepository.findRef(Mockito.anyString())).thenThrow(new IOException(errormsg));

		JitstaticReceivePack rp = new JitstaticReceivePack(remoteRepository, REF_HEADS_MASTER, null, null) {
			@Override
			protected List<ReceiveCommand> filterCommands(Result want) {
				return Arrays.asList(rc);
			}
		};
		rp.executeCommands();

		assertEquals(errormsg, rc.getMessage());
		assertEquals(Result.REJECTED_OTHER_REASON, rc.getResult());
		assertEquals("General error while deleting branches " + errormsg, rp.getFault().getMessage());
	}

	@Test
	public void testRepositoryFailsGeneralError() throws Exception {
		Repository remoteRepository = Mockito.mock(Repository.class);
		RefUpdate ru = Mockito.mock(RefUpdate.class);

		final String errormsg = "Triggered error";

		RemoteTestUtils.copy("/test3.json", storePath);
		clientGit.add().addFilepattern(STORE).call();
		ObjectId oldRef = clientGit.getRepository().resolve(REF_HEADS_MASTER);
		RevCommit c = clientGit.commit().setMessage("New commit").call();

		Mockito.when(remoteRepository.getConfig()).thenReturn(remoteBareGit.getRepository().getConfig());

		ReceiveCommand rc = Mockito.spy(new ReceiveCommand(oldRef, c.getId(), REF_HEADS_MASTER, Type.UPDATE));
		JitstaticReceivePack rp = Mockito.spy(new JitstaticReceivePack(remoteRepository, REF_HEADS_MASTER, null, null) {
			@Override
			protected List<ReceiveCommand> filterCommands(Result want) {
				return Arrays.asList(rc);
			}
		});
		Mockito.when(remoteRepository.updateRef(Mockito.any())).thenReturn(ru);
		Mockito.when(ru.update(Mockito.any())).thenReturn(RefUpdate.Result.NEW);
		Mockito.when(ru.forceUpdate()).thenReturn(RefUpdate.Result.NEW);
		Mockito.when(ru.delete()).thenReturn(RefUpdate.Result.FAST_FORWARD);
		Mockito.when(rc.getResult()).thenReturn(Result.OK).thenCallRealMethod();
		Mockito.doThrow(new RuntimeException(errormsg)).doCallRealMethod().when(rp).sendMessage(Mockito.any());

		rp.executeCommands();

		assertEquals("General error " + errormsg, rc.getMessage());
		assertEquals(Result.REJECTED_OTHER_REASON, rc.getResult());
		assertEquals("General error " + errormsg, rp.getFault().getMessage());
	}

	@Test
	public void testDeleteDefaultBranch() throws RefAlreadyExistsException, RefNotFoundException,
			InvalidRefNameException, GitAPIException, IOException {
		clientGit.branchCreate().setName("other").call();
		Ref ref = clientGit.getRepository().findRef(REF_HEADS_MASTER);
		clientGit.checkout().setName("other").call();
		clientGit.branchDelete().setBranchNames(REF_HEADS_MASTER).call();
		ReceiveCommand rc = new ReceiveCommand(ref.getObjectId(), ObjectId.zeroId(), REF_HEADS_MASTER, Type.DELETE);
		JitstaticReceivePack rp = Mockito
				.spy(new JitstaticReceivePack(remoteBareGit.getRepository(), REF_HEADS_MASTER, null, null) {
					@Override
					protected List<ReceiveCommand> filterCommands(Result want) {
						return Arrays.asList(rc);
					}
				});
		rp.executeCommands();
		assertEquals(Result.REJECTED_NODELETE, rc.getResult());
		assertEquals(null, rp.getFault());
	}
}
