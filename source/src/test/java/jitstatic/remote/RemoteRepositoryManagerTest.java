package jitstatic.remote;

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


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;

import jitstatic.source.Source.Contact;
import jitstatic.source.SourceEventListener;

public class RemoteRepositoryManagerTest {

	@ClassRule
	public static TemporaryFolder folder = new TemporaryFolder();

	@Rule
	public ExpectedException ex = ExpectedException.none();

	private SourceEventListener svl = mock(SourceEventListener.class);

	private File newFolder;

	@Before
	public void setup() throws IOException {
		newFolder = folder.newFolder();
	}

	@Test
	public void testRemoteRepostioryManager() {
		ex.expect(NullPointerException.class);
		ex.expectMessage("Remote endpoint cannot be null");
		new RemoteRepositoryManager(null, null, null);
	}

	@Test
	public void testRemoteRepositoryManager() throws IOException, IllegalStateException, GitAPIException {
		try (Git git = Git.init().setDirectory(newFolder).call()) {
			new RemoteRepositoryManager(newFolder.toURI(), null, null);
		}
	}

	@Test
	public void testGetRemoteRepo() throws IllegalStateException, GitAPIException {
		try (Git git = Git.init().setDirectory(newFolder).call()) {
			RemoteRepositoryManager rrm = new RemoteRepositoryManager(newFolder.toURI(), null, null);
			assertEquals(newFolder.toURI(), rrm.repositoryURI());
		}
	}

	@Test
	public void testCheckSuccessfulRemotePollOnEmptyOrMissingBranchRepository()
			throws IllegalStateException, GitAPIException {
		try (Git git = Git.init().setDirectory(newFolder).call()) {
			RemoteRepositoryManager rrm = new RemoteRepositoryManager(newFolder.toURI(), null, null);
			rrm.addListeners(svl);
			rrm.checkRemote().run();
			verify(svl, never()).onEvent();
			assertTrue(rrm.getFault() instanceof RepositoryIsMissingIntendedBranch);
		}
	}

	@Test
	public void testCheckSuccessfulRemotePoll() throws IllegalStateException, GitAPIException, IOException {
		try (Git git = Git.init().setDirectory(newFolder).call()) {
			Path fileData = Files.write(Paths.get(newFolder.getAbsolutePath(), UUID.randomUUID().toString()),
					UUID.randomUUID().toString().getBytes());
			git.add().addFilepattern(fileData.getFileName().toString()).call();
			ObjectId id = git.commit().setMessage("First").call().getId();

			RemoteRepositoryManager rrm = new RemoteRepositoryManager(newFolder.toURI(), null, null);
			rrm.addListeners(svl);
			assertEquals(null, rrm.getLatestSHA());
			rrm.checkRemote().run();
			verify(svl).onEvent();
			assertEquals(id.getName(), rrm.getLatestSHA());
		}
	}

	@Test
	public void testCheckFailedRemotePoll() {
		RemoteRepositoryManager rrm = new RemoteRepositoryManager(newFolder.toURI(), null, null);
		rrm.checkRemote().run();
		assertNotNull(rrm.getFault());
	}

	@Test
	public void testMasterBranchNotFound() throws IllegalStateException, GitAPIException, IOException {
		try (Git git = Git.init().setDirectory(newFolder).call()) {
			RemoteRepositoryManager rrm = new RemoteRepositoryManager(newFolder.toURI(), null, null);
			rrm.addListeners(svl);
			Files.write(Paths.get(newFolder.getAbsolutePath(), UUID.randomUUID().toString()),
					UUID.randomUUID().toString().getBytes());
			git.commit().setMessage("Initial commit").call();
			git.checkout().setName("other").setCreateBranch(true).call();
			git.branchDelete().setBranchNames("master").setForce(true).call();
			rrm.checkRemote().run();
			assertEquals(RepositoryIsMissingIntendedBranch.class, rrm.getFault().getClass());
		}
	}

	@Test
	public void testCheckUpdatedRemotePoll() throws IllegalStateException, GitAPIException, IOException {
		try (Git git = Git.init().setDirectory(newFolder).call()) {
			RemoteRepositoryManager rrm = new RemoteRepositoryManager(newFolder.toURI(), null, null);
			rrm.addListeners(svl);
			Path fileData = Files.write(Paths.get(newFolder.getAbsolutePath(), UUID.randomUUID().toString()),
					UUID.randomUUID().toString().getBytes());
			git.add().addFilepattern(fileData.getFileName().toString()).call();
			ObjectId id = git.commit().setMessage("First").call().getId();
			rrm.checkRemote().run();
			verify(svl).onEvent();
			assertEquals(id.getName(), rrm.getLatestSHA());
			Files.write(fileData, UUID.randomUUID().toString().getBytes());
			git.add().addFilepattern(fileData.getFileName().toString()).call();
			id = git.commit().setMessage("Second").call().getId();
			rrm.checkRemote().run();
			verify(svl, times(2)).onEvent();
			assertEquals(id.getName(), rrm.getLatestSHA());
		}
	}

	@Test
	public void testRemoteContact() throws URISyntaxException {
		final String user = "user";
		final String password = "password";
		RemoteRepositoryManager rrm = new RemoteRepositoryManager(newFolder.toURI(), user, password);
		Contact contact = rrm.getContact();
		assertEquals(user, contact.getUserName());
		assertEquals(password, contact.getPassword());
	}
}
