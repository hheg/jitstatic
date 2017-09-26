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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

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
import jitstatic.source.Source;

public class GitStorageTest {

	private static final ObjectMapper mapper = new ObjectMapper();

	@Rule
	public ExpectedException ex = ExpectedException.none();

	@Rule
	public final TemporaryFolder tempFolder = new TemporaryFolder();

	public Source source = mock(Source.class);

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
	public void testInitGitStorageWithNullSource() {
		ex.expectMessage("Source cannot be null");
		ex.expect(NullPointerException.class);
		try (GitStorage gs = new GitStorage(null);) {
		}
	}

	@Test
	public void testLoadCache() throws IOException, LoaderException {
		Set<User> users = new HashSet<>();
		users.add(new User("user", "1234"));
		try (GitStorage gs = new GitStorage(source);
				InputStream test1 = GitStorageTest.class.getResourceAsStream("/test1.json");
				InputStream test2 = GitStorageTest.class.getResourceAsStream("/test2.json")) {
			when(source.getSourceStream()).thenReturn(test1);
			gs.load();
			StorageData storage = new StorageData(users, readData("\"value1\""));
			assertEquals(storage, gs.get("key"));
			when(source.getSourceStream()).thenReturn(test2);
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

		try (GitStorage gs = new GitStorage(source);
				InputStream test3 = GitStorageTest.class.getResourceAsStream("/test3.json");
				InputStream test4 = GitStorageTest.class.getResourceAsStream("/test4.json")) {

			when(source.getSourceStream()).thenReturn(test3);
			gs.load();
			StorageData key1Data = gs.get("key1");
			StorageData key3Data = gs.get("key3");
			assertNotNull(key1Data);
			assertNotNull(key3Data);

			when(source.getSourceStream()).thenReturn(test4);
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
		NullPointerException npe = new NullPointerException();
		ex.expect(is(npe));
		when(source.getSourceStream()).thenThrow(npe);
		try (GitStorage gs = new GitStorage(source);) {
			try {
				gs.load();
			} catch (Exception ignore) {
			}
			gs.checkHealth();
		}
	}

	@Test
	public void testCheckHealthWithFault() throws Exception {
		RuntimeException cause = new RuntimeException("Fault reading something");
		doThrow(new RuntimeException("Fault reading something")).when(source).getSourceStream();

		try (GitStorage gs = new GitStorage(source);) {
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
		doThrow(cause).when(source).getSourceStream();

		try (GitStorage gs = new GitStorage(source);) {
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
	
	@Test
	public void testSourceCloseFailed() {
		doThrow(new RuntimeException()).when(source).close();
		try (GitStorage gs = new GitStorage(source);) {
		}
	}
}
