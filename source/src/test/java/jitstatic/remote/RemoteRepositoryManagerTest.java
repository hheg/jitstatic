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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;

import jitstatic.CorruptedSourceException;
import jitstatic.LinkedException;
import jitstatic.source.SourceEventListener;
import jitstatic.source.SourceInfo;

public class RemoteRepositoryManagerTest {

	private static final String STORAGE = "storage";
	private static final String REF_HEADS_MASTER = "refs/heads/master";
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
	public void testRemoteRepositoryManagerRequireDefaultBranchNullValue()
			throws CorruptedSourceException, IOException, IllegalStateException, GitAPIException {
		setUpRepo();
		ex.expect(NullPointerException.class);
		ex.expectMessage("defaultBranch cannot be null");
		try (RemoteRepositoryManager rrm = new RemoteRepositoryManager(remoteFolder.toURI(), null, null, workingDir,
				null)) {
		}
	}

	@Test
	public void testRemoteRepositoryManagerRequireDefaultBranch()
			throws CorruptedSourceException, IOException, IllegalStateException, GitAPIException {
		setUpRepo();
		final String branch = "somebranch";
		ex.expect(RepositoryIsMissingIntendedBranch.class);
		ex.expectMessage(branch);
		try (RemoteRepositoryManager rrm = new RemoteRepositoryManager(remoteFolder.toURI(), null, null, workingDir,
				branch)) {
		}
	}

	@Test
	public void testRemoteRepostioryManager() throws CorruptedSourceException, IOException {
		ex.expect(NullPointerException.class);
		ex.expectMessage("remote endpoint cannot be null");
		try (RemoteRepositoryManager rrm = new RemoteRepositoryManager(null, null, null, workingDir,
				REF_HEADS_MASTER);) {
		}
	}

	@Test
	public void testRemoteRepositoryManagerWithValidBranch() throws Exception {
		setUpRepo();
		try (RemoteRepositoryManager rrm = new RemoteRepositoryManager(remoteFolder.toURI(), null, null, workingDir,
				REF_HEADS_MASTER)) {
		}
	}

	@Test
	public void testRemoteRepositoryManager()
			throws IOException, IllegalStateException, GitAPIException, CorruptedSourceException {
		setUpRepo();
		try (RemoteRepositoryManager rrm = new RemoteRepositoryManager(remoteFolder.toURI(), null, null, workingDir,
				REF_HEADS_MASTER)) {
		}
	}

	@Test
	public void testCheckSuccessfulRemotePoll() throws Exception {
		try (Git git = Git.init().setDirectory(remoteFolder).call()) {
			Path fileData = Files.write(remoteFolder.toPath().resolve(STORAGE),
					"{\"data\":\"value1\",\"users\":[{\"user\":\"user\",\"password\":\"1234\"}]}".getBytes("UTF-8"));
			git.add().addFilepattern(fileData.getFileName().toString()).call();
			git.commit().setMessage("First").call();

			try (RemoteRepositoryManager rrm = new RemoteRepositoryManager(remoteFolder.toURI(), null, null, workingDir,
					REF_HEADS_MASTER);) {
				assertNull(rrm.getFault());
			}
		}
	}

	@Test
	public void testCheckSuccessfulRemotePollWithUsers() throws Exception {
		final String user = "user";
		final String password = "password";
		try (Git git = Git.init().setDirectory(remoteFolder).call()) {
			Path fileData = Files.write(remoteFolder.toPath().resolve(STORAGE),
					"{\"data\":\"value1\",\"users\":[{\"user\":\"user\",\"password\":\"1234\"}]}".getBytes("UTF-8"));
			git.add().addFilepattern(fileData.getFileName().toString()).call();
			git.commit().setMessage("First").call();

			try (RemoteRepositoryManager rrm = new RemoteRepositoryManager(remoteFolder.toURI(), user, password,
					workingDir, REF_HEADS_MASTER);) {
				assertNull(rrm.getFault());
			}
		}
	}

