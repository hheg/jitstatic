package jitstatic.storage;

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

import static org.hamcrest.CoreMatchers.isA;
import static org.junit.Assert.*;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.UUID;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.TransportException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;

import jitstatic.source.Source.Contact;
import jitstatic.storage.GitWorkingRepositoryManager;

public class GitWorkingRepositoryManagerTest {
	private static final String STORAGE = "storage";

	@Rule
	public ExpectedException ex = ExpectedException.none();

	@Rule
	public final TemporaryFolder tempFolder = new TemporaryFolder();

	private Path tempFile;
	private Path tempDir;
	private Contact remoteRepo;

	@Before
	public void setup() throws Exception {
		tempFile = tempFolder.newFile().toPath();
		tempDir = tempFolder.newFolder().toPath();
		try (Git git = Git.init().setDirectory(tempDir.resolve("bare").toFile()).setBare(true).call();) {
			URI uri = git.getRepository().getDirectory().toURI();
			remoteRepo = new Contact() {

				@Override
				public URI repositoryURI() {
					return uri;
				}

				@Override
				public String getUserName() {
					return "user";
				}

				@Override
				public String getPassword() {
					return "pass";
				}
			};
			;
		}
	}

	@Test
	public void testForDirectory() {
		ex.expect(IllegalArgumentException.class);
		ex.expectMessage(String.format("Path %s is not a directory", tempFile));
		try (GitWorkingRepositoryManager grm = new GitWorkingRepositoryManager(tempFile, STORAGE, remoteRepo);) {
		}
	}

	@Test
	public void testForWritableDirectory() throws IOException {
		ex.expect(IllegalArgumentException.class);
		ex.expectMessage(String.format("Path %s is not writeable", tempDir));
		Set<PosixFilePermission> perms = PosixFilePermissions.fromString("r-xr-x---");
		Files.setPosixFilePermissions(tempDir, perms);

		try (GitWorkingRepositoryManager grm = new GitWorkingRepositoryManager(tempDir, STORAGE, remoteRepo);) {
		}
	}

	@Test
	public void testCreatedWorkingDirectory() {
		try (GitWorkingRepositoryManager grm = new GitWorkingRepositoryManager(tempDir, STORAGE, remoteRepo);) {
			assertTrue(Files.exists(tempDir.resolve(GitWorkingRepositoryManager.WORKING).resolve(STORAGE)));
		}
	}

	@Test
	public void testReEntrantInstansiation() {
		final Path storage = tempDir.resolve(GitWorkingRepositoryManager.WORKING).resolve(STORAGE);
		try (GitWorkingRepositoryManager grm = new GitWorkingRepositoryManager(tempDir, STORAGE, remoteRepo);) {
			assertTrue(Files.exists(storage));
		}
		try (GitWorkingRepositoryManager grm = new GitWorkingRepositoryManager(tempDir, STORAGE, remoteRepo);) {
			assertTrue(Files.exists(storage));
		}
	}

	@Test
	public void testReEntrantInstansiationButStorageIsRemoved() throws IOException {
		final Path storage = tempDir.resolve(GitWorkingRepositoryManager.WORKING).resolve(STORAGE);

		ex.expect(RuntimeException.class);
		ex.expectCause(isA(FileNotFoundException.class));
		ex.expectMessage(storage.toString());

		try (GitWorkingRepositoryManager grm = new GitWorkingRepositoryManager(tempDir, STORAGE, remoteRepo);) {
			assertTrue(Files.exists(storage));
		}
		Files.delete(storage);
		try (GitWorkingRepositoryManager grm = new GitWorkingRepositoryManager(tempDir, STORAGE, remoteRepo);) {
		}
	}

	@Test
	public void testResolveACorrectFile() {
		try (GitWorkingRepositoryManager grm = new GitWorkingRepositoryManager(tempDir, STORAGE, remoteRepo);) {
			assertNotNull(grm.resolvePath(STORAGE));
		}
	}

	@Test
	public void testClonseAnExistingRepo() throws IOException {
		final Path storage = tempDir.resolve(GitWorkingRepositoryManager.WORKING).resolve(STORAGE);

		try (GitWorkingRepositoryManager grm = new GitWorkingRepositoryManager(tempDir, STORAGE, remoteRepo);) {
			assertTrue(Files.exists(storage));
		}
		final Path newPath = tempFolder.newFolder().toPath();
		final Path newstorage = newPath.resolve(GitWorkingRepositoryManager.WORKING).resolve(STORAGE);
		try (GitWorkingRepositoryManager grm = new GitWorkingRepositoryManager(newPath, STORAGE, remoteRepo)) {
			assertTrue(Files.exists(newstorage));
		}
	}

	@Test
	public void testRemoteContactInfoIsNull() {
		ex.expect(NullPointerException.class);
		ex.expectMessage("remoteContactInfo cannot be null");
		try (GitWorkingRepositoryManager grm = new GitWorkingRepositoryManager(tempDir, STORAGE, null);) {
		}
	}

	@Test
	public void testRefreshing() throws InvalidRemoteException, TransportException, GitAPIException,
			UnsupportedEncodingException, IOException {
		URI repositoryURI = remoteRepo.repositoryURI();
		String s = UUID.randomUUID().toString();
		Path p = Paths.get(repositoryURI).resolve(s);
		try (GitWorkingRepositoryManager grm = new GitWorkingRepositoryManager(tempDir, STORAGE, remoteRepo);) {
			grm.refresh();
			assertFalse(Files.exists(p));
		}
		try (Git git = Git.cloneRepository().setURI(repositoryURI.toString()).setDirectory(tempFolder.newFolder())
				.call()) {
			Files.write(p, s.getBytes("UTF-8"));
			git.add().addFilepattern(s).call();
			git.commit().setMessage("Test commit").call();
			git.push().call();
		}
		try (GitWorkingRepositoryManager grm = new GitWorkingRepositoryManager(tempDir, STORAGE, remoteRepo);) {
			grm.refresh();
			assertTrue(Files.exists(p));
		}
	}

}
