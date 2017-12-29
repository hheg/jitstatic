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
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.eclipse.jgit.lib.Constants;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jitstatic.auth.User;
import jitstatic.source.Source;

public class GitStorageTest {

	private static final ObjectMapper mapper = new ObjectMapper();
	private static final String REF_HEADS_MASTER = Constants.R_HEADS + Constants.MASTER;
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
		try (GitStorage gs = new GitStorage(null, null);) {
		}
	}

	@Test
	public void testLoadCache() throws IOException, InterruptedException, ExecutionException {
		Set<User> users = new HashSet<>();
		users.add(new User("user", "1234"));
		try (GitStorage gs = new GitStorage(source, null);
				InputStream test1 = GitStorageTest.class.getResourceAsStream("/test1.json");
				InputStream test2 = GitStorageTest.class.getResourceAsStream("/test2.json")) {
			when(source.getSourceStream(Mockito.anyString(), Mockito.anyString())).thenReturn(test1);
			gs.reload(Arrays.asList(REF_HEADS_MASTER));
			StorageData storage = new StorageData(users, readData("\"value1\""));
			assertEquals(storage, gs.get("key", null).get());
			when(source.getSourceStream(Mockito.anyString(), Mockito.anyString())).thenReturn(test2);
			gs.reload(Arrays.asList(REF_HEADS_MASTER));
			storage = new StorageData(users, readData("\"value2\""));
			assertEquals(storage, gs.get("key", null).get());
		}
	}

	private JsonNode readData(String content) throws JsonProcessingException, IOException {
		return mapper.readTree(content);
	}

	@Test
	public void testLoadNewCache() throws Exception {

		try (GitStorage gs = new GitStorage(source, null);
				InputStream test3 = GitStorageTest.class.getResourceAsStream("/test3.json");
				InputStream test4 = GitStorageTest.class.getResourceAsStream("/test4.json")) {

			when(source.getSourceStream(Mockito.eq("key3"), Mockito.anyString())).thenReturn(test3);
			when(source.getSourceStream(Mockito.eq("key4"), Mockito.anyString())).thenReturn(test4);
			Future<StorageData> key3Data = gs.get("key3", null);
			Future<StorageData> key4Data = gs.get("key4", null);
			assertNotNull(key3Data.get());
			assertNotNull(key4Data.get());
			gs.checkHealth();
		}
	}

	@Test
	public void testCheckHealth() throws Exception {
		NullPointerException npe = new NullPointerException();
		ex.expect(is(npe));
		when(source.getSourceStream(Mockito.anyString(), Mockito.anyString())).thenThrow(npe);
		try (GitStorage gs = new GitStorage(source, null);) {
			try {
				gs.get("", null).get();
			} catch (Exception ignore) {
			}
			gs.checkHealth();
		}
	}

	@Test
	public void testCheckHealthWithFault() throws Exception {
		RuntimeException cause = new RuntimeException("Fault reading something");
		doThrow(cause).when(source).getSourceStream(Mockito.anyString(), Mockito.anyString());

		try (GitStorage gs = new GitStorage(source, null);
				InputStream is = GitStorageTest.class.getResourceAsStream("/test3.json")) {

			gs.reload(Arrays.asList(REF_HEADS_MASTER));
			assertNull(gs.get("test3.json", null).get());
			try {
				gs.checkHealth();
			} catch (Exception e) {
				assertTrue(e instanceof RuntimeException);
				assertEquals(cause.getMessage(), e.getMessage());
			}
			Mockito.reset(source);
			when(source.getSourceStream(Mockito.anyString(), Mockito.anyString())).thenReturn(is);
			gs.checkHealth();
			assertNotNull(gs.get("test3.json", null));
		}
	}

	@Test
	public void testCheckHealthWithOldFault() throws Exception {
		RuntimeException cause = new RuntimeException("Fault reading something");
		ex.expect(is(equalTo(cause)));
		doThrow(cause).when(source).getSourceStream(Mockito.anyString(), Mockito.anyString());

		try (GitStorage gs = new GitStorage(source, null);) {

			gs.reload(Arrays.asList(REF_HEADS_MASTER));
			assertNull(gs.get("key", null).get());
			try {
				gs.checkHealth();
			} catch (Exception e) {
				assertTrue(e instanceof RuntimeException);
				assertEquals(cause.getMessage(), e.getMessage());
			}
			gs.get("key", null).get();
			gs.checkHealth();
		}
	}

	@Test
	public void testSourceCloseFailed() {
		doThrow(new RuntimeException()).when(source).close();
		try (GitStorage gs = new GitStorage(source, null);) {
		}
	}
	
	@Test
	public void testRefIsFoundButKeyIsNot() throws Exception {
		
		try (GitStorage gs = new GitStorage(source, null);
				InputStream test3 = GitStorageTest.class.getResourceAsStream("/test3.json");
				InputStream test4 = GitStorageTest.class.getResourceAsStream("/test4.json")) {

			when(source.getSourceStream(Mockito.eq("key3"), Mockito.anyString())).thenReturn(test3);
			when(source.getSourceStream(Mockito.eq("key4"), Mockito.anyString())).thenReturn(test4);
			Future<StorageData> key3Data = gs.get("key3", null);
			assertNotNull(key3Data.get());
			Future<StorageData> key4Data = gs.get("key4", null);			
			assertNotNull(key4Data.get());
			key4Data = gs.get("key4", REF_HEADS_MASTER);			
			assertNotNull(key4Data.get());
			gs.checkHealth();
		}

	}
}