	@Test
	public void testFailPullIfDefaultBranchHasBeenRemoved() throws Exception {
		ex.expect(RepositoryIsMissingIntendedBranch.class);
		setUpRepo();
		try (Git git = Git.open(remoteFolder)) {
			git.branchCreate().setName("other").call();
		}
		try (RemoteRepositoryManager rrm = new RemoteRepositoryManager(remoteFolder.toURI(), null, null, workingDir,
				"other");) {
			SourceInfo sourceInfo = rrm.getStorageInputStream(STORAGE, "refs/heads/other");
			try (InputStream is = sourceInfo.getInputStream()) {
				assertNotNull(is);
			}
			try (Git git = Git.init().setDirectory(remoteFolder).call()) {
				git.branchDelete().setBranchNames("other").call();
				rrm.checkRemote().run();
				Exception fault = rrm.getFault();
				SourceInfo sourceInfo2 = rrm.getStorageInputStream(STORAGE, "refs/heads/other");
				try (InputStream is = sourceInfo2.getInputStream()) {
					assertNotNull(is);
				}
				throw fault;
			}
		}
	}

	@Test
	public void testCheckFailedRemotePoll()
			throws IllegalStateException, GitAPIException, IOException, CorruptedSourceException {
		setUpRepo();
		try (RemoteRepositoryManager rrm = new RemoteRepositoryManager(remoteFolder.toURI(), null, null, workingDir,
				REF_HEADS_MASTER);) {
			Files.walk(remoteFolder.toPath()).filter(Files::isRegularFile).map(Path::toFile).forEach(File::delete);
			rrm.checkRemote().run();
			Exception fault = rrm.getFault();
			assertNotNull(fault);
			assertEquals(InvalidRemoteException.class, fault.getClass());
		}
	}

	@Test
	public void testMasterBranchNotFound()
			throws IllegalStateException, GitAPIException, IOException, CorruptedSourceException {
		try (Git git = Git.init().setDirectory(remoteFolder).call();) {
			Files.write(Paths.get(remoteFolder.getAbsolutePath(), STORAGE), getData().getBytes("UTF-8"));
			git.add().addFilepattern(STORAGE).call();
			git.commit().setMessage("Initial commit").call();

			try (final RemoteRepositoryManager rrm = new RemoteRepositoryManager(remoteFolder.toURI(), null, null,
					workingDir, REF_HEADS_MASTER);) {
				git.checkout().setName("other").setCreateBranch(true).call();
				git.branchDelete().setBranchNames("master").setForce(true).call();
				rrm.addListeners(svl);
				rrm.checkRemote().run();
				Exception fault = rrm.getFault();
				assertNotNull(fault);
				assertEquals(RepositoryIsMissingIntendedBranch.class, fault.getClass());
				verify(svl, never()).onEvent(null);
			}
		}
	}

	@Test
	public void testCheckUpdatedRemotePoll()
			throws IllegalStateException, GitAPIException, IOException, CorruptedSourceException {
		setUpRepo();
		File base = folder.newFolder();
		try (Git git = Git.cloneRepository().setDirectory(base).setURI(remoteFolder.toURI().toString()).call();
				RemoteRepositoryManager rrm = new RemoteRepositoryManager(remoteFolder.toURI(), null, null, workingDir,
						REF_HEADS_MASTER);) {
			rrm.addListeners(svl);
			Path resolved = base.toPath().resolve(STORAGE);
			Files.write(resolved,
					"{\"data\":\"value1\",\"users\":[{\"user\":\"user\",\"password\":\"1234\"}]}".getBytes("UTF-8"),
					StandardOpenOption.TRUNCATE_EXISTING);
			git.add().addFilepattern(STORAGE).call();
			git.commit().setMessage("First").call();
			git.push().call();

			rrm.checkRemote().run();
			assertNull(rrm.getFault());
			verify(svl).onEvent(Mockito.eq(Arrays.asList(REF_HEADS_MASTER)));

			Files.write(resolved,
					"{\"data\":\"value2\",\"users\":[{\"user\":\"user\",\"password\":\"4321\"}]}".getBytes("UTF-8"),
					StandardOpenOption.TRUNCATE_EXISTING);
			git.add().addFilepattern(STORAGE).call();
			git.commit().setMessage("Second").call();
			git.push().call();

			rrm.checkRemote().run();
			assertNull(rrm.getFault());
			verify(svl, times(2)).onEvent(Mockito.eq(Arrays.asList(REF_HEADS_MASTER)));

		}
	}

