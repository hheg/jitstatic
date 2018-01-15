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
import static org.junit.Assert.assertNotEquals;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.lib.Constants;
import org.hamcrest.Matchers;
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
import jitstatic.source.SourceInfo;
import jitstatic.utils.WrappingAPIException;

public class GitStorageTest {

	private static final ObjectMapper mapper = new ObjectMapper();
	private static final String REF_HEADS_MASTER = Constants.R_HEADS + Constants.MASTER;
	private static final String SHA_1 = "67adef5dab64f8f4cb50712ab24bda6605befa79";
	private static final String SHA_2 = "67adef5dab64f8f4cb50712ab24bda6605befa80";
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
	public void testLoadCache() throws IOException, InterruptedException, ExecutionException, RefNotFoundException {
		Set<User> users = new HashSet<>();
		users.add(new User("user", "1234"));
		try (GitStorage gs = new GitStorage(source, null);
				InputStream test1 = GitStorageTest.class.getResourceAsStream("/test1.json");
				InputStream test2 = GitStorageTest.class.getResourceAsStream("/test2.json")) {
			SourceInfo si1 = mock(SourceInfo.class);
			SourceInfo si2 = mock(SourceInfo.class);
			when(si1.getInputStream()).thenReturn(test1);
			when(si2.getInputStream()).thenReturn(test2);
			when(si1.getVersion()).thenReturn(SHA_1);
			when(si2.getVersion()).thenReturn(SHA_2);

			when(source.getSourceInfo(Mockito.anyString(), Mockito.anyString())).thenReturn(si1);
			gs.reload(Arrays.asList(REF_HEADS_MASTER));
			StoreInfo storage = new StoreInfo(new StorageData(users, readData("\"value1\"")), SHA_1);
			assertEquals(storage, gs.get("key", null).get());
			when(source.getSourceInfo(Mockito.anyString(), Mockito.anyString())).thenReturn(si2);
			gs.reload(Arrays.asList(REF_HEADS_MASTER));
			storage = new StoreInfo(new StorageData(users, readData("\"value2\"")), SHA_2);
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
			SourceInfo si1 = mock(SourceInfo.class);
			SourceInfo si2 = mock(SourceInfo.class);
			when(si1.getInputStream()).thenReturn(test3);
			when(si1.getVersion()).thenReturn(SHA_1);
			when(si2.getInputStream()).thenReturn(test4);
			when(si2.getVersion()).thenReturn(SHA_2);
			when(source.getSourceInfo(Mockito.eq("key3"), Mockito.anyString())).thenReturn(si1);
			when(source.getSourceInfo(Mockito.eq("key4"), Mockito.anyString())).thenReturn(si2);
			Future<StoreInfo> key3Data = gs.get("key3", null);
			Future<StoreInfo> key4Data = gs.get("key4", null);
			assertNotNull(key3Data.get());
			assertNotNull(key4Data.get());
			gs.checkHealth();
		}
	}

	@Test
	public void testCheckHealth() throws Exception {
		NullPointerException npe = new NullPointerException();
		ex.expect(is(npe));
		when(source.getSourceInfo(Mockito.anyString(), Mockito.anyString())).thenThrow(npe);
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
		doThrow(cause).when(source).getSourceInfo(Mockito.anyString(), Mockito.anyString());

		try (GitStorage gs = new GitStorage(source, null); InputStream is = GitStorageTest.class.getResourceAsStream("/test3.json")) {

			gs.reload(Arrays.asList(REF_HEADS_MASTER));
			assertNull(gs.get("test3.json", null).get());
			try {
				gs.checkHealth();
			} catch (Exception e) {
				assertTrue(e instanceof RuntimeException);
				assertEquals(cause.getMessage(), e.getMessage());
			}
			Mockito.reset(source);
			SourceInfo info = mock(SourceInfo.class);
			when(info.getInputStream()).thenReturn(is);
			when(source.getSourceInfo(Mockito.anyString(), Mockito.anyString())).thenReturn(info);
			gs.checkHealth();
			assertNotNull(gs.get("test3.json", null));
		}
	}

