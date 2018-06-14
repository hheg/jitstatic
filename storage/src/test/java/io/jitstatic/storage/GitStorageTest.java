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
import java.util.function.Supplier;

import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.lib.Constants;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spencerwi.either.Either;

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
    public void testGetAKey() throws Exception {
        try (GitStorage gs = new GitStorage(source, null); InputStream test1 = getInputStream(1); InputStream mtest1 = getMetaData();) {
            SourceInfo si1 = mock(SourceInfo.class);
            when(si1.getSourceInputStream()).thenReturn(test1);
            when(si1.getMetadataInputStream()).thenReturn(mtest1);
            when(si1.getSourceVersion()).thenReturn(SHA_1);
            when(si1.getMetaDataVersion()).thenReturn(SHA_1_MD);
            when(source.getSourceInfo(Mockito.eq("key"), Mockito.anyString())).thenReturn(si1);

            Supplier<Optional<StoreInfo>> completableSupplier = gs.getKey("key", null);
            assertNotNull(completableSupplier.get().get());
            gs.checkHealth();
        }
    }

    @Test
    public void testGetARootKey() throws Exception {
        try (GitStorage gs = new GitStorage(source, null); InputStream mtest1 = getMetaData();) {
            SourceInfo si1 = mock(SourceInfo.class);
            when(si1.getSourceInputStream()).thenReturn(null);
            when(si1.getMetadataInputStream()).thenReturn(mtest1);
            when(si1.getSourceVersion()).thenReturn(null);
            when(si1.getMetaDataVersion()).thenReturn(SHA_1_MD);
            when(source.getSourceInfo(Mockito.eq("root/"), Mockito.anyString())).thenReturn(si1);

            Supplier<Optional<StoreInfo>> completableSupplier = gs.getKey("root/", null);
            gs.checkHealth();
            StoreInfo storeInfo = completableSupplier.get().get();
            assertNotNull(storeInfo);
            assertThrows(IllegalStateException.class, () -> storeInfo.getData());
            assertNotNull(storeInfo.getStorageData());
            gs.checkHealth();
        }
    }

    @Test
    public void testPutARootKey() throws Exception {
        try (GitStorage gs = new GitStorage(source, null); InputStream mtest1 = getMetaData();) {
            SourceInfo si1 = mock(SourceInfo.class);
            when(si1.getSourceInputStream()).thenReturn(null);
            when(si1.getMetadataInputStream()).thenReturn(mtest1);
            when(si1.getSourceVersion()).thenReturn(null);
            when(si1.getMetaDataVersion()).thenReturn(SHA_1_MD);
            when(source.getSourceInfo(Mockito.eq("root/"), Mockito.anyString())).thenReturn(si1);
            when(source.modify(Mockito.<StorageData>any(), Mockito.eq(SHA_1_MD), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                    Mockito.any())).thenReturn((SHA_2_MD));

            Supplier<Optional<StoreInfo>> completableSupplier = gs.getKey("root/", null);
            StoreInfo storeInfo = completableSupplier.get().get();
            assertNotNull(storeInfo);
            assertThrows(IllegalStateException.class, () -> storeInfo.getData());
            assertNotNull(storeInfo.getStorageData());

            StorageData sd = new StorageData(Set.of(new User("u", "p")), "text/plain", false, false, List.of());
            Supplier<Either<String, FailedToLock>> putMetaData = gs.putMetaData("root/", null, sd, SHA_1_MD, "msg", "info", "mail");
            assertEquals(SHA_2_MD, putMetaData.get().getLeft());
            Supplier<Optional<StoreInfo>> completableSupplier2 = gs.getKey("root/", null);
            assertEquals(completableSupplier2.get().get().getMetaDataVersion(), SHA_2_MD);
            gs.checkHealth();
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
                InputStream mtest1 = getMetaData();
                InputStream test2 = getInputStream(2);
                InputStream mtest2 = getMetaData();) {
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
            when(source.getSourceInfo(Mockito.eq("key"), Mockito.eq(REF_HEADS_MASTER))).thenReturn(si1).thenReturn(si2);
            when(source.getRefId(Mockito.anyString())).thenReturn("1").thenReturn("2");

            gs.reload(List.of(REF_HEADS_MASTER));
            StoreInfo storage = new StoreInfo(readData("{\"data\":\"value1\"}"), new StorageData(users, null, false, false, List.of()),
                    SHA_1, SHA_1_MD);
            assertTrue(Arrays.equals(storage.getData(), gs.getKey("key", null).get().get().getData()));
            gs.reload(List.of(REF_HEADS_MASTER));
            storage = new StoreInfo(readData("{\"data\":\"value2\"}"), new StorageData(users, null, false, false, List.of()), SHA_2,
                    SHA_2_MD);
            assertArrayEquals(storage.getData(), gs.getKey("key", null).get().get().getData());
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
                InputStream mtest3 = getMetaData();
                InputStream test4 = getInputStream(2);
                InputStream mtest4 = getMetaData()) {
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
            Supplier<Optional<StoreInfo>> key3Data = gs.getKey("key3", null);
            Supplier<Optional<StoreInfo>> key4Data = gs.getKey("key4", null);
            assertNotNull(key3Data.get().get());
            assertNotNull(key4Data.get().get());
            gs.checkHealth();
        }
    }

    @Test
    public void testCheckHealth() throws Exception {
        NullPointerException npe = new NullPointerException("Test exception");
        when(source.getSourceInfo(Mockito.anyString(), Mockito.anyString())).thenThrow(npe);
        assertSame(assertThrows(NullPointerException.class, () -> {
            try (GitStorage gs = new GitStorage(source, null);) {
                try {
                    gs.getKey("", null).get();
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
        when(source.getRefId(Mockito.anyString())).thenReturn("1");

        try (GitStorage gs = new GitStorage(source, null); InputStream is = getInputStream(0); InputStream md = getMetaData()) {

            gs.reload(List.of(REF_HEADS_MASTER));
            assertNull(gs.getKey("test3.json", null).get());
            assertEquals(cause.getLocalizedMessage(), assertThrows(RuntimeException.class, () -> gs.checkHealth()).getLocalizedMessage());
            Mockito.reset(source);
            SourceInfo info = mock(SourceInfo.class);
            when(info.getSourceVersion()).thenReturn(SHA_1);
            when(info.getMetaDataVersion()).thenReturn(SHA_1_MD);
            when(info.getMetadataInputStream()).thenReturn(md);
            when(info.getSourceInputStream()).thenReturn(is);
            when(source.getSourceInfo(Mockito.anyString(), Mockito.anyString())).thenReturn(info);
            assertNotNull(gs.getKey("test3.json", null).get());
            gs.checkHealth();
        }
    }

    @Test
    public void testCheckHealthWithOldFault() throws Exception {
        RuntimeException cause = new RuntimeException("Fault reading something");
        doThrow(cause).when(source).getSourceInfo(Mockito.anyString(), Mockito.anyString());
        when(source.getRefId(Mockito.anyString())).thenReturn("1");

        assertSame(cause, assertThrows(RuntimeException.class, () -> {
            try (GitStorage gs = new GitStorage(source, null);) {
                gs.reload(List.of(REF_HEADS_MASTER));
                assertNull(gs.getKey("key", null).get());
                assertEquals(cause.getLocalizedMessage(),
                        assertThrows(RuntimeException.class, () -> gs.checkHealth()).getLocalizedMessage());
                gs.getKey("key", null).get();
                gs.checkHealth();
            }
        }));
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
                InputStream mtest3 = getMetaData();
                InputStream test4 = getInputStream(2);
                InputStream mtest4 = getMetaData()) {
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
            Supplier<Optional<StoreInfo>> key3Data = gs.getKey("key3", null);
            assertNotNull(key3Data.get());
            Supplier<Optional<StoreInfo>> key4Data = gs.getKey("key4", null);
            assertNotNull(key4Data.get());
            key4Data = gs.getKey("key4", REF_HEADS_MASTER);
            assertNotNull(key4Data.get().get());
            gs.checkHealth();
        }

    }

    @Test
    public void testPutAKey() throws Exception {
        try (GitStorage gs = new GitStorage(source, null); InputStream test3 = getInputStream(1); InputStream mtest3 = getMetaData()) {
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
                    Mockito.anyString())).thenReturn((SHA_2));
            Supplier<Optional<StoreInfo>> first = gs.getKey(key, null);
            StoreInfo storeInfo = first.get().get();
            assertNotNull(storeInfo);
            assertNotEquals(data, storeInfo.getData());
            Supplier<Either<String, FailedToLock>> put = gs.put(key, null, data, SHA_1, message, userInfo, "");
            String newVersion = put.get().getLeft();
            assertEquals(SHA_2, newVersion);
            first = gs.getKey(key, null);
            storeInfo = first.get().get();
            assertNotNull(storeInfo);
            assertArrayEquals(data, storeInfo.getData());
            gs.checkHealth();
        }
    }

    @Test
    public void testPutAOnANonWritableKey() throws Throwable {
        assertThat((UnsupportedOperationException) assertThrows(WrappingAPIException.class, () -> {
            try (GitStorage gs = new GitStorage(source, null);
                    InputStream test3 = getInputStream(1);
                    InputStream mtest3 = getMetaDataProtectedInputStream()) {
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
                when(source.modify(Mockito.eq(key), Mockito.any(), Mockito.any(), Mockito.eq(SHA_1), Mockito.eq(message),
                        Mockito.anyString(), Mockito.anyString())).thenReturn((SHA_2));
                Supplier<Optional<StoreInfo>> first = gs.getKey(key, null);
                StoreInfo storeInfo = first.get().get();
                assertNotNull(storeInfo);
                assertNotEquals(data, storeInfo.getData());
                Supplier<Either<String, FailedToLock>> put = gs.put(key, null, data, SHA_1, message, userInfo, "");
                put.get();
            }
        }).getCause(), Matchers.isA(UnsupportedOperationException.class));
    }

    @Test
    public void testGetADotKey() throws Exception {
        try (GitStorage gs = new GitStorage(source, null)) {
            String key = ".key3";
            Supplier<Optional<StoreInfo>> first = gs.getKey(key, null);
            assertFalse(first.get().isPresent());
            gs.checkHealth();
        }
    }

    @Test
    public void testGetATrainDotKey() throws Exception {
        try (GitStorage gs = new GitStorage(source, null)) {
            String key = "key/key/.key3";
            Supplier<Optional<StoreInfo>> first = gs.getKey(key, null);
            assertFalse(first.get().isPresent());
            gs.checkHealth();
        }
    }

    @Test
    public void testGetAHiddenFile() throws Exception {
        SourceInfo si = mock(SourceInfo.class);
        when(si.getMetadataInputStream()).thenReturn(getMetaDataHiddenInputStream());
        when(source.getSourceInfo("key", REF_HEADS_MASTER)).thenReturn(si);
        try (GitStorage gs = new GitStorage(source, null)) {
            Supplier<Optional<StoreInfo>> key = gs.getKey("key", null);
            assertFalse(key.get().isPresent());
            gs.checkHealth();
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
                gs.checkHealth();
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
                Supplier<Either<String, FailedToLock>> put = gs.put(key, null, data, SHA_1, message, userInfo, null);
                gs.checkHealth();
                put.get();
            }
        }).getCause(), Matchers.isA(RefNotFoundException.class));
    }

    @Test
    public void testPutKeyWithNoKey() throws Throwable {
        assertThat((UnsupportedOperationException) assertThrows(WrappingAPIException.class, () -> {
            try (GitStorage gs = new GitStorage(source, null); InputStream test3 = getInputStream(1); InputStream mtest3 = getMetaData()) {
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
                when(source.modify(Mockito.eq(key), Mockito.any(), Mockito.eq(data), Mockito.eq(SHA_1), Mockito.eq(message),
                        Mockito.anyString(), Mockito.anyString())).thenReturn((SHA_2));
                Supplier<Optional<StoreInfo>> first = gs.getKey(key, null);
                StoreInfo storeInfo = first.get().get();
                assertNotNull(storeInfo);
                assertNotEquals(data, storeInfo.getData());
                Supplier<Either<String, FailedToLock>> put = gs.put("other", null, data, SHA_1, message, userInfo, null);
                gs.checkHealth();
                put.get();
            }
        }).getCause(), Matchers.isA(UnsupportedOperationException.class));
    }

    @Test
    public void testAddKey() throws Exception {
        when(source.addKey(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn((Pair.of("1", "1")));
        try (GitStorage gs = new GitStorage(source, null)) {
            byte[] data = getByteArray(1);
            byte[] pretty = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(MAPPER.readTree(data));
            Supplier<StoreInfo> future = gs.add("somekey", "refs/heads/master", pretty,
                    new StorageData(new HashSet<>(), null, false, false, List.of()), "msg", "user", "mail");
            StoreInfo si = unwrap(future);
            assertArrayEquals(pretty, si.getData());
            assertEquals("1", si.getVersion());
            gs.checkHealth();
        }
    }

    @Test
    public void testPutMetaDataKey() throws Exception {
        try (GitStorage gs = new GitStorage(source, null); InputStream test3 = getInputStream(1); InputStream mtest3 = getMetaData()) {
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
            when(source.modify(Mockito.<StorageData>any(), Mockito.eq(SHA_1_MD), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                    Mockito.any())).thenReturn((SHA_2_MD));
            java.util.function.Supplier<Optional<StoreInfo>> first = gs.getKey(key, null);
            StoreInfo storeInfo = first.get().get();
            assertNotNull(storeInfo);

            StorageData sd = new StorageData(storeInfo.getStorageData().getUsers(), "application/test", false, false, List.of());
            Supplier<Either<String, FailedToLock>> put = gs.putMetaData(key, null, sd, storeInfo.getMetaDataVersion(), message, userInfo,
                    usermail);
            String newVersion = put.get().getLeft();
            assertEquals(SHA_2_MD, newVersion);
            first = gs.getKey(key, null);
            storeInfo = first.get().get();
            assertNotNull(storeInfo);
            assertEquals(sd, storeInfo.getStorageData());
            gs.checkHealth();
        }
    }

    @Test
    public void testDelete() throws Exception {
        try (GitStorage gs = new GitStorage(source, null); InputStream test3 = getInputStream(1); InputStream mtest3 = getMetaData()) {
            SourceInfo si = mock(SourceInfo.class);

            String message = "one message";
            String usermail = "test@test";
            String userInfo = "info";
            String key = "key3";
            when(si.getSourceInputStream()).thenReturn(test3);
            when(si.getMetadataInputStream()).thenReturn(mtest3);
            when(si.getSourceVersion()).thenReturn(SHA_1);
            when(si.getMetaDataVersion()).thenReturn(SHA_1_MD);

            when(source.getSourceInfo(Mockito.eq(key), Mockito.anyString())).thenReturn(si);
            StoreInfo key2 = gs.getKey(key, null).get().get();
            assertNotNull(key2);
            gs.delete(key, null, userInfo, message, usermail);
            Thread.sleep(1000);
            gs.checkHealth();
            Mockito.verify(source).delete(Mockito.eq(key), Mockito.eq(REF_HEADS_MASTER), Mockito.eq(userInfo), Mockito.eq(message),
                    Mockito.eq(usermail));
        }
    }

    private StoreInfo unwrap(Supplier<StoreInfo> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private byte[] getByteArray(int c) throws UnsupportedEncodingException {
        return ("{\"data\":\"value" + c + "\"}").getBytes(UTF_8);
    }

    private InputStream getInputStream(int c) throws UnsupportedEncodingException {
        return new ByteArrayInputStream(getByteArray(c));
    }

    private InputStream getMetaData() throws UnsupportedEncodingException {
        return new ByteArrayInputStream("{\"users\": [{\"user\": \"user\",\"password\": \"1234\"}]}".getBytes(UTF_8));
    }

    private InputStream getMetaDataHiddenInputStream() throws UnsupportedEncodingException {
        return new ByteArrayInputStream("{\"users\": [{\"user\": \"user\",\"password\": \"1234\"}],\"hidden\":true}".getBytes(UTF_8));
    }

    private InputStream getMetaDataProtectedInputStream() throws UnsupportedEncodingException {
        return new ByteArrayInputStream("{\"users\": [{\"user\": \"user\",\"password\": \"1234\"}],\"protected\":true}".getBytes(UTF_8));
    }
}