	@Test
	public void testCheckUpdatedRemotePollFailedOnFetch()
			throws IllegalStateException, GitAPIException, IOException, CorruptedSourceException {
		setUpRepo();
		File base = folder.newFolder();
		try (Git git = Git.cloneRepository().setDirectory(base).setURI(remoteFolder.toURI().toString()).call();
				RemoteRepositoryManager rrm = new RemoteRepositoryManager(remoteFolder.toURI(), null, null, workingDir,
						REF_HEADS_MASTER);) {
			rrm.addListeners(svl);
			Path resolved = base.toPath().resolve(STORAGE);
			Files.write(resolved,
					"{\"data\":\"value1\",\"users\":[{\"user\":\"user\",\"password\":\"1234\"}]}".getBytes("UTF-8"),
					StandardOpenOption.TRUNCATE_EXISTING);
			git.add().addFilepattern(STORAGE).call();
			git.commit().setMessage("First").call();
			git.push().call();

			rrm.checkRemote().run();
			assertNull(rrm.getFault());
			verify(svl).onEvent(Mockito.eq(Arrays.asList(REF_HEADS_MASTER)));

			Files.write(resolved,
					"{\"data\":\"value2\",\"users\":[{\"user\":\"user\",\"password\":\"4321\"}]}".getBytes("UTF-8"),
					StandardOpenOption.TRUNCATE_EXISTING);
			git.add().addFilepattern(STORAGE).call();
			git.commit().setMessage("Second").call();
			git.push().call();

			rrm.checkRemote().run();
			assertNull(rrm.getFault());
			verify(svl, times(2)).onEvent(Mockito.eq(Arrays.asList(REF_HEADS_MASTER)));

		}
	}

	@Test
	public void testCheckUpdatedRemotePollFailedOnMerge()
			throws IllegalStateException, GitAPIException, IOException, CorruptedSourceException {
		setUpRepo();
		File base = folder.newFolder();
		try (Git git = Git.cloneRepository().setDirectory(base).setURI(remoteFolder.toURI().toString()).call();
				RemoteRepositoryManager rrm = new RemoteRepositoryManager(remoteFolder.toURI(), null, null, workingDir,
						REF_HEADS_MASTER);) {
			rrm.addListeners(svl);
			Path resolved = base.toPath().resolve(STORAGE);
			Files.write(resolved,
					"{\"data\":\"value1\",\"users\":[{\"user\":\"user\",\"password\":\"1234\"}]}".getBytes("UTF-8"),
					StandardOpenOption.TRUNCATE_EXISTING);
			git.add().addFilepattern(STORAGE).call();
			git.commit().setMessage("First").call();
			git.push().call();

			rrm.checkRemote().run();
			assertNull(rrm.getFault());
			verify(svl).onEvent(Mockito.eq(Arrays.asList(REF_HEADS_MASTER)));

			Files.write(resolved,
					"\"data\":\"value2\",\"users\":[{\"user\":\"user\",\"password\":\"4321\"}]}".getBytes("UTF-8"),
					StandardOpenOption.TRUNCATE_EXISTING);
			git.add().addFilepattern(STORAGE).call();
			git.commit().setMessage("Second").call();
			git.push().call();

			rrm.checkRemote().run();
			Exception fault = rrm.getFault();
			assertEquals(LinkedException.class, fault.getClass());
			assertTrue(fault.getMessage().startsWith("class jitstatic.CorruptedSourceException: Error in branch refs/remotes/origin/master"));
			verify(svl, times(1)).onEvent(Mockito.eq(Arrays.asList(REF_HEADS_MASTER)));

		}
	}

