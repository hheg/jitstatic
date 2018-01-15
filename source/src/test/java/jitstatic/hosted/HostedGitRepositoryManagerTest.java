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

import static org.hamcrest.Matchers.isA;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.NoFilepatternException;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefDatabase;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate.Status;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;
import org.eclipse.jgit.transport.resolver.ServiceNotEnabledException;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonParser.Feature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jitstatic.CorruptedSourceException;
import jitstatic.remote.RepositoryIsMissingIntendedBranch;
import jitstatic.source.SourceEventListener;
import jitstatic.source.SourceInfo;

public class HostedGitRepositoryManagerTest {

	private static final ObjectMapper MAPPER = new ObjectMapper().enable(Feature.ALLOW_COMMENTS).enable(Feature.STRICT_DUPLICATE_DETECTION);
	private static final String ENDPOINT = "endpoint";
	private static final String REF_HEADS_MASTER = Constants.R_HEADS + Constants.MASTER;
	private static final String STORE = "store";

	@Rule
	public ExpectedException ex = ExpectedException.none();
	@Rule
	public final TemporaryFolder tempFolder = new TemporaryFolder();
	private Path tempFile;
	private Path tempDir;
	private ExecutorService service;

	@Before
	public void setup() throws IOException {
		tempDir = tempFolder.newFolder().toPath();
		tempFile = tempFolder.newFile().toPath();
		service = Executors.newSingleThreadExecutor();
	}

	@After
	public void tearDown() throws InterruptedException {
		service.shutdown();
		service.awaitTermination(10, TimeUnit.SECONDS);
	}

