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

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.isA;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import org.eclipse.jgit.api.errors.NoHeadException;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jitstatic.auth.User;

public class GitStorageTest {
	private static final String STORAGE = "storage";
	private static final ObjectMapper mapper = new ObjectMapper();

	@Rule
	public ExpectedException ex = ExpectedException.none();

	private GitWorkingRepositoryManager repoManager = mock(GitWorkingRepositoryManager.class);

	@Rule
	public final TemporaryFolder tempFolder = new TemporaryFolder();

	private Path tempFile;

	@Before
	public void setup() throws Exception {
		tempFile = tempFolder.newFile().toPath();
	}

	@After
	public void tearDown() throws IOException {
		Files.delete(tempFile);
	}

	@Test()
	public void testInitGitStorageWithNullFileStorageName() {
		ex.expectMessage("File storage is null");
		ex.expect(NullPointerException.class);
		try (GitStorage gs = new GitStorage(null, null);) {
		}
	}

	@Test()
	public void testInitGitStorageWithEmptyFileStorageName() {
		ex.expectMessage("Storage file name's empty");
		ex.expect(IllegalArgumentException.class);
		try (GitStorage gs = new GitStorage("", null);) {
		}
	}

	@Test()
	public void testInitGitStorageWithNullRepositoryManager() {
		ex.expectMessage("RepositoryManager is null");
		ex.expect(NullPointerException.class);
		try (GitStorage gs = new GitStorage("notnull", null);) {
		}
	}

	@Test
	public void testRefreshWithFailingRepository() throws LoaderException {
		ex.expect(LoaderException.class);
		ex.expectMessage("Error while loading storage");
		try {
			doThrow(new NoHeadException("")).when(repoManager).refresh();
		} catch (Exception ignore) {
		}
		try (GitStorage gs = new GitStorage(STORAGE, repoManager);) {
			gs.load();
		}
	}

	@Test
	public void testLoadCache() throws IOException, LoaderException {
		Set<User> users = new HashSet<>();
		users.add(new User("user", "1234"));
		when(repoManager.resolvePath(STORAGE)).thenReturn(tempFile);
		try (GitStorage gs = new GitStorage(STORAGE, repoManager);) {
			StorageTestUtils.copy("/test1.json", tempFile);
			gs.load();
			StorageData storage = new StorageData(users, readData("\"value1\""));
			assertEquals(storage, gs.get("key"));
			StorageTestUtils.copy("/test2.json", tempFile);
			gs.load();
			storage = new StorageData(users, readData("\"value2\""));
			assertEquals(storage, gs.get("key"));
		}
	}

	private JsonNode readData(String content) throws JsonProcessingException, IOException {
		return mapper.readTree(content);
	}

	@Test
	public void testLoadNewCache() throws IOException, LoaderException {
		when(repoManager.resolvePath(STORAGE)).thenReturn(tempFile);
		try (GitStorage gs = new GitStorage(STORAGE, repoManager);) {
			StorageTestUtils.copy("/test3.json", tempFile);
			gs.load();
			StorageData key1Data = gs.get("key1");
			StorageData key3Data = gs.get("key3");
			assertNotNull(key1Data);
			assertNotNull(key3Data);
			StorageTestUtils.copy("/test4.json", tempFile);
			gs.load();
			key1Data = gs.get("key1");
			StorageData key4Data = gs.get("key4");
			key3Data = gs.get("key3");
			assertNotNull(key1Data);
			assertNotNull(key4Data);
			assertNull(key3Data);
		}
	}

	@Test
	public void testCheckHealth() throws Exception {
		ex.expectCause(isA(LoaderException.class));
		try (GitStorage gs = new GitStorage(STORAGE, repoManager);) {
			gs.checkHealth();
		}
	}

	@Test
	public void testCheckHealthWithFault() throws Exception {
		RuntimeException cause = new RuntimeException("Fault reading something");
		doThrow(new RuntimeException("Fault reading something")).when(repoManager).refresh();
		when(repoManager.resolvePath(STORAGE)).thenReturn(tempFile);
		
		try (GitStorage gs = new GitStorage(STORAGE, repoManager);) {
			try {
				gs.load();
			} catch (LoaderException e) {
				assertTrue(e.getCause() instanceof RuntimeException);
				assertEquals(cause.getMessage(), e.getCause().getMessage());
			}
			try {
				gs.checkHealth();
			} catch (Exception e) {				
				assertTrue(e instanceof RuntimeException);
				assertEquals(cause.getMessage(), e.getMessage());
			}
			StorageTestUtils.copy("/test3.json", tempFile);
			gs.checkHealth();
		}
	}
	
	@Test
	public void testCheckHealthWithOldFault() throws Exception {
		RuntimeException cause = new RuntimeException("Fault reading something");
		ex.expect(is(equalTo(cause)));
		doThrow(cause).when(repoManager).refresh();
		when(repoManager.resolvePath(STORAGE)).thenReturn(tempFile);		
		try (GitStorage gs = new GitStorage(STORAGE, repoManager);) {
			try {
				gs.load();
			} catch (LoaderException e) {
				assertTrue(e.getCause() instanceof RuntimeException);
				assertEquals(cause.getMessage(), e.getCause().getMessage());
			}
			try {
				gs.checkHealth();
			} catch (Exception e) {			
				assertTrue(e instanceof RuntimeException);
				assertEquals(cause.getMessage(), e.getMessage());
			}
			try {
				gs.load();
			} catch (LoaderException e) {
				RuntimeException re = (RuntimeException) e.getCause();				
				assertEquals(cause.getMessage(), re.getMessage());
			}
			gs.checkHealth();
		}
	}
}