	@Test
	public void testCheckUpdatedRemotePollFailedOnStorage()
			throws IllegalStateException, GitAPIException, IOException, CorruptedSourceException {
		setUpRepo();
		File base = folder.newFolder();
		try (Git git = Git.cloneRepository().setDirectory(base).setURI(remoteFolder.toURI().toString()).call();
				RemoteRepositoryManager rrm = new RemoteRepositoryManager(remoteFolder.toURI(), null, null, workingDir,
						REF_HEADS_MASTER);) {
			rrm.addListeners(svl);
			Path resolved = base.toPath().resolve(STORAGE);
			Files.write(resolved,
					"{\"data\":\"value1\",\"users\":[{\"user\":\"user\",\"password\":\"1234\"}]}".getBytes("UTF-8"),
					StandardOpenOption.TRUNCATE_EXISTING);
			git.add().addFilepattern(STORAGE).call();
			git.commit().setMessage("First").call();
			git.push().call();

			rrm.checkRemote().run();
			assertNull(rrm.getFault());
			verify(svl).onEvent(Mockito.eq(Arrays.asList(REF_HEADS_MASTER)));

			Files.write(resolved,
					"{\"data\":\"value2\",\"users\":[{\"user\":\"user\",\"password\":\"4321\"}]}".getBytes("UTF-8"),
					StandardOpenOption.TRUNCATE_EXISTING);
			git.add().addFilepattern(STORAGE).call();
			git.commit().setMessage("Second").call();
			git.push().call();

			rrm.checkRemote().run();
			assertNull(rrm.getFault());
			verify(svl, times(2)).onEvent(Mockito.eq(Arrays.asList(REF_HEADS_MASTER)));

		}
	}

	@Test
	public void testFaultyRemote()
			throws IllegalStateException, GitAPIException, IOException, CorruptedSourceException {
		setUpFaultyRepo();
		ex.expect(CorruptedSourceException.class);
		ex.expectMessage("Error in branch " + REF_HEADS_MASTER);
		try (RemoteRepositoryManager rrm = new RemoteRepositoryManager(remoteFolder.toURI(), null, null, workingDir,
				REF_HEADS_MASTER);) {
		}
	}

	@Test
	public void testReEntryRemoteRepository()
			throws IllegalStateException, GitAPIException, IOException, CorruptedSourceException {
		setUpRepo();
		try (RemoteRepositoryManager rrm = new RemoteRepositoryManager(remoteFolder.toURI(), null, null, workingDir,
				REF_HEADS_MASTER)) {
			assertNull(rrm.getFault());
		}
		try (RemoteRepositoryManager rrm = new RemoteRepositoryManager(remoteFolder.toURI(), null, null, workingDir,
				REF_HEADS_MASTER)) {
			assertNull(rrm.getFault());
		}

	}

	@Test
	public void testRefNull() throws Exception {
		setUpRepo();
		ex.expect(NullPointerException.class);
		try (RemoteRepositoryManager rrm = new RemoteRepositoryManager(remoteFolder.toURI(), null, null, workingDir,
				REF_HEADS_MASTER)) {
			rrm.getStorageInputStream("key", null);
		}
	}

	@Test
	public void testKeyNull() throws Exception {
		setUpRepo();
		ex.expect(NullPointerException.class);
		try (RemoteRepositoryManager rrm = new RemoteRepositoryManager(remoteFolder.toURI(), null, null, workingDir,
				REF_HEADS_MASTER)) {
			rrm.getStorageInputStream(null, null);
		}
	}

	@Test
	public void testGetStorageInput() throws Exception {
		setUpRepo();
		// ex.expect(RuntimeException.class);
		try (RemoteRepositoryManager rrm = new RemoteRepositoryManager(remoteFolder.toURI(), null, null, workingDir,
				REF_HEADS_MASTER); ) {
			 SourceInfo sourceInfo = rrm.getStorageInputStream(STORAGE, REF_HEADS_MASTER);
			try(InputStream is = sourceInfo.getInputStream()){
				assertNotNull(is);
			}
		}
	}

	private void setUpRepo() throws IllegalStateException, GitAPIException, IOException {
		final File base = folder.newFolder();
		try (Git bare = Git.init().setBare(true).setDirectory(remoteFolder).call();
				Git git = Git.cloneRepository().setDirectory(base).setURI(remoteFolder.toURI().toString()).call()) {
			Files.write(base.toPath().resolve(STORAGE), getData().getBytes("UTF-8"));
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

	private String getData() {
		return getData(1);
	}

	private String getData(int i) {
		return "{\"data\":{\"key" + i
				+ "\":{\"data\":\"value1\",\"users\":[{\"password\":\"1234\",\"user\":\"user1\"}]},\"key3\":{\"data\":\"value3\",\"users\":[{\"password\":\"1234\",\"user\":\"user1\"}]}},\"users\":[{\"password\":\"1234\",\"user\":\"user1\"}]}";
	}
}