	@Test
	public void testCreatedBareDirectory() throws CorruptedSourceException, IOException {
		try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER);) {
			assertTrue(Files.exists(Paths.get(grm.repositoryURI()).resolve(Constants.HEAD)));
		}
	}

	@Test()
	public void testForDirectory() throws CorruptedSourceException, IOException {
		ex.expect(IllegalArgumentException.class);
		ex.expectMessage(String.format("Path %s is not a directory", tempFile));
		try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempFile, ENDPOINT, REF_HEADS_MASTER);) {
		}
	}

	@Test()
	public void testForWritableDirectory() throws IOException, CorruptedSourceException {
		ex.expect(IllegalArgumentException.class);
		ex.expectMessage(String.format("Path %s is not writeable", tempDir));
		Set<PosixFilePermission> perms = PosixFilePermissions.fromString("r-xr-x---");
		Files.setPosixFilePermissions(tempDir, perms);
		try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER);) {
		}
	}

	@Test
	public void testForNullEndPoint() throws CorruptedSourceException, IOException {
		ex.expect(NullPointerException.class);
		try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, null, REF_HEADS_MASTER);) {
		}
	}

	@Test
	public void testForEmptyEndPoint() throws CorruptedSourceException, IOException {
		ex.expect(IllegalArgumentException.class);
		ex.expectMessage("Parameter endPointName cannot be empty");
		try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, "", REF_HEADS_MASTER);) {
		}
	}

	@Test
	public void testGetRepositoryResolver()
			throws ServiceNotAuthorizedException, ServiceNotEnabledException, CorruptedSourceException, IOException {
		try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER);) {
			Repository open = grm.getRepositoryResolver().open(null, ENDPOINT);
			assertNotNull(open);
		}
	}

	@Test
	public void testNotFoundRepositoryResolver()
			throws ServiceNotAuthorizedException, ServiceNotEnabledException, CorruptedSourceException, IOException {
		ex.expect(RepositoryNotFoundException.class);
		try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER);) {
			grm.getRepositoryResolver().open(null, "something");
		}
	}

	@Test
	public void testMountingOnExistingGitRepository() throws CorruptedSourceException, IOException {
		try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER)) {
		}

		try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER)) {
		}
	}

	@Test
	public void testGetTagSourceStream() throws CorruptedSourceException, IOException, NoFilepatternException, GitAPIException {
		File workFolder = tempFolder.newFolder();
		try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER);
				Git git = Git.cloneRepository().setDirectory(workFolder).setURI(tempDir.toUri().toString()).call();) {
			Path file = workFolder.toPath().resolve("other");
			Files.write(file, getData().getBytes("UTF-8"), StandardOpenOption.CREATE);
			git.add().addFilepattern("other").call();
			git.commit().setMessage("Initial commit").call();
			git.push().call();
			git.tag().setName("tag").call();
			git.push().setPushTags().call();
			SourceInfo sourceInfo = grm.getSourceInfo("other", "refs/tags/tag");
			try (InputStream is = sourceInfo.getInputStream()) {
				assertNotNull(is);
			}
		}
	}

	@Test
	public void testGetSourceStreamNotValid() throws CorruptedSourceException, IOException, RefNotFoundException {
		String ref = "refs/somethingelse/ref";
		ex.expect(RefNotFoundException.class);
		ex.expectMessage(ref);
		try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER)) {
			grm.getSourceInfo("key", ref);
		}
	}

	@Test
	public void testMountingOnDifferentBranches() throws Throwable {
		final String wrongBranch = "wrongbranch";
		final File tmpGit = tempFolder.newFolder();
		ex.expect(RepositoryIsMissingIntendedBranch.class);
		ex.expectMessage(REF_HEADS_MASTER);
		try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER)) {
			// should create a bare repo with a branch other with a store file with content
		}
		try (Git git = Git.cloneRepository().setURI(tempDir.toUri().toString()).setDirectory(tmpGit).call();) {
			git.commit().setMessage("commit something").call();
			git.branchCreate().setName(wrongBranch).call();
			git.checkout().setName(wrongBranch).call();
			git.branchDelete().setBranchNames("master").call();
			git.push().call();
		}
		try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER)) {
		}

	}

	@Test
	public void testInitializingValidRepository()
			throws InvalidRemoteException, TransportException, GitAPIException, IOException, CorruptedSourceException {
		File base = tempFolder.newFolder();
		try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER);
				Git git = Git.cloneRepository().setDirectory(base).setURI(tempDir.toUri().toString()).call()) {
		}
	}

	@Test
	public void testPushingANonJSONFormattedStorageFile() throws Exception {
		ex.expect(CorruptedSourceException.class);
		ex.expectMessage("Error in branch " + REF_HEADS_MASTER);
		try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER)) {
			final File localGitDir = tempFolder.newFolder();
			try (Git git = Git.cloneRepository().setURI(grm.repositoryURI().toString()).setDirectory(localGitDir).call()) {
				Files.write(Paths.get(localGitDir.toURI()).resolve(STORE), "{".getBytes("UTF-8"), StandardOpenOption.CREATE_NEW,
						StandardOpenOption.TRUNCATE_EXISTING);
				git.add().addFilepattern(STORE).call();
				git.commit().setMessage("Test commit").call();
				// This works since there's no check done on the repository
				final Iterable<PushResult> push = git.push().call();
				final PushResult pushResult = push.iterator().next();
				assertEquals(Status.OK, pushResult.getRemoteUpdate(REF_HEADS_MASTER).getStatus());
			}
		}
		try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, null)) {
		}
	}

	@Test
	public void testClosedRepositoryAndInputStream()
			throws IOException, CorruptedSourceException, InvalidRemoteException, TransportException, GitAPIException {
		try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER);) {
			final File localGitDir = tempFolder.newFolder();
			try (Git git = Git.cloneRepository().setURI(grm.repositoryURI().toString()).setDirectory(localGitDir).call()) {
				Files.write(Paths.get(localGitDir.toURI()).resolve(STORE), "{}".getBytes("UTF-8"), StandardOpenOption.CREATE_NEW,
						StandardOpenOption.TRUNCATE_EXISTING);
				git.add().addFilepattern(STORE).call();
				git.commit().setMessage("Test commit").call();
				final Iterable<PushResult> push = git.push().call();
				final PushResult pushResult = push.iterator().next();
				assertEquals(Status.OK, pushResult.getRemoteUpdate(REF_HEADS_MASTER).getStatus());
				SourceInfo sourceInfo = grm.getSourceInfo(STORE, REF_HEADS_MASTER);
				try (InputStream is = sourceInfo.getInputStream()) {
					JsonParser parser = MAPPER.getFactory().createParser(is);
					while (parser.nextToken() != null)
						;
				}
			}
		}
	}

	@Test
	public void testListeners() throws CorruptedSourceException, IOException {
		SourceEventListener svl = mock(SourceEventListener.class);
		try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER);) {
			grm.addListener(svl);
		}
	}

	@Test
	public void testCheckHealth() throws CorruptedSourceException, IOException {
		try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER);) {
			grm.checkHealth();
		}
	}

	@Test
	public void testCheckHealthWithError() throws CorruptedSourceException, IOException {
		ex.expectCause(isA(NullPointerException.class));
		ex.expect(RuntimeException.class);
		NullPointerException npe = new NullPointerException();
		ErrorReporter reporter = new ErrorReporter();
		try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER, service, reporter);) {
			reporter.setFault(npe);
			grm.checkHealth();
		}
	}

	@Test
	public void testModifyKey() throws CorruptedSourceException, IOException, InvalidRemoteException, TransportException, GitAPIException,
			InterruptedException, ExecutionException {
		String message = "modified value";
		String userInfo = "test@test";
		File gitFolder = tempFolder.newFolder();
		try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER);
				Git git = Git.cloneRepository().setURI(tempDir.toUri().toString()).setDirectory(gitFolder).call()) {
			Path keyFile = gitFolder.toPath().resolve(STORE);
			Files.write(keyFile, getData().getBytes("UTF-8"), StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE_NEW);
			git.add().addFilepattern(STORE).call();
			git.commit().setMessage("first").call();
			git.push().call();
			SourceInfo firstSourceInfo = grm.getSourceInfo(STORE, null);
			assertNotNull(firstSourceInfo);
			JsonNode firstValue = readJsonData(firstSourceInfo);
			String firstVersion = firstSourceInfo.getVersion();
			JsonNode modified = MAPPER.readValue("{\"one\":\"two\"}", JsonNode.class);
			String newVersion = grm.modify(modified, firstVersion, message, userInfo, null, STORE, null).get();
			assertNotEquals(firstVersion, newVersion);
			SourceInfo secondSourceInfo = grm.getSourceInfo(STORE, null);
			JsonNode secondValue = readJsonData(secondSourceInfo);
			assertNotEquals(firstValue, secondValue);
			assertEquals(newVersion, secondSourceInfo.getVersion());
			git.pull().call();
			RevCommit revCommit = getRevCommit(git.getRepository(), REF_HEADS_MASTER);
			assertEquals(message, revCommit.getShortMessage());
			assertEquals(userInfo, revCommit.getAuthorIdent().getName());
			System.out.println("User email " + revCommit.getAuthorIdent().getEmailAddress());
			assertEquals("JitStatic API put operation", revCommit.getCommitterIdent().getName());
		}
	}
	
	@Test
	public void testModifyTag() throws CorruptedSourceException, IOException {
		ex.expect(UnsupportedOperationException.class);
		ex.expectMessage("Tags cannot be modified");
		try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, REF_HEADS_MASTER);){
			grm.modify(null, "1", "m", "ui", "m", "key", "refs/tags/tag");
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
		try (InputStream is = sourceInfo.getInputStream()) {
			return MAPPER.readValue(is, JsonNode.class);
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
