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
import static org.junit.Assert.assertNotEquals;
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
import java.util.Map;
import java.util.concurrent.CompletionException;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefDatabase;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jitstatic.CorruptedSourceException;
import jitstatic.RepositoryIsMissingIntendedBranch;
import jitstatic.source.SourceEventListener;
import jitstatic.source.SourceInfo;
import jitstatic.utils.LinkedException;
import jitstatic.utils.VersionIsNotSameException;
import jitstatic.utils.WrappingAPIException;

public class RemoteRepositoryManagerTest {
	private static final String METADATA = ".metadata";
	private static final ObjectMapper MAPPER = new ObjectMapper();
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
		ex.expectMessage("defaultRef cannot be null");
		try (RemoteRepositoryManager rrm = new RemoteRepositoryManager(remoteFolder.toURI(), null, null, workingDir, null)) {
		}
	}

	@Test
	public void testRemoteRepositoryManagerRequireDefaultBranch()
			throws CorruptedSourceException, IOException, IllegalStateException, GitAPIException {
		setUpRepo();
		final String branch = "somebranch";
		ex.expect(RuntimeException.class);
		ex.expectCause(Matchers.isA(RefNotFoundException.class));
		try (RemoteRepositoryManager rrm = new RemoteRepositoryManager(remoteFolder.toURI(), null, null, workingDir, branch)) {
		}
	}

	@Test
	public void testRemoteRepositoryManagerNullRemoteEndpoint() throws CorruptedSourceException, IOException {
		ex.expect(NullPointerException.class);
		ex.expectMessage("remote endpoint cannot be null");
		try (RemoteRepositoryManager rrm = new RemoteRepositoryManager(null, null, null, workingDir, REF_HEADS_MASTER);) {
		}
	}

	@Test
	public void testRemoteRepositoryManagerWithValidBranch() throws Exception {
		setUpRepo();
		try (RemoteRepositoryManager rrm = new RemoteRepositoryManager(remoteFolder.toURI(), null, null, workingDir, REF_HEADS_MASTER)) {
		}
	}

	@Test
	public void testRemoteRepositoryManager() throws IOException, IllegalStateException, GitAPIException, CorruptedSourceException {
		setUpRepo();
		try (RemoteRepositoryManager rrm = new RemoteRepositoryManager(remoteFolder.toURI(), null, null, workingDir, REF_HEADS_MASTER)) {
		}
	}

	@Test
	public void testCheckSuccessfulRemotePoll() throws Exception {
		try (Git git = Git.init().setDirectory(remoteFolder).call()) {
			Files.write(remoteFolder.toPath().resolve(STORAGE), "{\"data\":\"value1\"}".getBytes("UTF-8"));
			Files.write(remoteFolder.toPath().resolve(STORAGE + METADATA),
					"{\"users\":[{\"user\":\"user\",\"password\":\"1234\"}]}".getBytes("UTF-8"));
			git.add().addFilepattern(STORAGE + METADATA).call();
			git.add().addFilepattern(STORAGE).call();
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
			Files.write(remoteFolder.toPath().resolve(STORAGE), "{\"data\":\"value1\"}".getBytes("UTF-8"));
			Files.write(remoteFolder.toPath().resolve(STORAGE + METADATA),
					"{\"users\":[{\"user\":\"user\",\"password\":\"1234\"}]}".getBytes("UTF-8"));
			git.add().addFilepattern(STORAGE + METADATA).call();
			git.add().addFilepattern(STORAGE).call();
			git.commit().setMessage("First").call();

			try (RemoteRepositoryManager rrm = new RemoteRepositoryManager(remoteFolder.toURI(), user, password, workingDir,
					REF_HEADS_MASTER);) {
				assertNull(rrm.getFault());
			}
		}
	}

	@Test
	public void testFailPullIfDefaultBranchHasBeenRemoved() throws Exception {
		setUpRepo();
		try (Git git = Git.open(remoteFolder)) {
			git.branchCreate().setName("other").call();
		}
		try (RemoteRepositoryManager rrm = new RemoteRepositoryManager(remoteFolder.toURI(), null, null, workingDir, "refs/heads/other");) {

			SourceInfo sourceInfo = rrm.getSourceInfo(STORAGE, "refs/heads/other");
			try (InputStream is = sourceInfo.getSourceInputStream()) {
				assertNotNull(is);
			}
			try (Git git = Git.open(remoteFolder)) {
				git.branchDelete().setBranchNames("other").call();
				rrm.checkRemote().run();

				Exception fault = rrm.getFault();
				assertEquals(RepositoryIsMissingIntendedBranch.class, fault.getClass());
				assertEquals("refs/heads/other", fault.getMessage());

				SourceInfo sourceInfo2 = rrm.getSourceInfo(STORAGE, "refs/heads/other");
				try (InputStream is = sourceInfo2.getSourceInputStream()) {
					assertNotNull(is);
				}
			}
		}
	}

	@Test
	public void testDeleteBranch() throws Exception {
		setUpRepo();
		try (Git git = Git.open(remoteFolder)) {
			git.branchCreate().setName("other").call();
			try (RemoteRepositoryManager rrm = new RemoteRepositoryManager(remoteFolder.toURI(), null, null, workingDir,
					REF_HEADS_MASTER);) {

				SourceInfo sourceInfo = rrm.getSourceInfo(STORAGE, "refs/heads/other");
				try (InputStream is = sourceInfo.getSourceInputStream()) {
					assertNotNull(is);
				}
				git.branchDelete().setBranchNames("other").call();
				rrm.checkRemote().run();
				assertNull(rrm.getFault());
				ex.expect(RefNotFoundException.class);
				rrm.getSourceInfo(STORAGE, "refs/heads/other");
			}
		}
	}

	@Test
	public void testCheckFailedRemotePoll() throws Exception {
		setUpRepo();
		ex.expect(TransportException.class);
		try (RemoteRepositoryManager rrm = new RemoteRepositoryManager(remoteFolder.toURI(), null, null, workingDir, REF_HEADS_MASTER);) {
			Files.walk(remoteFolder.toPath()).filter(Files::isRegularFile).map(Path::toFile).forEach(File::delete);
			rrm.checkRemote().run();
			Exception fault = rrm.getFault();
			assertNotNull(fault);
			throw fault;
		}
	}

	@Test
	public void testMasterBranchNotFound() throws IllegalStateException, GitAPIException, IOException, CorruptedSourceException {
		try (Git git = Git.init().setDirectory(remoteFolder).call();) {
			Files.write(Paths.get(remoteFolder.getAbsolutePath(), STORAGE + METADATA), getMetaData().getBytes("UTF-8"));
			Files.write(Paths.get(remoteFolder.getAbsolutePath(), STORAGE), getData().getBytes("UTF-8"));
			git.add().addFilepattern(STORAGE).call();
			git.add().addFilepattern(STORAGE + METADATA).call();
			git.commit().setMessage("Initial commit").call();

			try (final RemoteRepositoryManager rrm = new RemoteRepositoryManager(remoteFolder.toURI(), null, null, workingDir,
					REF_HEADS_MASTER);) {
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
	public void testCheckUpdatedRemotePoll() throws IllegalStateException, GitAPIException, IOException, CorruptedSourceException {
		setUpRepo();
		File base = folder.newFolder();
		try (Git git = Git.cloneRepository().setDirectory(base).setURI(remoteFolder.toURI().toString()).call();
				RemoteRepositoryManager rrm = new RemoteRepositoryManager(remoteFolder.toURI(), null, null, workingDir,
						REF_HEADS_MASTER);) {
			rrm.addListeners(svl);
			Path resolved = base.toPath().resolve(STORAGE);
			Files.write(resolved, "{\"data\":\"value1\",\"users\":[{\"user\":\"user\",\"password\":\"1234\"}]}".getBytes("UTF-8"),
					StandardOpenOption.TRUNCATE_EXISTING);
			git.add().addFilepattern(STORAGE).call();
			git.commit().setMessage("First").call();
			git.push().call();

			rrm.checkRemote().run();
			assertNull(rrm.getFault());
			verify(svl).onEvent(Mockito.eq(Arrays.asList(REF_HEADS_MASTER)));

			Files.write(resolved, "{\"data\":\"value2\",\"users\":[{\"user\":\"user\",\"password\":\"4321\"}]}".getBytes("UTF-8"),
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
			Files.write(resolved, "{\"data\":\"value1\",\"users\":[{\"user\":\"user\",\"password\":\"1234\"}]}".getBytes("UTF-8"),
					StandardOpenOption.TRUNCATE_EXISTING);
			git.add().addFilepattern(STORAGE).call();
			git.commit().setMessage("First").call();
			git.push().call();

			rrm.checkRemote().run();
			assertNull(rrm.getFault());
			verify(svl).onEvent(Mockito.eq(Arrays.asList(REF_HEADS_MASTER)));

			Files.write(resolved, "{\"data\":\"value2\",\"users\":[{\"user\":\"user\",\"password\":\"4321\"}]}".getBytes("UTF-8"),
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
			Path mResolved = base.toPath().resolve(STORAGE + METADATA);
			Files.write(resolved, getData().getBytes("UTF-8"), StandardOpenOption.TRUNCATE_EXISTING);
			Files.write(mResolved, getMetaData().getBytes("UTF-8"), StandardOpenOption.TRUNCATE_EXISTING);
			git.add().addFilepattern(STORAGE).call();
			git.add().addFilepattern(STORAGE + METADATA).call();
			git.commit().setMessage("First").call();
			git.push().call();

			rrm.checkRemote().run();
			assertNull(rrm.getFault());
			verify(svl).onEvent(Mockito.eq(Arrays.asList(REF_HEADS_MASTER)));

			Files.write(resolved, getData(1).getBytes("UTF-8"), StandardOpenOption.TRUNCATE_EXISTING);
			Files.write(mResolved, "{".getBytes("UTF-8"), StandardOpenOption.TRUNCATE_EXISTING);
			git.add().addFilepattern(STORAGE).call();
			git.add().addFilepattern(STORAGE + METADATA).call();
			git.commit().setMessage("Second").call();
			git.push().call();

			rrm.checkRemote().run();
			Exception fault = rrm.getFault();
			assertNotNull(fault);
			assertEquals(LinkedException.class, fault.getClass());
			assertTrue(
					fault.getMessage().startsWith("class jitstatic.CorruptedSourceException: Error in branch refs/remotes/origin/master"));
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
			Files.write(resolved, "{\"data\":\"value1\",\"users\":[{\"user\":\"user\",\"password\":\"1234\"}]}".getBytes("UTF-8"),
					StandardOpenOption.TRUNCATE_EXISTING);
			git.add().addFilepattern(STORAGE).call();
			git.commit().setMessage("First").call();
			git.push().call();

			rrm.checkRemote().run();
			assertNull(rrm.getFault());
			verify(svl).onEvent(Mockito.eq(Arrays.asList(REF_HEADS_MASTER)));

			Files.write(resolved, "{\"data\":\"value2\",\"users\":[{\"user\":\"user\",\"password\":\"4321\"}]}".getBytes("UTF-8"),
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
	public void testFaultyRemote() throws IllegalStateException, GitAPIException, IOException, CorruptedSourceException {
		setUpFaultyRepo();
		ex.expect(CorruptedSourceException.class);
		ex.expectMessage("Error in branch " + REF_HEADS_MASTER);
		try (RemoteRepositoryManager rrm = new RemoteRepositoryManager(remoteFolder.toURI(), null, null, workingDir, REF_HEADS_MASTER);) {
		}
	}

	@Test
	public void testReEntryRemoteRepository() throws IllegalStateException, GitAPIException, IOException, CorruptedSourceException {
		setUpRepo();
		try (RemoteRepositoryManager rrm = new RemoteRepositoryManager(remoteFolder.toURI(), null, null, workingDir, REF_HEADS_MASTER)) {
			assertNull(rrm.getFault());
		}
		try (RemoteRepositoryManager rrm = new RemoteRepositoryManager(remoteFolder.toURI(), null, null, workingDir, REF_HEADS_MASTER)) {
			assertNull(rrm.getFault());
		}

	}

	@Test
	public void testRefNull() throws Exception {
		setUpRepo();
		ex.expect(NullPointerException.class);
		try (RemoteRepositoryManager rrm = new RemoteRepositoryManager(remoteFolder.toURI(), null, null, workingDir, REF_HEADS_MASTER)) {
			rrm.getSourceInfo("key", null);
		}
	}

	@Test
	public void testKeyNull() throws Exception {
		setUpRepo();
		ex.expect(NullPointerException.class);
		try (RemoteRepositoryManager rrm = new RemoteRepositoryManager(remoteFolder.toURI(), null, null, workingDir, REF_HEADS_MASTER)) {
			rrm.getSourceInfo(null, null);
		}
	}

	@Test
	public void testGetStorageInput() throws Exception {
		setUpRepo();
		try (RemoteRepositoryManager rrm = new RemoteRepositoryManager(remoteFolder.toURI(), null, null, workingDir, REF_HEADS_MASTER);) {
			SourceInfo sourceInfo = rrm.getSourceInfo(STORAGE, REF_HEADS_MASTER);
			try (InputStream is = sourceInfo.getSourceInputStream()) {
				assertNotNull(is);
			}
		}
	}

	@Test
	public void testModifyAKey() throws IllegalStateException, GitAPIException, IOException, CorruptedSourceException {
		setUpRepo();
		String message = "commit message";
		String userInfo = "test User";
		String userMail = "test@test.org";
		try (RemoteRepositoryManager rrm = new RemoteRepositoryManager(remoteFolder.toURI(), null, null, workingDir, REF_HEADS_MASTER);) {
			SourceInfo sourceInfo = rrm.getSourceInfo(STORAGE, REF_HEADS_MASTER);
			JsonNode originalData = readJsonData(sourceInfo);
			JsonNode newData = MAPPER.readValue("{\"one\":\"two\"}", JsonNode.class);
			assertNotEquals(originalData, newData);
			String newVersion = rrm.modify(newData, sourceInfo.getSourceVersion(), message, userInfo, userMail, STORAGE, null).join();
			assertNotNull(newVersion);
			assertNotEquals(sourceInfo.getSourceVersion(), newVersion);
			assertNull(rrm.getFault());
			sourceInfo = rrm.getSourceInfo(STORAGE, REF_HEADS_MASTER);
			assertEquals(newData, readJsonData(sourceInfo));
			assertNull(rrm.getFault());
			Git git = Git.open(remoteFolder);
			RevCommit revCommit = getRevCommit(git.getRepository(), REF_HEADS_MASTER);
			assertEquals(message, revCommit.getFullMessage());
		}
	}

	// TODO Better mechanics
	@Test
	public void testModifyAKeyButFailPushing() throws IllegalStateException, GitAPIException, IOException, CorruptedSourceException {
		setUpRepo();
		Path store = remoteFolder.toPath().resolve(STORAGE);
		assertTrue(Files.exists(store));
		String message = "commit message";
		String userInfo = "test User";
		String userMail = "test@test.org";

		try (RemoteRepositoryManager rrm = new RemoteRepositoryManager(remoteFolder.toURI(), null, null, workingDir, REF_HEADS_MASTER);) {
			assertTrue(Files.exists(store));
			SourceInfo sourceInfo = rrm.getSourceInfo(STORAGE, REF_HEADS_MASTER);
			assertTrue(Files.exists(store));
			JsonNode originalData = readJsonData(sourceInfo);
			JsonNode newData = MAPPER.readValue("{\"one\":\"two\"}", JsonNode.class);
			assertNotEquals(originalData, newData);
			Git git = Git.open(remoteFolder);
			Files.write(store, getData(2).getBytes("UTF-8"), StandardOpenOption.TRUNCATE_EXISTING);
			git.add().addFilepattern(STORAGE).call();
			git.commit().setMessage("new commit").call();
			String newVersion = rrm.modify(newData, sourceInfo.getSourceVersion(), message, userInfo, userMail, STORAGE, null).join();
			assertNotNull(newVersion);
			Exception fault = rrm.getFault();
			assertEquals(RemoteUpdateException.class, fault.getClass());
			assertEquals("refs/heads/master isn't updated due to REJECTED_NONFASTFORWARD:null", fault.getMessage());
		}
	}

	@Test
	public void testModifyAKeyWithWrongVersion() throws Throwable {
		setUpRepo();
		ex.expect(WrappingAPIException.class);
		ex.expectCause(Matchers.isA(VersionIsNotSameException.class));
		String message = "commit message";
		String userInfo = "test User";
		String userMail = "test@test.org";
		try (RemoteRepositoryManager rrm = new RemoteRepositoryManager(remoteFolder.toURI(), null, null, workingDir, REF_HEADS_MASTER);) {
			SourceInfo sourceInfo = rrm.getSourceInfo(STORAGE, REF_HEADS_MASTER);
			JsonNode originalData = readJsonData(sourceInfo);
			JsonNode newData = MAPPER.readValue("{\"one\":\"two\"}", JsonNode.class);
			assertNotEquals(originalData, newData);
			try {
				rrm.modify(newData, "1", message, userInfo, userMail, STORAGE, null).join();
			} catch (CompletionException e) {
				throw e.getCause();
			}
		}
	}

	@Test
	public void testModifyAKeyWithMissingBranch() throws Throwable {
		setUpRepo();
		ex.expect(WrappingAPIException.class);
		ex.expectCause(Matchers.isA(RefNotFoundException.class));
		String message = "commit message";
		String userInfo = "test User";
		String userMail = "test@test.org";
		try (RemoteRepositoryManager rrm = new RemoteRepositoryManager(remoteFolder.toURI(), null, null, workingDir, REF_HEADS_MASTER);) {
			JsonNode newData = MAPPER.readValue("{\"one\":\"two\"}", JsonNode.class);
			try {
				rrm.modify(newData, "1", message, userInfo, userMail, STORAGE, "refs/heads/someother").join();
			} catch (CompletionException e) {
				throw e.getCause();
			}
		}
	}

	@Test
	public void testModifyTag() throws CorruptedSourceException, IOException, IllegalStateException, GitAPIException {
		setUpRepo();
		ex.expect(UnsupportedOperationException.class);
		ex.expectMessage("Tags cannot be modified");
		try (RemoteRepositoryManager rrm = new RemoteRepositoryManager(remoteFolder.toURI(), null, null, workingDir, REF_HEADS_MASTER);) {
			rrm.modify(null, "1", "m", "ui", "m", "key", "refs/tags/tag");
		}
	}

	private RevCommit getRevCommit(Repository repository, String targetRef) throws IOException {
		try (RevWalk revWalk = new RevWalk(repository)) {
			revWalk.sort(RevSort.COMMIT_TIME_DESC);
			Map<String, Ref> allRefs = repository.getRefDatabase().getRefs(RefDatabase.ALL);
			Ref actualTargetRef = allRefs.get(targetRef);
			RevCommit commit = revWalk.parseCommit(actualTargetRef.getLeaf().getObjectId());
			revWalk.markStart(commit);
			return revWalk.next();
		}
	}

	private JsonNode readJsonData(SourceInfo sourceInfo) throws IOException, JsonParseException, JsonMappingException {
		assertNotNull(sourceInfo);
		try (InputStream is = sourceInfo.getSourceInputStream()) {
			return MAPPER.readValue(is, JsonNode.class);
		}
	}

	private void setUpRepo() throws IllegalStateException, GitAPIException, IOException {
		try (Git git = Git.init().setBare(false).setDirectory(remoteFolder).call();) {
			Files.write(remoteFolder.toPath().resolve(STORAGE + METADATA), getMetaData().getBytes("UTF-8"));
			Files.write(remoteFolder.toPath().resolve(STORAGE), getData().getBytes("UTF-8"));
			git.add().addFilepattern(".").call();
			git.commit().setMessage("Commit").call();
		}
	}

	private void setUpFaultyRepo() throws IllegalStateException, GitAPIException, IOException {
		final File base = folder.newFolder();
		try (Git bare = Git.init().setBare(true).setDirectory(remoteFolder).call();
				Git git = Git.cloneRepository().setDirectory(base).setURI(remoteFolder.toURI().toString()).call()) {
			Files.write(base.toPath().resolve(STORAGE), "{".getBytes("UTF-8"));
			git.add().addFilepattern(".").call();
			git.commit().setMessage("Commit").call();
			git.push().call();
		}
	}

	private String getData() {
		return getData(1);
	}

	private String getMetaData() {
		return "{\"users\":[{\"password\":\"1234\",\"user\":\"user1\"}]}";
	}

	private String getData(int i) {
		return "{\"key" + i
				+ "\":{\"data\":\"value1\",\"users\":[{\"password\":\"1234\",\"user\":\"user1\"}]},\"key3\":{\"data\":\"value3\",\"users\":[{\"password\":\"1234\",\"user\":\"user1\"}]}}";
	}
}
