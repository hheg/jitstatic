package io.jitstatic.storage;

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
import static org.hamcrest.MatcherAssert.assertThat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.lib.Constants;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.jitstatic.StorageData;
import io.jitstatic.auth.User;
import io.jitstatic.source.Source;
import io.jitstatic.source.SourceInfo;
import io.jitstatic.utils.Pair;
import io.jitstatic.utils.WrappingAPIException;

public class GitStorageTest {

    private static final String UTF_8 = "UTF-8";
    private static final String REF_HEADS_MASTER = Constants.R_HEADS + Constants.MASTER;
    private static final String SHA_1 = "67adef5dab64f8f4cb50712ab24bda6605befa79";
    private static final String SHA_2 = "67adef5dab64f8f4cb50712ab24bda6605befa80";
    private static final String SHA_1_MD = "67adef5dab64f8f4cb50712ab24bda6605befa81";
    private static final String SHA_2_MD = "67adef5dab64f8f4cb50712ab24bda6605befa82";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public Source source = mock(Source.class);

    private Path tempFile;

    @BeforeEach
    public void setup() throws Exception {
        tempFile = Files.createTempFile("junit", UUID.randomUUID().toString());
    }

    @AfterEach
    public void tearDown() throws IOException {
        Mockito.reset(source);
        Files.delete(tempFile);
    }

    @Test
    public void testGetAKey() throws IOException, RefNotFoundException {
        try (GitStorage gs = new GitStorage(source, null); InputStream test1 = getInputStream(1); InputStream mtest1 = getUsers();) {
            SourceInfo si1 = mock(SourceInfo.class);
            when(si1.getSourceInputStream()).thenReturn(test1);
            when(si1.getMetadataInputStream()).thenReturn(mtest1);
            when(si1.getSourceVersion()).thenReturn(SHA_1);
            when(si1.getMetaDataVersion()).thenReturn(SHA_1_MD);
            when(source.getSourceInfo(Mockito.eq("key"), Mockito.anyString())).thenReturn(si1);

            CompletableFuture<Optional<StoreInfo>> completableFuture = gs.get("key", null);
            assertNotNull(completableFuture.join().get());
        }
    }

    @Test
    public void testGetARootKey() throws Exception {
        try (GitStorage gs = new GitStorage(source, null); InputStream mtest1 = getUsers();) {
            SourceInfo si1 = mock(SourceInfo.class);
            when(si1.getSourceInputStream()).thenReturn(null);
            when(si1.getMetadataInputStream()).thenReturn(mtest1);
            when(si1.getSourceVersion()).thenReturn(null);
            when(si1.getMetaDataVersion()).thenReturn(SHA_1_MD);
            when(source.getSourceInfo(Mockito.eq("root/"), Mockito.anyString())).thenReturn(si1);

            CompletableFuture<Optional<StoreInfo>> completableFuture = gs.get("root/", null);
            gs.checkHealth();
            StoreInfo storeInfo = completableFuture.join().get();
            assertNotNull(storeInfo);
            assertThrows(IllegalStateException.class, () -> storeInfo.getData());
            assertNotNull(storeInfo.getStorageData());
        }
    }