	@Test
	public void testCheckHealthWithOldFault() throws Exception {
		RuntimeException cause = new RuntimeException("Fault reading something");
		ex.expect(is(equalTo(cause)));
		doThrow(cause).when(source).getSourceInfo(Mockito.anyString(), Mockito.anyString());

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
			SourceInfo si1 = mock(SourceInfo.class);
			SourceInfo si2 = mock(SourceInfo.class);
			when(si1.getInputStream()).thenReturn(test3);
			when(si1.getVersion()).thenReturn(SHA_1);
			when(si2.getInputStream()).thenReturn(test4);
			when(si2.getVersion()).thenReturn(SHA_2);
			when(source.getSourceInfo(Mockito.eq("key3"), Mockito.anyString())).thenReturn(si1);
			when(source.getSourceInfo(Mockito.eq("key4"), Mockito.anyString())).thenReturn(si2);
			Future<StoreInfo> key3Data = gs.get("key3", null);
			assertNotNull(key3Data.get());
			Future<StoreInfo> key4Data = gs.get("key4", null);
			assertNotNull(key4Data.get());
			key4Data = gs.get("key4", REF_HEADS_MASTER);
			assertNotNull(key4Data.get());
			gs.checkHealth();
		}

	}

	@Test
	public void testPutAKey() throws IOException, InterruptedException, ExecutionException, RefNotFoundException {
		try (GitStorage gs = new GitStorage(source, null); InputStream test3 = GitStorageTest.class.getResourceAsStream("/test3.json")) {
			SourceInfo si = mock(SourceInfo.class);
			JsonNode data = readData("{\"one\" : \"two\"}");
			String message = "one message";
			String userInfo = "test@test";
			String key = "key3";
			when(si.getInputStream()).thenReturn(test3);
			when(si.getVersion()).thenReturn(SHA_1);
			when(source.getSourceInfo(Mockito.eq("key3"), Mockito.anyString())).thenReturn(si);
			when(source.modify(Mockito.eq(data), Mockito.eq(SHA_1), Mockito.eq(message), Mockito.eq(userInfo), Mockito.anyString(),
					Mockito.eq(key), Mockito.any())).thenReturn(CompletableFuture.completedFuture(SHA_2));
			Future<StoreInfo> first = gs.get(key, null);
			StoreInfo storeInfo = first.get();
			assertNotNull(storeInfo);
			assertNotEquals(data, storeInfo.getStorageData().getData());
			CompletableFuture<String> put = gs.put(data, SHA_1, message, userInfo, "", key, null);
			String newVersion = put.get();
			assertEquals(SHA_2, newVersion);
			first = gs.get(key, null);
			storeInfo = first.get();
			assertNotNull(storeInfo);
			assertEquals(data, storeInfo.getStorageData().getData());
		}
	}

	@Test
	public void testPutKeyWithEmptyMessage() throws JsonProcessingException, IOException {
		ex.expect(IllegalArgumentException.class);
		try (GitStorage gs = new GitStorage(source, null);) {
			JsonNode data = readData("{\"one\" : \"two\"}");
			String userInfo = "test@test";
			String key = "key3";
			gs.put(data, SHA_1, "", userInfo, null, key, null);
		}
	}

	@Test
	public void testPutKeyWithNoRef() throws Throwable {
		ex.expect(WrappingAPIException.class);
		ex.expectCause(Matchers.isA(RefNotFoundException.class));
		try (GitStorage gs = new GitStorage(source, null);) {
			JsonNode data = readData("{\"one\" : \"two\"}");
			String message = "one message";
			String userInfo = "test@test";
			String key = "key3";
			CompletableFuture<String> put = gs.put(data, SHA_1, message, userInfo, null, key, null);
			try {
				put.get();
			} catch (Exception e) {
				throw e.getCause();
			}
		}
	}

	@Test
	public void testPutKeyWithNoKey() throws Throwable {
		ex.expect(WrappingAPIException.class);
		ex.expectCause(Matchers.isA(UnsupportedOperationException.class));
		try (GitStorage gs = new GitStorage(source, null); InputStream test3 = GitStorageTest.class.getResourceAsStream("/test3.json")) {
			SourceInfo si = mock(SourceInfo.class);
			JsonNode data = readData("{\"one\" : \"two\"}");
			String message = "one message";
			String userInfo = "test@test";
			String key = "key3";
			when(si.getInputStream()).thenReturn(test3);
			when(si.getVersion()).thenReturn(SHA_1);
			when(source.getSourceInfo(Mockito.eq("key3"), Mockito.anyString())).thenReturn(si);
			when(source.modify(Mockito.eq(data), Mockito.eq(SHA_1), Mockito.eq(message), Mockito.eq(userInfo), Mockito.anyString(),
					Mockito.eq(key), Mockito.any())).thenReturn(CompletableFuture.completedFuture(SHA_2));
			Future<StoreInfo> first = gs.get(key, null);
			StoreInfo storeInfo = first.get();
			assertNotNull(storeInfo);
			assertNotEquals(data, storeInfo.getStorageData().getData());
			CompletableFuture<String> put = gs.put(data, SHA_1, message, userInfo, null, "other", null);
			try {
				put.get();
			} catch (Exception e) {
				throw e.getCause();
			}
		}
	}

}
