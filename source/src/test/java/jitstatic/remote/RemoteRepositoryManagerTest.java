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
import static org.hamcrest.Matchers.isA;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;

import com.fasterxml.jackson.core.io.JsonEOFException;

import jitstatic.hosted.BranchNotFoundException;
import jitstatic.source.SourceEventListener;

public class RemoteRepositoryManagerTest {

	private static final String STORAGE = "storage";
	private static final String BRANCH = "refs/heads/master";
	@ClassRule
	public final static TemporaryFolder folder = new TemporaryFolder();

	@Rule
	public final ExpectedException ex = ExpectedException.none();

	private final SourceEventListener svl = mock(SourceEventListener.class);

	private File remoteFolder;
	private Path workingDir;

	@Before
	public void setup() throws IOException, IllegalStateException, GitAPIException {
		remoteFolder = folder.newFolder();
		workingDir = folder.newFolder().toPath();
	}

	@Test
	public void testRemoteRepostioryManager() {
		ex.expect(NullPointerException.class);
		ex.expectMessage("remote endpoint cannot be null");
		try (RemoteRepositoryManager rrm = new RemoteRepositoryManager(null, null, null, null, null, workingDir);) {

		}
	}

	@Test
	public void testRemoteRepositoryManagerWithNotValidBranch() throws Exception {
		final String branch = "other";
		setUpRepo();
		ex.expect(BranchNotFoundException.class);
		ex.expectMessage(branch + " is not found in the repository");
		try (RemoteRepositoryManager rrm = new RemoteRepositoryManager(remoteFolder.toURI(), null, null, branch,
				STORAGE, workingDir)) {
		}
	}

	@Test
	public void testRemoteRepositoryManagerWithValidBranch() throws Exception {
		setUpRepo();
		try (RemoteRepositoryManager rrm = new RemoteRepositoryManager(remoteFolder.toURI(), null, null, BRANCH,
				STORAGE, workingDir)) {
		}
	}

	@Test
	public void testRemoteRepositoryManager() throws IOException, IllegalStateException, GitAPIException {
		setUpRepo();
		try (RemoteRepositoryManager rrm = new RemoteRepositoryManager(remoteFolder.toURI(), null, null, BRANCH,
				STORAGE, workingDir)) {
		}
	}

	@Test
	public void testGetRemoteRepo() throws IllegalStateException, GitAPIException, IOException {
		setUpRepo();
		try (RemoteRepositoryManager rrm = new RemoteRepositoryManager(remoteFolder.toURI(), null, null, BRANCH,
				STORAGE, workingDir)) {
			assertEquals(remoteFolder.toURI(), rrm.repositoryURI());
		}
	}

	@Test
	public void testCheckSuccessfulRemotePoll() throws Exception {
		try (Git git = Git.init().setDirectory(remoteFolder).call()) {
			Path fileData = Files.write(remoteFolder.toPath().resolve(STORAGE),
					"{\"key\":{\"data\":\"value1\",\"users\":[{\"user\":\"user\",\"password\":\"1234\"}]}}"
							.getBytes("UTF-8"));
			git.add().addFilepattern(fileData.getFileName().toString()).call();
			ObjectId id = git.commit().setMessage("First").call().getId();

			try (RemoteRepositoryManager rrm = new RemoteRepositoryManager(remoteFolder.toURI(), null, null, BRANCH,
					STORAGE, workingDir);) {
				rrm.addListeners(svl);
				assertEquals(null, rrm.getLatestSHA());
				rrm.checkRemote().run();
				verify(svl).onEvent();
				assertEquals(id.getName(), rrm.getLatestSHA());
			}
		}
	}

	@Test
	public void testCheckSuccessfulRemotePollWithUsers() throws Exception {
		final String user = "user";
		final String password = "password";
		try (Git git = Git.init().setDirectory(remoteFolder).call()) {
			Path fileData = Files.write(remoteFolder.toPath().resolve(STORAGE),
					"{\"key\":{\"data\":\"value1\",\"users\":[{\"user\":\"user\",\"password\":\"1234\"}]}}"
							.getBytes("UTF-8"));
			git.add().addFilepattern(fileData.getFileName().toString()).call();
			ObjectId id = git.commit().setMessage("First").call().getId();

			try (RemoteRepositoryManager rrm = new RemoteRepositoryManager(remoteFolder.toURI(), user, password, BRANCH,
					STORAGE, workingDir);) {
				rrm.addListeners(svl);
				assertEquals(null, rrm.getLatestSHA());
				rrm.checkRemote().run();
				verify(svl).onEvent();
				assertEquals(id.getName(), rrm.getLatestSHA());
			}
		}
	}

	@Test
	public void testCheckFailedRemotePoll() throws IllegalStateException, GitAPIException, IOException {
		setUpRepo();
		try (RemoteRepositoryManager rrm = new RemoteRepositoryManager(remoteFolder.toURI(), null, null, BRANCH,
				STORAGE, workingDir);) {
			Files.walk(remoteFolder.toPath()).filter(Files::isRegularFile).map(Path::toFile).forEach(File::delete);
			rrm.checkRemote().run();
			Exception fault = rrm.getFault();
			assertNotNull(fault);
			assertTrue(fault instanceof TransportException);
		}
	}

