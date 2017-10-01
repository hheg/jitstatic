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


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.hamcrest.Matchers.is;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate.Status;
import org.eclipse.jgit.transport.ServiceMayNotContinueException;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;
import org.eclipse.jgit.transport.resolver.ServiceNotEnabledException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonParser.Feature;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.io.JsonEOFException;

import jitstatic.source.SourceEventListener;

public class HostedGitRepositoryManagerTest {

	private static final JsonFactory mapper = new JsonFactory().enable(Feature.ALLOW_COMMENTS)
			.enable(Feature.STRICT_DUPLICATE_DETECTION);
	private static final String master = "master";
	private static final String ENDPOINT = "endpoint";
	private static final String ref_heads_master = Constants.R_HEADS + master;
	private static final String store = "store";

	@Rule
	public ExpectedException ex = ExpectedException.none();
	@Rule
	public final TemporaryFolder tempFolder = new TemporaryFolder();
	private Path tempFile;
	private Path tempDir;

	@Before
	public void setup() throws IOException {
		tempDir = tempFolder.newFolder().toPath();
		tempFile = tempFolder.newFile().toPath();
	}

	@Test
	public void testForNullStore() {
		ex.expect(NullPointerException.class);
		try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, null, master);) {
		}
	}

	@Test
	public void testForNullBranch() {
		ex.expect(NullPointerException.class);
		try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, store, null);) {
		}
	}

	@Test
	public void testForEmptyStore() {
		ex.expect(IllegalArgumentException.class);
		ex.expectMessage("Storage name cannot be empty");
		try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, "", master);) {
		}
	}

	@Test
	public void testForEmptyBranch() {
		ex.expect(IllegalArgumentException.class);
		ex.expectMessage("Branch name cannot be empty");
		try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, store, "");) {
		}
	}

	@Test
	public void testCreatedBareDirectory() {
		try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, store, master);) {
			assertTrue(Files.exists(Paths.get(grm.repositoryURI()).resolve("HEAD")));
		}
	}

	@Test()
	public void testForDirectory() {
		ex.expect(IllegalArgumentException.class);
		ex.expectMessage(String.format("Path %s is not a directory", tempFile));
		try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempFile, ENDPOINT, store, master);) {
		}
	}

	@Test()
	public void testForWritableDirectory() throws IOException {
		ex.expect(IllegalArgumentException.class);
		ex.expectMessage(String.format("Path %s is not writeable", tempDir));
		Set<PosixFilePermission> perms = PosixFilePermissions.fromString("r-xr-x---");
		Files.setPosixFilePermissions(tempDir, perms);
		try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, store, master);) {
		}
	}

	@Test
	public void testForNullEndPoint() {
		ex.expect(NullPointerException.class);
		try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, null, store, master);) {
		}
	}

	@Test
	public void testForEmptyEndPoint() {
		ex.expect(IllegalArgumentException.class);
		ex.expectMessage("Parameter endPointName cannot be empty");
		try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, "", store, master);) {
		}
	}

	@Test
	public void testGetRepositoryResolver() throws RepositoryNotFoundException, ServiceMayNotContinueException,
			ServiceNotAuthorizedException, ServiceNotEnabledException {
		try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, store, master);) {
			Repository open = grm.getRepositoryResolver().open(null, ENDPOINT);
			assertNotNull(open);
		}
	}

	@Test
	public void testNotFoundRepositoryResolver() throws RepositoryNotFoundException, ServiceMayNotContinueException,
			ServiceNotAuthorizedException, ServiceNotEnabledException {
		ex.expect(RepositoryNotFoundException.class);
		try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, store, master);) {
			grm.getRepositoryResolver().open(null, "something");
		}
	}

	@Test
	public void testMountingOnExistingGitRepository() {
		try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, store, master)) {
		}

		try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, store, master)) {			
		}
	}

	@Test
	public void testMountingOnDifferentBranches() throws Throwable {
		String wrongBranch = "wrongbranch";
		ex.expect(BranchNotFoundException.class);
		ex.expectMessage(wrongBranch + " is not found in the repository");
		try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, store, "other")) {
			// should create a bare repo with a branch other with a store file with content
		}

		try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, store, wrongBranch)) {
			// should try an mount on the same repo but with the wrong branch and should end
			// with an error
		}
	}

	@Test
	public void testInitializingValidRepository()
			throws InvalidRemoteException, TransportException, GitAPIException, IOException {
		File base = tempFolder.newFolder();
		try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, store, master);
				Git git = Git.cloneRepository().setDirectory(base).setURI(tempDir.toUri().toString())
						.call()) {
			assertTrue(Files.exists(base.toPath().resolve(store)));
		}
	}

	@Test
	public void testPushingANonJSONFormattedStorageFile() throws Exception {
		ex.expect(RuntimeException.class);
		ex.expectCause(is(JsonEOFException.class));
		try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, store, master)) {
			File localGitDir = tempFolder.newFolder();
			try (Git git = Git.cloneRepository().setURI(grm.repositoryURI().toString()).setDirectory(localGitDir).call()) {
				Files.write(Paths.get(localGitDir.toURI()).resolve(store), "{".getBytes("UTF-8"), StandardOpenOption.TRUNCATE_EXISTING);
				git.add().addFilepattern(store).call();
				git.commit().setMessage("Test commit").call();
				// This works since there's no check done on the repository
				Iterable<PushResult> push = git.push().call();
				PushResult pushResult = push.iterator().next();
				assertEquals(Status.OK, pushResult.getRemoteUpdate(ref_heads_master).getStatus());
			}
		}
		try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, store, master)) {
		}
	}
	
	@Test
	public void testClosedRepositoryAndInputStream() throws IOException {
		try(HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, store, master);){
			try(InputStream is = grm.getSourceStream()){
				JsonParser parser = mapper.createParser(is);
				while(parser.nextToken() != null);
			}
		}
	}

	@Test
	public void testListeners() {
		SourceEventListener svl = mock(SourceEventListener.class);
		try(HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, store, master);){
			grm.addListener(svl);
		}
	}
	
	@Test
	public void testNonFullRefPathBranchName() {
		try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, ENDPOINT, store,
				ref_heads_master)) {
			//fail("TODO fix check of branch here");
		}
	}

}