    @Test
    public void testPutARootKey() throws UnsupportedEncodingException, IOException, RefNotFoundException {
        try (GitStorage gs = new GitStorage(source, null); InputStream mtest1 = getUsers();) {
            SourceInfo si1 = mock(SourceInfo.class);
            when(si1.getSourceInputStream()).thenReturn(null);
            when(si1.getMetadataInputStream()).thenReturn(mtest1);
            when(si1.getSourceVersion()).thenReturn(null);
            when(si1.getMetaDataVersion()).thenReturn(SHA_1_MD);
            when(source.getSourceInfo(Mockito.eq("root/"), Mockito.anyString())).thenReturn(si1);
            when(source.modify(Mockito.<StorageData>any(), Mockito.eq(SHA_1_MD), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                    .thenReturn(CompletableFuture.completedFuture(SHA_2_MD));

            CompletableFuture<Optional<StoreInfo>> completableFuture = gs.get("root/", null);
            StoreInfo storeInfo = completableFuture.join().get();
            assertNotNull(storeInfo);
            assertThrows(IllegalStateException.class, () -> storeInfo.getData());
            assertNotNull(storeInfo.getStorageData());

            StorageData sd = new StorageData(Set.of(new User("u", "p")), "text/plain", false, false, List.of());
            CompletableFuture<String> putMetaData = gs.putMetaData("root/", null, sd, SHA_1_MD, "msg", "info", "mail");
            assertEquals(SHA_2_MD, putMetaData.join());
            CompletableFuture<Optional<StoreInfo>> completableFuture2 = gs.get("root/", null);
            assertEquals(completableFuture2.join().get().getMetaDataVersion(), SHA_2_MD);
        }
    }

    @Test
    public void testInitGitStorageWithNullSource() {
        assertEquals("Source cannot be null", assertThrows(NullPointerException.class, () -> {
            try (GitStorage gs = new GitStorage(null, null);) {
            }
        }).getLocalizedMessage());
    }

    @Test
    public void testLoadCache() throws Exception {
        Set<User> users = new HashSet<>();
        users.add(new User("user", "1234"));
        try (GitStorage gs = new GitStorage(source, null);
                InputStream test1 = getInputStream(1);
                InputStream mtest1 = getUsers();
                InputStream test2 = getInputStream(2);
                InputStream mtest2 = getUsers();) {
            SourceInfo si1 = mock(SourceInfo.class);
            SourceInfo si2 = mock(SourceInfo.class);
            when(si1.getSourceInputStream()).thenReturn(test1);
            when(si1.getMetadataInputStream()).thenReturn(mtest1);
            when(si2.getSourceInputStream()).thenReturn(test2);
            when(si2.getMetadataInputStream()).thenReturn(mtest2);
            when(si1.getSourceVersion()).thenReturn(SHA_1);
            when(si2.getSourceVersion()).thenReturn(SHA_2);

            when(si1.getMetaDataVersion()).thenReturn(SHA_1_MD);
            when(si2.getMetaDataVersion()).thenReturn(SHA_2_MD);
            when(source.getSourceInfo(Mockito.anyString(), Mockito.anyString())).thenReturn(si1);

            gs.reload(Arrays.asList(REF_HEADS_MASTER));
            StoreInfo storage = new StoreInfo(readData("{\"data\":\"value1\"}"), new StorageData(users, null, false, false, List.of()), SHA_1, SHA_1_MD);
            assertTrue(Arrays.equals(storage.getData(), gs.get("key", null).get().get().getData()));
            when(source.getSourceInfo(Mockito.anyString(), Mockito.anyString())).thenReturn(si2);
            gs.reload(Arrays.asList(REF_HEADS_MASTER));
            storage = new StoreInfo(readData("{\"data\":\"value2\"}"), new StorageData(users, null, false, false, List.of()), SHA_2, SHA_2_MD);
            assertTrue(Arrays.equals(storage.getData(), gs.get("key", null).get().get().getData()));
            gs.checkHealth();
        }
    }

    private byte[] readData(String content) throws UnsupportedEncodingException {
        return content.getBytes(UTF_8);
    }

    @Test
    public void testLoadNewCache() throws Exception {

        try (GitStorage gs = new GitStorage(source, null);
                InputStream test3 = getInputStream(1);
                InputStream mtest3 = getUsers();
                InputStream test4 = getInputStream(2);
                InputStream mtest4 = getUsers()) {
            SourceInfo si1 = mock(SourceInfo.class);
            SourceInfo si2 = mock(SourceInfo.class);
            when(si1.getSourceInputStream()).thenReturn(test3);
            when(si1.getMetadataInputStream()).thenReturn(mtest3);
            when(si1.getSourceVersion()).thenReturn(SHA_1);
            when(si2.getSourceInputStream()).thenReturn(test4);
            when(si2.getMetadataInputStream()).thenReturn(mtest4);
            when(si2.getSourceVersion()).thenReturn(SHA_2);
            when(si1.getMetaDataVersion()).thenReturn(SHA_1_MD);
            when(si2.getMetaDataVersion()).thenReturn(SHA_2_MD);

            when(source.getSourceInfo(Mockito.eq("key3"), Mockito.anyString())).thenReturn(si1);
            when(source.getSourceInfo(Mockito.eq("key4"), Mockito.anyString())).thenReturn(si2);
            Future<Optional<StoreInfo>> key3Data = gs.get("key3", null);
            Future<Optional<StoreInfo>> key4Data = gs.get("key4", null);
            assertNotNull(key3Data.get().get());
            assertNotNull(key4Data.get().get());
            gs.checkHealth();
        }
    }

    @Test
    public void testCheckHealth() throws Exception {
        NullPointerException npe = new NullPointerException();
        when(source.getSourceInfo(Mockito.anyString(), Mockito.anyString())).thenThrow(npe);
        assertSame(assertThrows(NullPointerException.class, () -> {
            try (GitStorage gs = new GitStorage(source, null);) {
                try {
                    gs.get("", null).get();
                } catch (Exception ignore) {
                }
                gs.checkHealth();
            }
        }), npe);
    }

    @Test
    public void testCheckHealthWithFault() throws Exception {
        RuntimeException cause = new RuntimeException("Fault reading something");
        doThrow(cause).when(source).getSourceInfo(Mockito.anyString(), Mockito.anyString());

        try (GitStorage gs = new GitStorage(source, null); InputStream is = getUsers()) {

            gs.reload(Arrays.asList(REF_HEADS_MASTER));
            assertNull(gs.get("test3.json", null).get());
            try {
                gs.checkHealth();
            } catch (Exception e) {
                assertTrue(e instanceof RuntimeException);
                assertEquals(cause.getLocalizedMessage(), e.getLocalizedMessage());
            }
            Mockito.reset(source);
            SourceInfo info = mock(SourceInfo.class);
            when(info.getSourceInputStream()).thenReturn(is);
            when(source.getSourceInfo(Mockito.anyString(), Mockito.anyString())).thenReturn(info);
            gs.checkHealth();
            assertNotNull(gs.get("test3.json", null));
        }
    }

    @Test
    public void testCheckHealthWithOldFault() throws Exception {
        RuntimeException cause = new RuntimeException("Fault reading something");
        doThrow(cause).when(source).getSourceInfo(Mockito.anyString(), Mockito.anyString());
        assertSame(assertThrows(RuntimeException.class, () -> {
            try (GitStorage gs = new GitStorage(source, null);) {
                gs.reload(Arrays.asList(REF_HEADS_MASTER));
                assertNull(gs.get("key", null).get());
                try {
                    gs.checkHealth();
                } catch (Exception e) {
                    assertTrue(e instanceof RuntimeException);
                    assertEquals(cause.getLocalizedMessage(), e.getLocalizedMessage());
                }
                gs.get("key", null).get();
                gs.checkHealth();
            }
        }), cause);
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
                InputStream test3 = getInputStream(1);
                InputStream mtest3 = getUsers();
                InputStream test4 = getInputStream(2);
                InputStream mtest4 = getUsers()) {
            SourceInfo si1 = mock(SourceInfo.class);
            SourceInfo si2 = mock(SourceInfo.class);
            when(si1.getSourceInputStream()).thenReturn(test3);
            when(si1.getMetadataInputStream()).thenReturn(mtest3);
            when(si1.getSourceVersion()).thenReturn(SHA_1);
            when(si2.getSourceInputStream()).thenReturn(test4);
            when(si2.getMetadataInputStream()).thenReturn(mtest4);
            when(si2.getSourceVersion()).thenReturn(SHA_2);
            when(si1.getMetaDataVersion()).thenReturn(SHA_1_MD);
            when(si2.getMetaDataVersion()).thenReturn(SHA_2_MD);

            when(source.getSourceInfo(Mockito.eq("key3"), Mockito.anyString())).thenReturn(si1);
            when(source.getSourceInfo(Mockito.eq("key4"), Mockito.anyString())).thenReturn(si2);
            Future<Optional<StoreInfo>> key3Data = gs.get("key3", null);
            assertNotNull(key3Data.get());
            Future<Optional<StoreInfo>> key4Data = gs.get("key4", null);
            assertNotNull(key4Data.get());
            key4Data = gs.get("key4", REF_HEADS_MASTER);
            assertNotNull(key4Data.get().get());
            gs.checkHealth();
        }

    }

    @Test
    public void testPutAKey() throws IOException, InterruptedException, ExecutionException, RefNotFoundException {
        try (GitStorage gs = new GitStorage(source, null); InputStream test3 = getInputStream(1); InputStream mtest3 = getUsers()) {
            SourceInfo si = mock(SourceInfo.class);
            byte[] data = readData("{\"one\" : \"two\"}");
            String message = "one message";
            String userInfo = "test@test";
            String key = "key3";
            when(si.getSourceInputStream()).thenReturn(test3);
            when(si.getMetadataInputStream()).thenReturn(mtest3);
            when(si.getSourceVersion()).thenReturn(SHA_1);
            when(si.getMetaDataVersion()).thenReturn(SHA_1_MD);

            when(source.getSourceInfo(Mockito.eq("key3"), Mockito.anyString())).thenReturn(si);
            when(source.modify(Mockito.eq(key), Mockito.any(), Mockito.any(), Mockito.eq(SHA_1), Mockito.eq(message), Mockito.eq(userInfo),
                    Mockito.anyString())).thenReturn(CompletableFuture.completedFuture(SHA_2));
            Future<Optional<StoreInfo>> first = gs.get(key, null);
            StoreInfo storeInfo = first.get().get();
            assertNotNull(storeInfo);
            assertNotEquals(data, storeInfo.getData());
            CompletableFuture<String> put = gs.put(key, null, data, SHA_1, message, userInfo, "");
            String newVersion = put.get();
            assertEquals(SHA_2, newVersion);
            first = gs.get(key, null);
            storeInfo = first.get().get();
            assertNotNull(storeInfo);
            assertArrayEquals(data, storeInfo.getData());
        }
    }

    @Test
    public void testPutAOnANonWritableKey() throws Throwable {
        assertThat((UnsupportedOperationException) assertThrows(WrappingAPIException.class, () -> {
            try (GitStorage gs = new GitStorage(source, null); InputStream test3 = getInputStream(1); InputStream mtest3 = getMetaDataProtectedInputStream()) {
                SourceInfo si = mock(SourceInfo.class);
                byte[] data = readData("{\"one\" : \"two\"}");
                String message = "one message";
                String userInfo = "test@test";
                String key = "key3";
                when(si.getSourceInputStream()).thenReturn(test3);
                when(si.getMetadataInputStream()).thenReturn(mtest3);
                when(si.getSourceVersion()).thenReturn(SHA_1);
                when(si.getMetaDataVersion()).thenReturn(SHA_1_MD);

                when(source.getSourceInfo(Mockito.eq("key3"), Mockito.anyString())).thenReturn(si);
                when(source.modify(Mockito.eq(key), Mockito.any(), Mockito.any(), Mockito.eq(SHA_1), Mockito.eq(message), Mockito.eq(userInfo),
                        Mockito.anyString())).thenReturn(CompletableFuture.completedFuture(SHA_2));
                Future<Optional<StoreInfo>> first = gs.get(key, null);
                StoreInfo storeInfo = first.get().get();
                assertNotNull(storeInfo);
                assertNotEquals(data, storeInfo.getData());
                CompletableFuture<String> put = gs.put(key, null, data, SHA_1, message, userInfo, "");
                try {
                    put.get();
                } catch (ExecutionException e) {
                    throw e.getCause();
                }
            }
        }).getCause(), Matchers.isA(UnsupportedOperationException.class));
    }

    @Test
    public void testGetADotKey() throws IOException, InterruptedException, ExecutionException, RefNotFoundException {
        try (GitStorage gs = new GitStorage(source, null)) {
            String key = ".key3";
            Future<Optional<StoreInfo>> first = gs.get(key, null);
            assertFalse(first.get().isPresent());
        }
    }

    @Test
    public void testGetATrainDotKey() throws IOException, InterruptedException, ExecutionException, RefNotFoundException {
        try (GitStorage gs = new GitStorage(source, null)) {
            String key = "key/key/.key3";
            Future<Optional<StoreInfo>> first = gs.get(key, null);
            assertFalse(first.get().isPresent());
        }
    }

    @Test
    public void testGetAHiddenFile() throws InterruptedException, ExecutionException, IOException, RefNotFoundException {
        SourceInfo si = mock(SourceInfo.class);
        when(si.getMetadataInputStream()).thenReturn(getMetaDataHiddenInputStream());
        when(source.getSourceInfo("key", REF_HEADS_MASTER)).thenReturn(si);
        try (GitStorage gs = new GitStorage(source, null)) {
            CompletableFuture<Optional<StoreInfo>> key = gs.get("key", null);
            assertFalse(key.get().isPresent());
        }
    }

    @Test
    public void testPutKeyWithEmptyMessage() throws IOException {
        assertThrows(IllegalArgumentException.class, () -> {
            try (GitStorage gs = new GitStorage(source, null);) {
                byte[] data = readData("{\"one\" : \"two\"}");
                String userInfo = "test@test";
                String key = "key3";
                gs.put(key, null, data, SHA_1, "", userInfo, null);
            }
        });
    }

    @Test
    public void testPutKeyWithNoRef() {
        assertThat((RefNotFoundException) assertThrows(WrappingAPIException.class, () -> {
            try (GitStorage gs = new GitStorage(source, null);) {
                byte[] data = readData("{\"one\" : \"two\"}");
                String message = "one message";
                String userInfo = "test@test";
                String key = "key3";
                CompletableFuture<String> put = gs.put(key, null, data, SHA_1, message, userInfo, null);
                try {
                    put.get();
                } catch (Exception e) {
                    throw e.getCause();
                }
            }
        }).getCause(), Matchers.isA(RefNotFoundException.class));
    }

    @Test
    public void testPutKeyWithNoKey() throws Throwable {
        assertThat((UnsupportedOperationException) assertThrows(WrappingAPIException.class, () -> {
            try (GitStorage gs = new GitStorage(source, null); InputStream test3 = getInputStream(1); InputStream mtest3 = getUsers()) {
                SourceInfo si = mock(SourceInfo.class);
                byte[] data = readData("{\"one\" : \"two\"}");
                String message = "one message";
                String userInfo = "test@test";
                String key = "key3";
                when(si.getSourceInputStream()).thenReturn(test3);
                when(si.getMetadataInputStream()).thenReturn(mtest3);
                when(si.getSourceVersion()).thenReturn(SHA_1);
                when(si.getMetaDataVersion()).thenReturn(SHA_1_MD);

                when(source.getSourceInfo(Mockito.eq("key3"), Mockito.anyString())).thenReturn(si);
                when(source.modify(Mockito.eq(key), Mockito.any(), Mockito.eq(data), Mockito.eq(SHA_1), Mockito.eq(message), Mockito.eq(userInfo),
                        Mockito.anyString())).thenReturn(CompletableFuture.completedFuture(SHA_2));
                Future<Optional<StoreInfo>> first = gs.get(key, null);
                StoreInfo storeInfo = first.get().get();
                assertNotNull(storeInfo);
                assertNotEquals(data, storeInfo.getData());
                CompletableFuture<String> put = gs.put("other", null, data, SHA_1, message, userInfo, null);
                try {
                    put.get();
                } catch (Exception e) {
                    throw e.getCause();
                }
            }
        }).getCause(), Matchers.isA(UnsupportedOperationException.class));
    }

    @Test
    public void testAddKey() throws JsonProcessingException, IOException {
        when(source.addKey(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(CompletableFuture.completedFuture(Pair.of("1", "1")));
        try (GitStorage gs = new GitStorage(source, null)) {
            byte[] data = getByteArray(1);
            byte[] pretty = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(MAPPER.readTree(data));
            Future<StoreInfo> future = gs.add("somekey", "refs/heads/master", pretty, new StorageData(new HashSet<>(), null, false, false, List.of()), "msg",
                    "user", "mail");
            StoreInfo si = unwrap(future);
            assertArrayEquals(pretty, si.getData());
            assertEquals("1", si.getVersion());
        }
    }

    @Test
    public void testPutMetaDataKey() throws Exception {
        try (GitStorage gs = new GitStorage(source, null); InputStream test3 = getInputStream(1); InputStream mtest3 = getUsers()) {
            SourceInfo si = mock(SourceInfo.class);

            String message = "one message";
            String usermail = "test@test";
            String userInfo = "info";
            String key = "key3";
            when(si.getSourceInputStream()).thenReturn(test3);
            when(si.getMetadataInputStream()).thenReturn(mtest3);
            when(si.getSourceVersion()).thenReturn(SHA_1);
            when(si.getMetaDataVersion()).thenReturn(SHA_1_MD);

            when(source.getSourceInfo(Mockito.eq("key3"), Mockito.anyString())).thenReturn(si);
            when(source.modify(Mockito.<StorageData>any(), Mockito.eq(SHA_1_MD), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                    .thenReturn(CompletableFuture.completedFuture(SHA_2_MD));
            Future<Optional<StoreInfo>> first = gs.get(key, null);
            StoreInfo storeInfo = first.get().get();
            assertNotNull(storeInfo);

            StorageData sd = new StorageData(storeInfo.getStorageData().getUsers(), "application/test", false, false, List.of());
            CompletableFuture<String> put = gs.putMetaData(key, null, sd, storeInfo.getMetaDataVersion(), message, userInfo, usermail);
            String newVersion = put.get();
            assertEquals(SHA_2_MD, newVersion);
            first = gs.get(key, null);
            storeInfo = first.get().get();
            assertNotNull(storeInfo);
            assertEquals(sd, storeInfo.getStorageData());
        }
    }

    private StoreInfo unwrap(Future<StoreInfo> future) {
        try {
            return future.get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    private byte[] getByteArray(int c) throws UnsupportedEncodingException {
        return ("{\"data\":\"value" + c + "\"}").getBytes(UTF_8);
    }

    private InputStream getInputStream(int c) throws UnsupportedEncodingException {
        return new ByteArrayInputStream(getByteArray(c));
    }

    private InputStream getUsers() throws UnsupportedEncodingException {
        return new ByteArrayInputStream("{\"users\": [{\"user\": \"user\",\"password\": \"1234\"}]}".getBytes(UTF_8));
    }

    private InputStream getMetaDataHiddenInputStream() throws UnsupportedEncodingException {
        return new ByteArrayInputStream("{\"users\": [{\"user\": \"user\",\"password\": \"1234\"}],\"hidden\":true}".getBytes(UTF_8));
    }

    private InputStream getMetaDataProtectedInputStream() throws UnsupportedEncodingException {
        return new ByteArrayInputStream("{\"users\": [{\"user\": \"user\",\"password\": \"1234\"}],\"protected\":true}".getBytes(UTF_8));
    }
}