	@Test
	public void testMasterBranchNotFound() throws IllegalStateException, GitAPIException, IOException {

		try (Git git = Git.init().setDirectory(remoteFolder).call();) {
			Files.write(Paths.get(remoteFolder.getAbsolutePath(), STORAGE), "{}".getBytes("UTF-8"));
			git.add().addFilepattern(STORAGE).call();
			git.commit().setMessage("Initial commit").call();

			try (final RemoteRepositoryManager rrm = new RemoteRepositoryManager(remoteFolder.toURI(), null, null,
					BRANCH, STORAGE, workingDir);) {
				git.checkout().setName("other").setCreateBranch(true).call();
				git.branchDelete().setBranchNames("master").setForce(true).call();
				rrm.addListeners(svl);
				rrm.checkRemote().run();
				assertEquals(RepositoryIsMissingIntendedBranch.class, rrm.getFault().getClass());
				verify(svl, never()).onEvent();
			}
		}
	}

	@Test
	public void testCheckUpdatedRemotePoll() throws IllegalStateException, GitAPIException, IOException {
		setUpRepo();
		File base = folder.newFolder();
		try (Git git = Git.cloneRepository().setDirectory(base).setURI(remoteFolder.toURI().toString()).call();
				RemoteRepositoryManager rrm = new RemoteRepositoryManager(remoteFolder.toURI(), null, null, BRANCH,
						STORAGE, workingDir);) {
			rrm.addListeners(svl);
			Path resolved = base.toPath().resolve(STORAGE);
			Files.write(resolved,
					"{\"key\":{\"data\":\"value1\",\"users\":[{\"user\":\"user\",\"password\":\"1234\"}]}}"
							.getBytes("UTF-8"),
					StandardOpenOption.TRUNCATE_EXISTING);
			git.add().addFilepattern(STORAGE).call();
			ObjectId id = git.commit().setMessage("First").call().getId();
			git.push().call();

			rrm.checkRemote().run();
			verify(svl).onEvent();
			assertEquals(id.getName(), rrm.getLatestSHA());
			Files.write(resolved,
					"{\"key\":{\"data\":\"value2\",\"users\":[{\"user\":\"user\",\"password\":\"4321\"}]}}"
							.getBytes("UTF-8"),
					StandardOpenOption.TRUNCATE_EXISTING);
			git.add().addFilepattern(STORAGE).call();
			id = git.commit().setMessage("Second").call().getId();
			git.push().call();

			rrm.checkRemote().run();
			verify(svl, times(2)).onEvent();
			assertEquals(id.getName(), rrm.getLatestSHA());
		}
	}

	@Test
	public void testFaultyRemote() throws IllegalStateException, GitAPIException, IOException {
		setUpFaultyRepo();
		ex.expect(RuntimeException.class);
		ex.expectCause(isA(JsonEOFException.class));
		try (RemoteRepositoryManager rrm = new RemoteRepositoryManager(remoteFolder.toURI(), null, null, BRANCH,
				STORAGE, workingDir);) {

		}
	}

	@Test
	public void testReEntryRemoteRepository() throws IllegalStateException, GitAPIException, IOException {
		setUpRepo();
		try (RemoteRepositoryManager rrm = new RemoteRepositoryManager(remoteFolder.toURI(), null, null, BRANCH,
				STORAGE, workingDir)) {
		}
		try (RemoteRepositoryManager rrm = new RemoteRepositoryManager(remoteFolder.toURI(), null, null, BRANCH,
				STORAGE, workingDir)) {
		}

	}

	@Test
	public void testFaultyBranchName() throws IllegalStateException, GitAPIException, IOException {
		String faultyBranchName = "master.lock";
		setUpRepo();
		ex.expect(BranchNotFoundException.class);
		ex.expectMessage(faultyBranchName + " is not found in the repository");
		try (RemoteRepositoryManager rrm = new RemoteRepositoryManager(remoteFolder.toURI(), null, null,
				faultyBranchName, STORAGE, workingDir);) {

		}
	}

	private void setUpRepo() throws IllegalStateException, GitAPIException, IOException {
		final File base = folder.newFolder();
		try (Git bare = Git.init().setBare(true).setDirectory(remoteFolder).call();
				Git git = Git.cloneRepository().setDirectory(base).setURI(remoteFolder.toURI().toString()).call()) {
			Files.write(base.toPath().resolve(STORAGE), "{}".getBytes("UTF-8"));
			git.add().addFilepattern(STORAGE).call();
			git.commit().setMessage("Commit").call();
			git.push().call();
		}
	}

	private void setUpFaultyRepo() throws IllegalStateException, GitAPIException, IOException {
		final File base = folder.newFolder();
		try (Git bare = Git.init().setBare(true).setDirectory(remoteFolder).call();
				Git git = Git.cloneRepository().setDirectory(base).setURI(remoteFolder.toURI().toString()).call()) {
			Files.write(base.toPath().resolve(STORAGE), "{".getBytes("UTF-8"));
			git.add().addFilepattern(STORAGE).call();
			git.commit().setMessage("Commit").call();
			git.push().call();
		}
	}
}
