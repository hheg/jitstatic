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



import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static org.hamcrest.Matchers.isA;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jgit.api.errors.NoHeadException;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;

import jitstatic.storage.GitStorage;
import jitstatic.storage.GitWorkingRepositoryManager;
import jitstatic.storage.LoaderException;

public class GitStorageTest {
	private static final String STORAGE = "storage";

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
		final Map<String,Object> map = new HashMap<>();
		map.put("key1", "value1");
		when(repoManager.resolvePath(STORAGE)).thenReturn(tempFile);
		try (GitStorage gs = new GitStorage(STORAGE, repoManager);) {
			StorageTestUtils.copy("/test1.json", tempFile);
			gs.load();
			assertEquals(map, gs.get("key"));
			StorageTestUtils.copy("/test2.json", tempFile);
			gs.load();
			map.clear();
			map.put("key2","value2");
			assertEquals(map, gs.get("key"));
		}
	}
	
	@Test
	public void testLoadNewCache() throws IOException, LoaderException {
		when(repoManager.resolvePath(STORAGE)).thenReturn(tempFile);
		try (GitStorage gs = new GitStorage(STORAGE, repoManager);) {
			StorageTestUtils.copy("/test3.json", tempFile);
			gs.load();
			assertEquals("value1", gs.get("key1").get("key1"));
			assertEquals("value3",gs.get("key3").get("key3"));
			StorageTestUtils.copy("/test4.json", tempFile);
			gs.load();
			assertEquals("value1",gs.get("key1").get("key1"));
			assertEquals("value4", gs.get("key4").get("key4"));
			assertEquals(null,gs.get("key3"));
		}
	}
	
	@Test
	public void testCheckHealth() {
		ex.expectCause(isA(LoaderException.class));
		try (GitStorage gs = new GitStorage(STORAGE, repoManager);) {
			gs.checkHealth();
		}
	}
}
