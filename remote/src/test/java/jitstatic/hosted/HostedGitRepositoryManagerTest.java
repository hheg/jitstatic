package jitstatic.hosted;

/*-
 * #%L
 * jitstatic
 * %%
 * Copyright (C) 2017 HHegardt
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

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.ServiceMayNotContinueException;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;
import org.eclipse.jgit.transport.resolver.ServiceNotEnabledException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;

import jitstatic.hosted.HostedGitRepositoryManager;

public class HostedGitRepositoryManagerTest {

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
	public void testCreatedBareDirectory() {
		try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, "endpoint");) {
			assertTrue(Files.exists(tempDir.resolve(HostedGitRepositoryManager.BARE).resolve("HEAD")));
		}
	}

	@Test()
	public void testForDirectory() {
		ex.expect(IllegalArgumentException.class);
		ex.expectMessage(String.format("Path %s is not a directory", tempFile));
		try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempFile, "endpoint");) {
		}
	}

	@Test()
	public void testForWritableDirectory() throws IOException {
		ex.expect(IllegalArgumentException.class);
		ex.expectMessage(String.format("Path %s is not writeable", tempDir));
		Set<PosixFilePermission> perms = PosixFilePermissions.fromString("r-xr-x---");
		Files.setPosixFilePermissions(tempDir, perms);

		try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, "endpoint");) {
		}
	}

	@Test
	public void testForNullEndPoint() {
		ex.expect(NullPointerException.class);
		try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, null);) {
		}
	}

	@Test
	public void testForEmptyEndPoint() {
		ex.expect(IllegalArgumentException.class);
		ex.expectMessage("Parameter endPointName cannot be empty");
		try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, "");) {
		}
	}

	@Test
	public void testGetRepositoryResolver() throws RepositoryNotFoundException, ServiceMayNotContinueException,
			ServiceNotAuthorizedException, ServiceNotEnabledException {
		try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, "endpoint");) {
			Repository open = grm.getRepositoryResolver().open(null, "endpoint");
			assertNotNull(open);
		}
	}

	@Test
	public void testNotFoundRepositoryResolver() throws RepositoryNotFoundException, ServiceMayNotContinueException,
			ServiceNotAuthorizedException, ServiceNotEnabledException {
		ex.expect(RepositoryNotFoundException.class);
		try (HostedGitRepositoryManager grm = new HostedGitRepositoryManager(tempDir, "endpoint");) {
			grm.getRepositoryResolver().open(null, "something");
		}
	}

}
