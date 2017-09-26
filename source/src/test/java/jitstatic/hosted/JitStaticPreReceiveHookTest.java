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

import static org.mockito.Mockito.*;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.PushConnection;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceiveCommand.Result;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.RemoteRefUpdate.Status;
import org.eclipse.jgit.transport.TestProtocol;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.resolver.ReceivePackFactory;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;
import org.eclipse.jgit.transport.resolver.ServiceNotEnabledException;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;

public class JitStaticPreReceiveHookTest {

	private static final String master = "refs/heads/master";
	private static final String store = "store";
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

	private Git bareGit;
	private Git workingGit;
	private Path storePath;
	private TestProtocol<Object> protocol;
	private URIish uri;

	@Before
	public void setup() throws IllegalStateException, GitAPIException, IOException {
		bareGit = Git.init().setDirectory(folder.newFolder()).setBare(true).call();
		File workingFolder = folder.newFolder();
		workingGit = Git.cloneRepository().setURI(bareGit.getRepository().getDirectory().getAbsolutePath())
				.setDirectory(workingFolder).call();
		storePath = workingFolder.toPath().resolve(store);
		Files.write(storePath, data, StandardOpenOption.CREATE);
		workingGit.add().addFilepattern(store).call();
		workingGit.commit().setMessage("Initial commit").call();
		workingGit.push().call();

		protocol = new TestProtocol<Object>(null, new ReceivePackFactory<Object>() {
			@Override
			public ReceivePack create(Object req, Repository db)
					throws ServiceNotEnabledException, ServiceNotAuthorizedException {
				final ReceivePack receivePack = new ReceivePack(db);
				receivePack.setPreReceiveHook(new JitStaticPreReceiveHook(store, master));
				return receivePack;
			}
		});
		uri = protocol.register(o, bareGit.getRepository());
	}

	@After
	public void tearDown() {
		bareGit.close();
		workingGit.close();
		Transport.unregister(protocol);
	}

	@Test
	public void testOnPreReceiveNewCommitValidJSON() throws Exception {
		final Repository repository = workingGit.getRepository();
		final ObjectId oldId = repository.resolve(Constants.HEAD);
		RemoteTestUtils.copy("/test3.json", storePath);
		workingGit.add().addFilepattern(store).call();
		RevCommit c = workingGit.commit().setMessage("New commit").call();
		assertFalse(oldId.equals(c));
		Ref ref = repository.findRef(master);

		RemoteRefUpdate rru = new RemoteRefUpdate(repository, ref, master, true, null, oldId);
		Map<String, RemoteRefUpdate> updates = new HashMap<>();
		updates.put(rru.getRemoteName(), rru);

		try (Transport tn = protocol.open(uri, repository, "server"); PushConnection connection = tn.openPush()) {
			connection.push(NullProgressMonitor.INSTANCE, updates);
			String messages = connection.getMessages();
			System.out.println(messages);
		}
		assertEquals(Status.OK, rru.getStatus());
	}

	@Test
	public void testOnPreReceiveFaultyJSONShouldDenyCommit() throws Exception {
		final Repository repository = workingGit.getRepository();
		final ObjectId oldId = repository.resolve(Constants.HEAD);
		RemoteTestUtils.copy("/test4.json", storePath);
		workingGit.add().addFilepattern(store).call();
		final RevCommit c = workingGit.commit().setMessage("New commit").call();
		assertFalse(oldId.equals(c));
		final Ref ref = repository.findRef(master);

		final RemoteRefUpdate rru = new RemoteRefUpdate(repository, ref, master, true, null, oldId);
		final Map<String, RemoteRefUpdate> updates = new HashMap<>();
		updates.put(rru.getRemoteName(), rru);

		try (Transport tn = protocol.open(uri, repository, "server"); PushConnection connection = tn.openPush()) {
			connection.push(NullProgressMonitor.INSTANCE, updates);
			String messages = connection.getMessages();
			System.out.println(messages);
		}
		assertEquals(Status.REJECTED_OTHER_REASON, rru.getStatus());
		assertEquals("File is not valid JSON at line: 50, column: 4", rru.getMessage());
		final File newFolder = folder.newFolder();
		try (Git git = Git.cloneRepository().setDirectory(newFolder)
				.setURI(bareGit.getRepository().getDirectory().getAbsolutePath()).call()) {
			byte[] readAllBytes = Files.readAllBytes(newFolder.toPath().resolve(store));
			assertArrayEquals(data, readAllBytes);
		}
	}

	@Test
	public void testPrereviveNoBranchFound() {
		ReceivePack rp = mock(ReceivePack.class);
		ReceiveCommand rc = mock(ReceiveCommand.class);
		when(rc.getRefName()).thenReturn("other");
		ArgumentCaptor<String> ac = ArgumentCaptor.forClass(String.class);

		JitStaticPreReceiveHook js = new JitStaticPreReceiveHook("store", "master");
		List<ReceiveCommand> commands = new ArrayList<>();
		commands.add(rc);
		js.onPreReceive(rp, commands);
		verify(rp).sendMessage(ac.capture());
		assertEquals("Branch master is not present in this push. Not checking.", ac.getValue());

	}

	@Test
	public void testFailedPushCommand() {
		ReceivePack rp = mock(ReceivePack.class);
		ReceiveCommand rc = mock(ReceiveCommand.class);
		when(rc.getRefName()).thenReturn("master");
		when(rc.getResult()).thenReturn(Result.REJECTED_CURRENT_BRANCH);

		JitStaticPreReceiveHook js = new JitStaticPreReceiveHook("store", "master");
		List<ReceiveCommand> commands = new ArrayList<>();
		commands.add(rc);
		js.onPreReceive(rp, commands);

	}

	// @Test
	// public void testFailed() {}
}
