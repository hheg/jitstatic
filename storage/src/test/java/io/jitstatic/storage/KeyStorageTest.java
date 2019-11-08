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

import static io.jitstatic.JitStaticConstants.JITSTATIC_NOWHERE;
import static io.jitstatic.source.ObjectStreamProvider.toByte;
import static io.jitstatic.source.ObjectStreamProvider.toProvider;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectLoader;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.codahale.metrics.MetricRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spencerwi.either.Either;

import io.jitstatic.CommitMetaData;
import io.jitstatic.JitStaticConstants;
import io.jitstatic.MetaData;
import io.jitstatic.Role;
import io.jitstatic.auth.User;
import io.jitstatic.auth.UserData;
import io.jitstatic.hosted.FailedToLock;
import io.jitstatic.hosted.RefLockHolder;
import io.jitstatic.hosted.StoreInfo;
import io.jitstatic.source.ObjectStreamProvider;
import io.jitstatic.source.Source;
import io.jitstatic.source.SourceInfo;
import io.jitstatic.storage.ref.LocalRefLockService;
import io.jitstatic.test.BaseTest;
import io.jitstatic.utils.Functions;
import io.jitstatic.utils.Functions.ThrowingSupplier;
import io.jitstatic.utils.Pair;
import io.jitstatic.utils.WrappingAPIException;

public class KeyStorageTest extends BaseTest {

    private static final Charset UTF_8 = StandardCharsets.UTF_8;
    private static final String REF_HEADS_MASTER = Constants.R_HEADS + Constants.MASTER;
    private static final String SHA_1 = "67adef5dab64f8f4cb50712ab24bda6605befa79";
    private static final String SHA_2 = "67adef5dab64f8f4cb50712ab24bda6605befa80";
    private static final String SHA_1_MD = "67adef5dab64f8f4cb50712ab24bda6605befa81";
    private static final String SHA_2_MD = "67adef5dab64f8f4cb50712ab24bda6605befa82";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    @SuppressWarnings("unchecked")
    private ThrowingSupplier<ObjectLoader, IOException> factory = mock(Functions.ThrowingSupplier.class);
    private Source source = mock(Source.class);
    private HashService hashService = new HashService();
    private MetricRegistry registry = new MetricRegistry();
    private LocalRefLockService clusterService;

    private ExecutorService defaultExecutor;
    private ExecutorService workStealer;

    @BeforeEach
    public void setup() {
        defaultExecutor = Executors.newCachedThreadPool(new NamingThreadFactory("test"));
        workStealer = Executors.newWorkStealingPool();
        clusterService = new LocalRefLockService(registry);
    }

    @AfterEach
    public void tearDown() throws Exception {
        clusterService.close();
        Exception e1 = BaseTest.shutdownExecutor(defaultExecutor);
        Exception e2 = BaseTest.shutdownExecutor(workStealer);
        if (e1 != null) {
            if (e2 != null) {
                e1.addSuppressed(e2);
            }
            throw e1;
        }
        if (e2 != null) {
            throw e2;
        }
    }

    @Test
    public void testGetAKey() throws Throwable {
        try (KeyStorage ks = new KeyStorage(source, null, hashService, clusterService, "root", defaultExecutor, workStealer, registry);
                InputStream mtest1 = getMetaDataInputStream();) {
            SourceInfo si1 = mock(SourceInfo.class);
            when(si1.getStreamProvider()).thenReturn(toProvider(getByteArray(1)));
            when(si1.readMetaData()).thenCallRealMethod();
            when(si1.getMetadataInputStream()).thenReturn(mtest1);
            when(si1.getSourceVersion()).thenReturn(SHA_1);
            when(si1.getMetaDataVersion()).thenReturn(SHA_1_MD);
            when(source.getSourceInfo(eq("key"), anyString())).thenReturn(si1);

            Optional<StoreInfo> key = ks.getKey("key", null).get();
            assertNotNull(key.get());
            ks.checkHealth();
        }
    }

    @Test
    public void testGetARootKey() throws Throwable {
        try (KeyStorage ks = new KeyStorage(source, null, hashService, clusterService, "root", defaultExecutor, workStealer, registry)) {
            assertEquals(UnsupportedOperationException.class, assertThrows(WrappingAPIException.class, () -> ks.getKey("root/", null)).getCause().getClass());
            ks.checkHealth();
        }
    }

    @Test
    public void testPutARootKey() throws Throwable {
        try (KeyStorage ks = new KeyStorage(source, null, hashService, clusterService, "root", defaultExecutor, workStealer, registry);
                InputStream mtest1 = getMetaDataInputStream();
                InputStream mtest2 = getMetaDataInputStream()) {
            SourceInfo si = mock(SourceInfo.class);
            when(source.modifyMetadata(any(), anyString(), anyString(), anyString(), any())).thenReturn(SHA_2_MD);
            when(si.readMetaData()).thenCallRealMethod();
            when(si.getMetaDataVersion()).thenReturn(SHA_1_MD);
            when(si.getMetadataInputStream()).thenReturn(mtest1).thenReturn(mtest2);
            when(source.getSourceInfo(eq("root/"), anyString())).thenReturn(si);
            when(source.getSourceInfo(eq("root"), anyString())).thenReturn(null);
            assertEquals(UnsupportedOperationException.class, assertThrows(WrappingAPIException.class, () -> ks.getKey("root/", null)).getCause().getClass());
            assertTrue(ks.getMetaKey("root/", null).get().isPresent());

            MetaData sd = new MetaData("text/plain", false, false, List.of(), Set.of(), Set.of());
            Either<String, FailedToLock> putMetaData = ks
                    .putMetaData("root/", null, sd, SHA_1_MD, new CommitMetaData("user", "mail", "msg", "test", JITSTATIC_NOWHERE))
                    .orTimeout(5, TimeUnit.SECONDS).join();
            assertTrue(putMetaData.isLeft());
            assertEquals(SHA_2_MD, putMetaData.getLeft());
            ks.checkHealth();
        }
    }

    @Test
    public void testInitGitStorageWithNullSource() {
        assertEquals("Source cannot be null", assertThrows(NullPointerException.class, () -> {
            try (KeyStorage ks = new KeyStorage(null, null, hashService, clusterService, "root", defaultExecutor, workStealer, registry);) {
            }
        }).getLocalizedMessage());
    }

    @Test
    public void testLoadCache() throws Throwable {
        Set<User> users = new HashSet<>();
        users.add(new User("user", "1234"));
        try (KeyStorage ks = new KeyStorage(source, null, hashService, clusterService, "root", defaultExecutor, workStealer, registry);
                InputStream mtest1 = getMetaDataInputStream();
                InputStream mtest2 = getMetaDataInputStream();) {
            SourceInfo si1 = mock(SourceInfo.class);
            SourceInfo si2 = mock(SourceInfo.class);

            when(si1.getStreamProvider()).thenReturn(toProvider(getByteArray(1)));
            when(si1.readMetaData()).thenCallRealMethod();
            when(si1.getMetadataInputStream()).thenReturn(mtest1);
            when(si2.getStreamProvider()).thenReturn(toProvider(getByteArray(2)));
            when(si2.readMetaData()).thenCallRealMethod();
            when(si2.getMetadataInputStream()).thenReturn(mtest2);
            when(si1.getSourceVersion()).thenReturn(SHA_1);
            when(si2.getSourceVersion()).thenReturn(SHA_2);

            when(si1.getMetaDataVersion()).thenReturn(SHA_1_MD);
            when(si2.getMetaDataVersion()).thenReturn(SHA_2_MD);
            when(source.getSourceInfo(eq("key"), eq(REF_HEADS_MASTER))).thenReturn(si1).thenReturn(si2);

            ks.reload(REF_HEADS_MASTER);
            StoreInfo storage = new StoreInfo(toProvider(readData("{\"data\":\"value1\"}")), new MetaData(null, false, false, List
                    .of(), Set.of(), Set.of()), SHA_1, SHA_1_MD);
            assertTrue(Arrays.equals(toByte(storage.getStreamProvider()), toByte(ks.getKey("key", null).get().get().getStreamProvider())));
            RefLockHolder refHolderLock = ks.getRefHolderLock(REF_HEADS_MASTER);
            refHolderLock.enqueueAndBlock(() -> null, () -> null, (e) -> ks.reload(REF_HEADS_MASTER)).orTimeout(5, TimeUnit.SECONDS).join();

            storage = new StoreInfo(toProvider(readData("{\"data\":\"value2\"}")), new MetaData(null, false, false, List
                    .of(), Set.of(), Set.of()), SHA_2, SHA_2_MD);
            assertArrayEquals(toByte(storage.getStreamProvider()), toByte(ks.getKey("key", null).get().get().getStreamProvider()));
            ks.checkHealth();
        }
    }

    private byte[] readData(String content) {
        return content.getBytes(UTF_8);
    }

    @Test
    public void testLoadNewCache() throws Throwable {
        try (KeyStorage ks = new KeyStorage(source, null, hashService, clusterService, "root", defaultExecutor, workStealer, registry);
                InputStream mtest3 = getMetaDataInputStream();
                InputStream mtest4 = getMetaDataInputStream()) {
            SourceInfo si1 = mock(SourceInfo.class);
            SourceInfo si2 = mock(SourceInfo.class);
            when(si1.getStreamProvider()).thenReturn(toProvider(getByteArray(1)));
            when(si1.readMetaData()).thenCallRealMethod();
            when(si1.getMetadataInputStream()).thenReturn(mtest3);
            when(si1.getSourceVersion()).thenReturn(SHA_1);
            when(si2.getStreamProvider()).thenReturn(toProvider(getByteArray(2)));
            when(si2.readMetaData()).thenCallRealMethod();
            when(si2.getMetadataInputStream()).thenReturn(mtest4);
            when(si2.getSourceVersion()).thenReturn(SHA_2);
            when(si1.getMetaDataVersion()).thenReturn(SHA_1_MD);
            when(si2.getMetaDataVersion()).thenReturn(SHA_2_MD);
            when(source.getSourceInfo(eq("key3"), anyString())).thenReturn(si1);
            when(source.getSourceInfo(eq("key4"), anyString())).thenReturn(si2);
            Optional<StoreInfo> key3Data = ks.getKey("key3", null).get();
            Optional<StoreInfo> key4Data = ks.getKey("key4", null).get();
            assertNotNull(key3Data.get());
            assertNotNull(key4Data.get());
            ks.checkHealth();
        }
    }

    @Test
    public void testCheckHealth() throws Exception {
        NullPointerException npe = new NullPointerException("Test exception");

        when(source.getSourceInfo(anyString(), anyString())).thenThrow(npe);
        assertSame(npe, assertThrows(NullPointerException.class, () -> {
            try (KeyStorage ks = new KeyStorage(source, null, hashService, clusterService, "root", defaultExecutor, workStealer, registry);) {
                try {
                    ks.getKey("", null).get();
                } catch (Exception ignore) {
                }
                ks.checkHealth();
            }
        }));
    }

    @Test
    public void testCheckHealthWithFault() throws Throwable {
        RuntimeException cause = new RuntimeException("Error reading something");
        SourceInfo info = mock(SourceInfo.class);
        when(info.getStreamProvider()).thenReturn(toProvider(getByteArray(1)));
        when(info.readMetaData()).thenCallRealMethod();
        when(info.getSourceVersion()).thenReturn(SHA_1);
        when(info.getMetaDataVersion()).thenReturn(SHA_1_MD);
        when(info.getMetadataInputStream()).thenReturn(getMetaDataInputStream());
        doThrow(cause).doReturn(info).when(source).getSourceInfo(anyString(), anyString());

        try (KeyStorage ks = new KeyStorage(source, null, hashService, clusterService, "root", defaultExecutor, workStealer, registry);) {
            ks.reload(REF_HEADS_MASTER);
            assertFalse(ks.getKey("test3.json", null).get().isPresent());
            assertEquals(cause.getLocalizedMessage(), assertThrows(RuntimeException.class, () -> ks.checkHealth()).getLocalizedMessage());
            assertNotNull(ks.getKey("test3.json", null).get());
            ks.checkHealth();
        }
    }

    @Test
    public void testCheckHealthWithOldFault() throws Exception {
        RuntimeException cause = new RuntimeException("Error reading something");
        doThrow(cause).when(source).getSourceInfo(anyString(), anyString());

        assertSame(cause, assertThrows(RuntimeException.class, () -> {
            try (KeyStorage ks = new KeyStorage(source, null, hashService, clusterService, "root", defaultExecutor, workStealer, registry);) {
                ks.reload(REF_HEADS_MASTER);
                assertFalse(ks.getKey("key", null).orTimeout(5, TimeUnit.SECONDS).join().isPresent());
                assertEquals(cause.getLocalizedMessage(), assertThrows(RuntimeException.class, () -> ks.checkHealth()).getLocalizedMessage());
                ks.getKey("key", null).orTimeout(5, TimeUnit.SECONDS).join();
                ks.checkHealth();
            }
        }));
    }

    @Test
    public void testSourceCloseFailed() {
        doThrow(new RuntimeException("Test Exception")).when(source).close();
        try (KeyStorage ks = new KeyStorage(source, null, hashService, clusterService, "root", defaultExecutor, workStealer, registry);) {
        }
    }

    @Test
    public void testRefIsFoundButKeyIsNot() throws Throwable {

        try (KeyStorage ks = new KeyStorage(source, null, hashService, clusterService, "root", defaultExecutor, workStealer, registry);
                InputStream mtest3 = getMetaDataInputStream();
                InputStream mtest4 = getMetaDataInputStream()) {
            SourceInfo si1 = mock(SourceInfo.class);
            SourceInfo si2 = mock(SourceInfo.class);

            when(si1.getStreamProvider()).thenReturn(toProvider(getByteArray(1)));
            when(si1.readMetaData()).thenCallRealMethod();
            when(si1.getMetadataInputStream()).thenReturn(mtest3);
            when(si1.getSourceVersion()).thenReturn(SHA_1);
            when(si2.getStreamProvider()).thenReturn(toProvider(getByteArray(2)));
            when(si2.readMetaData()).thenCallRealMethod();
            when(si2.getMetadataInputStream()).thenReturn(mtest4);
            when(si2.getSourceVersion()).thenReturn(SHA_2);
            when(si1.getMetaDataVersion()).thenReturn(SHA_1_MD);
            when(si2.getMetaDataVersion()).thenReturn(SHA_2_MD);
            when(source.getSourceInfo(eq("key3"), anyString())).thenReturn(si1);
            when(source.getSourceInfo(eq("key4"), anyString())).thenReturn(si2);

            Optional<StoreInfo> key3Data = ks.getKey("key3", null).get();
            assertNotNull(key3Data.get());
            Optional<StoreInfo> key4Data = ks.getKey("key4", null).get();
            assertNotNull(key4Data.get());
            key4Data = ks.getKey("key4", REF_HEADS_MASTER).get();
            assertNotNull(key4Data.get());
            ks.checkHealth();
        }

    }

    @Test
    public void testPutAKey() throws Throwable {
        try (KeyStorage ks = new KeyStorage(source, null, hashService, clusterService, "root", defaultExecutor, workStealer, registry);
                InputStream mtest3 = getMetaDataInputStream()) {
            SourceInfo si = mock(SourceInfo.class);
            byte[] data = readData("{\"one\" : \"two\"}");
            String key = "key3";

            when(si.getStreamProvider()).thenReturn(toProvider(getByteArray(1)));
            when(si.readMetaData()).thenCallRealMethod();
            when(si.getMetadataInputStream()).thenReturn(mtest3);
            when(si.getSourceVersion()).thenReturn(SHA_1);
            when(si.getMetaDataVersion()).thenReturn(SHA_1_MD);
            when(source.getSourceInfo(eq("key3"), anyString())).thenReturn(si);
            when(source.modifyKey(eq(key), any(), any(), any())).thenReturn(Pair.of(SHA_2, factory));
            Optional<StoreInfo> first = ks.getKey(key, null).get();
            StoreInfo storeInfo = first.get();
            assertNotNull(storeInfo);
            assertNotEquals(data, toByte(storeInfo.getStreamProvider()));
            Either<String, FailedToLock> put = ks
                    .putKey(key, null, toProvider(data), SHA_1, new CommitMetaData("user", "mail", "msg", "Test", JITSTATIC_NOWHERE))
                    .orTimeout(5, TimeUnit.SECONDS).join();
            String newVersion = put.getLeft();
            assertEquals(SHA_2, newVersion);
            first = ks.getKey(key, null).get();
            storeInfo = first.get();
            assertNotNull(storeInfo);
            assertArrayEquals(data, toByte(storeInfo.getStreamProvider()));
            ks.checkHealth();
        }
    }

    @Test
    public void testPutAOnANonWritableKey() throws Exception {
        assertThat((UnsupportedOperationException) assertThrows(WrappingAPIException.class, () -> {
            KeyStorage ks = new KeyStorage(source, null, hashService, clusterService, "root", defaultExecutor, workStealer, registry);
            try (ks; InputStream mtest3 = getMetaDataProtectedInputStream()) {
                SourceInfo si = mock(SourceInfo.class);
                byte[] data = readData("{\"one\" : \"two\"}");
                String key = "key3";
                when(si.getStreamProvider()).thenReturn(toProvider(data));
                when(si.readMetaData()).thenCallRealMethod();
                when(si.getMetadataInputStream()).thenReturn(mtest3);
                when(si.getSourceVersion()).thenReturn(SHA_1);
                when(si.getMetaDataVersion()).thenReturn(SHA_1_MD);

                when(source.getSourceInfo(eq(key), anyString())).thenReturn(si);
                when(source.modifyKey(eq(key), any(), any(), any())).thenReturn(Pair.of(SHA_2, factory));
                Optional<StoreInfo> first = ks.getKey(key, null).get();
                StoreInfo storeInfo = first.get();
                assertNotNull(storeInfo);
                assertNotEquals(data, toByte(storeInfo.getStreamProvider()));
                try {
                    ks.putKey(key, null, toProvider(data), SHA_1, new CommitMetaData("user", "mail", "msg", "Test", JITSTATIC_NOWHERE))
                            .orTimeout(5, TimeUnit.SECONDS).join();
                } catch (CompletionException ce) {
                    throw ce.getCause();
                }
            }
            ks.checkHealth();
        }).getCause(), Matchers.isA(UnsupportedOperationException.class));
    }

    @Test
    public void testGetADotKey() throws Throwable {
        try (KeyStorage ks = new KeyStorage(source, null, hashService, clusterService, "root", defaultExecutor, workStealer, registry)) {
            String key = ".key3";
            Optional<StoreInfo> first = ks.getKey(key, null).get();
            assertFalse(first.isPresent());
            ks.checkHealth();
        }
    }

    @Test
    public void testGetATrainDotKey() throws Throwable {
        try (KeyStorage ks = new KeyStorage(source, null, hashService, clusterService, "root", defaultExecutor, workStealer, registry)) {
            String key = "key/key/.key3";
            Optional<StoreInfo> first = ks.getKey(key, null).get();
            assertFalse(first.isPresent());
            ks.checkHealth();
        }
    }

    @Test
    public void testGetAHiddenFile() throws Throwable {
        SourceInfo si = mock(SourceInfo.class);

        when(si.getStreamProvider()).thenReturn(toProvider(getByteArray(1)));
        when(si.readMetaData()).thenCallRealMethod();
        when(si.getMetadataInputStream()).thenReturn(getMetaDataHiddenInputStream());
        when(source.getSourceInfo("key", REF_HEADS_MASTER)).thenReturn(si);
        try (KeyStorage ks = new KeyStorage(source, null, hashService, clusterService, "root", defaultExecutor, workStealer, registry)) {
            Optional<StoreInfo> key = ks.getKey("key", null).get();
            assertFalse(key.isPresent());
            ks.checkHealth();
        }
    }

    @Test
    public void testPutKeyWithEmptyMessage() throws Throwable {
        try (KeyStorage ks = new KeyStorage(source, null, hashService, clusterService, "root", defaultExecutor, workStealer, registry);) {
            assertThrows(IllegalArgumentException.class, () -> {
                byte[] data = readData("{\"one\" : \"two\"}");
                String key = "key3";
                ks.putKey(key, null, toProvider(data), SHA_1, new CommitMetaData("user", "mail", "", "Test", JITSTATIC_NOWHERE));
            });
            ks.checkHealth();
        }
    }

    @Test
    public void testPutKeyWithNoRef() {
        assertThrows(RefNotFoundException.class, () -> {
            try (KeyStorage ks = new KeyStorage(source, null, hashService, clusterService, "root", defaultExecutor, workStealer, registry);) {
                byte[] data = readData("{\"one\" : \"two\"}");
                String key = "key3";
                ks.checkHealth();
                ks.putKey(key, "refs/heads/noref", toProvider(data), SHA_1, new CommitMetaData("user", "mail", "msg", "Test", JITSTATIC_NOWHERE));
            }
        });
    }

    @Test
    public void testPutKeyWithNoKey() throws Throwable {
        assertThat((UnsupportedOperationException) assertThrows(WrappingAPIException.class, () -> {
            try (KeyStorage ks = new KeyStorage(source, null, hashService, clusterService, "root", defaultExecutor, workStealer, registry);
                    InputStream mtest3 = getMetaDataInputStream()) {
                SourceInfo si = mock(SourceInfo.class);
                byte[] data = readData("{\"one\" : \"two\"}");
                String key = "key3";
                when(si.getStreamProvider()).thenReturn(toProvider(getByteArray(1)));
                when(si.readMetaData()).thenCallRealMethod();
                when(si.getMetadataInputStream()).thenReturn(mtest3);
                when(si.getSourceVersion()).thenReturn(SHA_1);
                when(si.getMetaDataVersion()).thenReturn(SHA_1_MD);
                when(source.getSourceInfo(eq("key3"), anyString())).thenReturn(si);
                when(source.modifyKey(eq(key), any(), any(), any())).thenReturn(Pair.of(SHA_2, factory));
                Optional<StoreInfo> first = ks.getKey(key, null).get();
                StoreInfo storeInfo = first.get();
                assertNotNull(storeInfo);
                assertNotEquals(data, toByte(storeInfo.getStreamProvider()));
                ks.checkHealth();
                try {
                    ks.putKey("other", null, toProvider(data), SHA_1, new CommitMetaData("user", "mail", "msg", "Test", JITSTATIC_NOWHERE))
                            .orTimeout(5, TimeUnit.SECONDS).join();
                } catch (CompletionException ce) {
                    throw ce.getCause();
                }
            }
        }).getCause(), Matchers.isA(UnsupportedOperationException.class));
    }

    @Test
    public void testAddKey() throws Throwable {
        when(source.addKey(any(), any(), any(), any(), any())).thenReturn(Pair.of(Pair.of(factory, "1"), "1"));
        try (KeyStorage ks = new KeyStorage(source, null, hashService, clusterService, "root", defaultExecutor, workStealer, registry)) {
            byte[] data = getByteArray(1);
            byte[] pretty = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(MAPPER.readTree(data));
            String si = ks
                    .addKey("somekey", "refs/heads/master", toProvider(pretty), new MetaData(null, false, false, List.of(), Set.of(), Set
                            .of()), new CommitMetaData("user", "mail", "msg", "Test", JITSTATIC_NOWHERE))
                    .orTimeout(5, TimeUnit.SECONDS).join();
            assertEquals("1", si);
            ks.checkHealth();
        }
    }

    @Test
    public void testPutMetaDataKey() throws Throwable {
        try (KeyStorage ks = new KeyStorage(source, null, hashService, clusterService, "root", defaultExecutor, workStealer, registry);
                InputStream mtest3 = getMetaDataInputStream()) {
            SourceInfo si = mock(SourceInfo.class);
            String key = "key3";

            when(si.getStreamProvider()).thenReturn(toProvider(getByteArray(1)));
            when(si.readMetaData()).thenCallRealMethod();
            when(si.getMetadataInputStream()).thenReturn(mtest3);
            when(si.getSourceVersion()).thenReturn(SHA_1);
            when(si.getMetaDataVersion()).thenReturn(SHA_1_MD);
            when(source.getSourceInfo(eq(key), anyString())).thenReturn(si);
            when(source.modifyMetadata(any(), anyString(), any(), any(), any())).thenReturn((SHA_2_MD));
            Optional<StoreInfo> first = ks.getKey(key, null).get();
            StoreInfo storeInfo = first.get();
            assertNotNull(storeInfo);
            MetaData sd = new MetaData("application/test", false, false, List.of(), Set.of(), Set.of());
            Either<String, FailedToLock> put = ks
                    .putMetaData(key, null, sd, storeInfo.getMetaDataVersion(), new CommitMetaData("user", "mail", "msg", "Test", JITSTATIC_NOWHERE))
                    .orTimeout(5, TimeUnit.SECONDS).join();
            String newVersion = put.getLeft();
            assertEquals(SHA_2_MD, newVersion);
            first = ks.getKey(key, null).get();
            storeInfo = first.get();
            assertNotNull(storeInfo);
            assertEquals(sd, storeInfo.getMetaData());
            ks.checkHealth();
        }
    }

    @Test
    public void testDelete() throws Throwable {
        try (KeyStorage ks = new KeyStorage(source, null, hashService, clusterService, "root", defaultExecutor, workStealer, registry);
                InputStream mtest3 = getMetaDataInputStream()) {
            SourceInfo si = mock(SourceInfo.class);
            String key = "key3";

            when(si.getStreamProvider()).thenReturn(toProvider(getByteArray(1)));
            when(si.readMetaData()).thenCallRealMethod();
            when(si.getMetadataInputStream()).thenReturn(mtest3);
            when(si.getSourceVersion()).thenReturn(SHA_1);
            when(si.getMetaDataVersion()).thenReturn(SHA_1_MD);
            when(source.getSourceInfo(eq(key), anyString())).thenReturn(si);
            StoreInfo key2 = ks.getKey(key, null).get().get();
            assertNotNull(key2);
            ks.delete(key, null, new CommitMetaData("user", "mail", "msg", "Test", JITSTATIC_NOWHERE)).orTimeout(5, TimeUnit.SECONDS).join();
            TimeUnit.SECONDS.sleep(1);
            ks.checkHealth();
            Mockito.verify(source).deleteKey(eq(key), eq(REF_HEADS_MASTER), any());
        }
    }

    @Test
    public void testDeleteMetaKey() throws IOException {
        try (KeyStorage ks = new KeyStorage(source, null, hashService, clusterService, "root", defaultExecutor, workStealer, registry)) {
            assertSame(UnsupportedOperationException.class, assertThrows(WrappingAPIException.class, () -> ks
                    .delete("key/", null, new CommitMetaData("user", "mail", "msg", "Test", JITSTATIC_NOWHERE))).getCause().getClass());
        }
    }

    @Test
    public void testAddkeyWithNewBranch() throws Throwable {
        String key = "somekey";
        String branch = "refs/heads/newbranch";

        when(source.getSourceInfo(eq(key), eq(branch))).thenThrow(RefNotFoundException.class);
        when(source.addKey(eq(key), eq(branch), any(), any(), any())).thenReturn(Pair.of(Pair.of(factory, "1"), "1"));
        try (KeyStorage ks = new KeyStorage(source, null, hashService, clusterService, "root", defaultExecutor, workStealer, registry)) {
            byte[] data = getByteArray(1);
            byte[] pretty = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(MAPPER.readTree(data));
            assertThrows(RefNotFoundException.class, () -> {
                try {
                    ks.addKey(key, branch, toProvider(pretty), new MetaData(null, false, false, List
                            .of(), Set.of(), Set.of()), new CommitMetaData("user", "mail", "msg", "Test", JITSTATIC_NOWHERE)).orTimeout(5, TimeUnit.SECONDS)
                            .join();
                } catch (CompletionException ce) {
                    throw ce.getCause();
                }
            });
            ks.checkHealth();
        }
    }

    @Test
    public void testAddKeyWithExistingKey() throws Exception {
        String key = "somekey";
        String branch = "refs/heads/newbranch";
        byte[] data = getByteArray(1);
        byte[] pretty = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(MAPPER.readTree(data));
        SourceInfo sourceInfo = mock(SourceInfo.class);
        MetaData metaData = mock(MetaData.class);

        when(sourceInfo.readMetaData()).thenReturn(metaData);
        when(sourceInfo.getMetaDataVersion()).thenReturn("2");
        when(sourceInfo.getSourceVersion()).thenReturn("1");
        when(sourceInfo.getStreamProvider()).thenReturn(toProvider(pretty));
        when(source.getSourceInfo(eq(key), eq(branch))).thenReturn(sourceInfo);
        try (KeyStorage ks = new KeyStorage(source, null, hashService, clusterService, "root", defaultExecutor, workStealer, registry)) {
            ks.addRef(branch);
            assertSame(KeyAlreadyExist.class, assertThrows(WrappingAPIException.class, () -> {
                try {
                    ks.addKey(key, branch, toProvider(pretty), new MetaData(null, false, false, List.of(), Set.of(), Set
                            .of()), new CommitMetaData("user", "mail", "msg", "Test", JITSTATIC_NOWHERE)).orTimeout(5, TimeUnit.SECONDS).join();
                } catch (CompletionException ce) {
                    throw ce.getCause();
                }
            }).getCause().getClass());
        }
    }

    @Test
    public void testGetListForRef() throws RefNotFoundException, IOException {
        try (KeyStorage ks = new KeyStorage(source, null, hashService, clusterService, "root", defaultExecutor, workStealer, registry);
                InputStream mtest3 = getMetaDataInputStream()) {
            SourceInfo si = mock(SourceInfo.class);
            String key = "dir/";
            String dirkey = "dir/key";

            when(si.getStreamProvider()).thenReturn(toProvider(getByteArray(1)));
            when(si.readMetaData()).thenCallRealMethod();
            when(si.getMetadataInputStream()).thenReturn(mtest3);
            when(si.getSourceVersion()).thenReturn(SHA_1);
            when(si.getMetaDataVersion()).thenReturn(SHA_1_MD);
            when(source.getSourceInfo(eq(dirkey), anyString())).thenReturn(si);
            when(source.getList(eq(key), anyString(), Mockito.anyBoolean())).thenReturn(List.of(dirkey));
            List<Pair<String, Boolean>> keys = List.of(Pair.of(key, false));
            List<Pair<String, StoreInfo>> list = ks.getListForRef(keys, REF_HEADS_MASTER).orTimeout(5, TimeUnit.SECONDS).join();
            assertEquals(1, list.size());
            list = ks.getListForRef(keys, REF_HEADS_MASTER).orTimeout(5, TimeUnit.SECONDS).join();
            assertEquals(1, list.size());
        }
    }

    @Test
    public void testGetList() throws Exception {
        try (KeyStorage ks = new KeyStorage(source, null, hashService, clusterService, "root", defaultExecutor, workStealer, registry);
                InputStream mtest3 = getMetaDataInputStream()) {
            SourceInfo si = mock(SourceInfo.class);
            String key = "dir/";
            String dirkey = "dir/key";

            when(si.getStreamProvider()).thenReturn(toProvider(getByteArray(1)));
            when(si.readMetaData()).thenCallRealMethod();
            when(si.getMetadataInputStream()).thenReturn(mtest3);
            when(si.getSourceVersion()).thenReturn(SHA_1);
            when(si.getMetaDataVersion()).thenReturn(SHA_1_MD);
            when(source.getSourceInfo(eq(dirkey), anyString())).thenReturn(si);
            when(source.getList(eq(key), anyString(), Mockito.anyBoolean())).thenReturn(List.of(dirkey));
            List<Pair<List<Pair<String, Boolean>>, String>> keys = List.of(Pair.of(List.of(Pair.of(key, false)), REF_HEADS_MASTER));
            List<Pair<List<Pair<String, StoreInfo>>, String>> list = ks.getList(keys).orTimeout(5, TimeUnit.SECONDS).join();
            assertEquals(1, list.size());
            Pair<List<Pair<String, StoreInfo>>, String> masterResult = list.get(0);
            assertEquals(REF_HEADS_MASTER, masterResult.getRight());
            assertFalse(masterResult.getLeft().isEmpty());
            list = ks.getList(keys).orTimeout(5, TimeUnit.SECONDS).join();
            assertEquals(1, list.size());
            masterResult = list.get(0);
            assertEquals(REF_HEADS_MASTER, masterResult.getRight());
            assertEquals(dirkey, masterResult.getLeft().get(0).getLeft());
        }
    }

    @Test
    public void testAclusterServiceotFile() {
        try (KeyStorage ks = new KeyStorage(source, null, hashService, clusterService, "root", defaultExecutor, workStealer, registry)) {
            assertThrows(WrappingAPIException.class, () -> ks.addKey("dot/.dot", null, toProvider(new byte[] { 1 }), new MetaData(Set.of(), Set
                    .of()), new CommitMetaData("d", "d", "d", "Test", JITSTATIC_NOWHERE)));
        }
    }

    @Test
    public void testGetUser() throws RefNotFoundException, IOException {
        when(source.getUser(anyString(), anyString())).thenReturn(Pair.of("1", new UserData(Set.of(new Role("role")), "1234", null, null)));
        try (KeyStorage ks = new KeyStorage(source, null, hashService, clusterService, "root", defaultExecutor, workStealer, registry)) {
            ks.addRef("refs/heads/secret");
            assertNotNull(ks.getUser("name", "refs/heads/secret", JitStaticConstants.JITSTATIC_GIT_REALM));
        }
    }

    @Test
    public void testGetListForNoKey() throws RefNotFoundException {
        when(source.getSourceInfo(eq("key"), eq("refs/heads/master"))).thenReturn(null);
        try (KeyStorage ks = new KeyStorage(source, null, hashService, clusterService, "root", defaultExecutor, workStealer, registry)) {
            List<Pair<String, StoreInfo>> listForRef = ks.getListForRef(List.of(Pair.of("key", false)), "refs/heads/master").orTimeout(5, TimeUnit.SECONDS)
                    .join();
            assertTrue(listForRef.isEmpty());
        }
    }

    @Test
    public void testGetListNoRef() throws RefNotFoundException, IOException {
        when(source.getList(eq("key/"), eq("refs/heads/master"), Mockito.anyBoolean())).thenThrow(new RefNotFoundException("test"));
        try (KeyStorage ks = new KeyStorage(source, null, hashService, clusterService, "root", defaultExecutor, workStealer, registry)) {
            List<Pair<String, StoreInfo>> listForRef = ks.getListForRef(List.of(Pair.of("key/", false)), "refs/heads/master").orTimeout(5, TimeUnit.SECONDS)
                    .join();
            assertTrue(listForRef.isEmpty());
        }
    }

    @Test
    public void testGetListIOException() throws RefNotFoundException, IOException {
        when(source.getList(eq("key/"), eq("refs/heads/master"), Mockito.anyBoolean())).thenThrow(new IOException("test"));
        try (KeyStorage ks = new KeyStorage(source, null, hashService, clusterService, "root", defaultExecutor, workStealer, registry)) {
            List<Pair<String, StoreInfo>> listForRef = ks.getListForRef(List.of(Pair.of("key/", false)), "refs/heads/master").orTimeout(5, TimeUnit.SECONDS)
                    .join();
            assertTrue(listForRef.isEmpty());
        }
    }

    @Test
    public void testGetListForAKey() throws RefNotFoundException, IOException {
        try (KeyStorage ks = new KeyStorage(source, null, hashService, clusterService, "root", defaultExecutor, workStealer, registry);
                InputStream mtest3 = getMetaDataInputStream()) {
            SourceInfo si = mock(SourceInfo.class);
            MetaData metaData = mock(MetaData.class);
            ObjectStreamProvider osp = mock(ObjectStreamProvider.class);

            when(si.getStreamProvider()).thenReturn(osp);
            when(si.readMetaData()).thenReturn(metaData);
            when(si.getMetadataInputStream()).thenReturn(mtest3);
            when(si.getSourceVersion()).thenReturn(SHA_1);
            when(si.getMetaDataVersion()).thenReturn(SHA_1_MD);
            when(si.isMetaDataSource()).thenReturn(false);
            when(source.getSourceInfo(eq("key"), eq("refs/heads/master"))).thenReturn(si);
            List<Pair<String, StoreInfo>> listForRef = ks.getListForRef(List.of(Pair.of("key", false)), REF_HEADS_MASTER).orTimeout(5, TimeUnit.SECONDS).join();
            assertEquals(1, listForRef.size());
            assertEquals("key", listForRef.get(0).getKey());
        }
    }

    @Test
    public void testGetUserDataNoBranch() throws RefNotFoundException, IOException {
        RefNotFoundException exception = new RefNotFoundException("Test");
        when(source.getUser(eq(".users/git/kit"), eq(REF_HEADS_MASTER))).thenThrow(exception);
        try (KeyStorage ks = new KeyStorage(source, null, hashService, clusterService, "root", defaultExecutor, workStealer, registry)) {
            assertSame(exception, assertThrows(CompletionException.class, () -> ks.getUserData("kit", null, JitStaticConstants.JITSTATIC_GIT_REALM).join())
                    .getCause().getCause());
            Mockito.verify(source).getUser(".users/git/kit", REF_HEADS_MASTER);
        }
    }

    @Test
    public void testGetUserDataIOError() throws RefNotFoundException, IOException {
        IOException exception = new IOException("Test");
        when(source.getUser(eq(".users/git/kit"), eq(REF_HEADS_MASTER))).thenThrow(exception);
        try (KeyStorage ks = new KeyStorage(source, null, hashService, clusterService, "root", defaultExecutor, workStealer, registry)) {
            assertSame(exception, assertThrows(CompletionException.class, () -> ks.getUserData("kit", null, JitStaticConstants.JITSTATIC_GIT_REALM).join())
                    .getCause().getCause());
            Mockito.verify(source).getUser(".users/git/kit", REF_HEADS_MASTER);
        }
    }

    @Test
    public void testUpdateUserNoRef() {
        try (KeyStorage ks = new KeyStorage(source, null, hashService, clusterService, "root", defaultExecutor, workStealer, registry)) {
            assertThrows(UnsupportedOperationException.class, () -> ks
                    .updateUser("kit", "refs/heads/noref", JitStaticConstants.JITSTATIC_GIT_REALM, "updater", new UserData(Set
                            .of(new Role("role")), "p", null, null), "1"));
        }
    }

    @Test
    public void testUpdateUser() throws RefNotFoundException, IOException {
        when(source.addUser(anyString(), anyString(), anyString(), any())).thenReturn("1");
        when(source.updateUser(anyString(), anyString(), anyString(), any())).thenReturn("2");
        try (KeyStorage ks = new KeyStorage(source, null, hashService, clusterService, "root", defaultExecutor, workStealer, registry)) {
            ks.addUser("kit", null, JitStaticConstants.JITSTATIC_GIT_REALM, "creator", new UserData(Set.of(new Role("role")), "pa", null, null))
                    .orTimeout(5, TimeUnit.SECONDS).orTimeout(5, TimeUnit.SECONDS).join();
            assertEquals("2", ks
                    .updateUser("kit", null, JitStaticConstants.JITSTATIC_GIT_REALM, "updater", new UserData(Set.of(new Role("role")), "pb", null, null), "1")
                    .orTimeout(5, TimeUnit.SECONDS).join().getLeft());
        }
    }

    @Test
    public void testUpdateUserNoKey() throws RefNotFoundException, IOException {
        when(source.addUser(anyString(), anyString(), anyString(), any())).thenReturn("1");
        when(source.updateUser(anyString(), anyString(), anyString(), any())).thenReturn("2");
        try (KeyStorage ks = new KeyStorage(source, null, hashService, clusterService, "root", defaultExecutor, workStealer, registry)) {
            ks.addUser("kit", null, JitStaticConstants.JITSTATIC_GIT_REALM, "creator", new UserData(Set.of(new Role("role")), "pa", null, null))
                    .orTimeout(5, TimeUnit.SECONDS).join();
            assertEquals("2", ks
                    .updateUser("kit", null, JitStaticConstants.JITSTATIC_GIT_REALM, "updater", new UserData(Set.of(new Role("role")), "pb", null, null), "1")
                    .orTimeout(5, TimeUnit.SECONDS).join().getLeft());
        }
    }

    @Test()
    public void testDeleteUser() throws RefNotFoundException, IOException {
        when(source.addUser(anyString(), anyString(), anyString(), any())).thenReturn("1");
        try (KeyStorage ks = new KeyStorage(source, null, hashService, clusterService, "root", defaultExecutor, workStealer, registry)) {
            ks.addUser("kit", null, JitStaticConstants.JITSTATIC_GIT_REALM, "creator", new UserData(Set.of(new Role("role")), "pa", null, null))
                    .orTimeout(5, TimeUnit.SECONDS).join();
            ks.deleteUser("kit", null, JitStaticConstants.JITSTATIC_GIT_REALM, "creator");
        }
    }

    @Test
    public void testAddUserRefNotFound() throws RefNotFoundException, IOException {
        when(source.addUser(anyString(), anyString(), anyString(), any())).thenThrow(RefNotFoundException.class);
        try (KeyStorage ks = new KeyStorage(source, null, hashService, clusterService, "root", defaultExecutor, workStealer, registry)) {
            assertEquals(UnsupportedOperationException.class, assertThrows(WrappingAPIException.class, () -> {
                try {
                    ks.addUser("kit", null, JitStaticConstants.JITSTATIC_GIT_REALM, "creator", new UserData(Set.of(new Role("role")), "pa", null, null))
                            .orTimeout(5, TimeUnit.SECONDS).join();
                } catch (CompletionException ce) {
                    throw ce.getCause();
                }
            }).getCause().getClass());
        }
    }

    @Test
    public void testAddUserReadError() throws RefNotFoundException, IOException {
        when(source.addUser(anyString(), anyString(), anyString(), any())).thenThrow(IOException.class);
        try (KeyStorage ks = new KeyStorage(source, null, hashService, clusterService, "root", defaultExecutor, workStealer, registry)) {
            assertEquals(IOException.class, assertThrows(UncheckedIOException.class, () -> {
                try {
                    ks.addUser("kit", null, JitStaticConstants.JITSTATIC_GIT_REALM, "creator", new UserData(Set.of(new Role("role")), "pa", null, null))
                            .orTimeout(5, TimeUnit.SECONDS).join();
                } catch (CompletionException ce) {
                    throw ce.getCause();
                }
            }).getCause().getClass());
        }
    }

    @Test
    public void testAddUserMatchingRoot() {
        try (KeyStorage ks = new KeyStorage(source, null, hashService, clusterService, "root", defaultExecutor, workStealer, registry)) {
            assertEquals("io.jitstatic.storage.KeyAlreadyExist: Key 'root' already exist in branch refs/heads/master", assertThrows(WrappingAPIException.class, () -> ks
                    .addUser("root", null, JitStaticConstants.JITSTATIC_GIT_REALM, "creator", new UserData(Set.of(new Role("role")), "pa", null, null)))
                            .getMessage());
        }
    }

    @Test
    public void testPutMetaDataDotKey() {
        MetaData md = mock(MetaData.class);
        CommitMetaData cmd = mock(CommitMetaData.class);
        try (KeyStorage ks = new KeyStorage(source, null, hashService, clusterService, "root", defaultExecutor, workStealer, registry)) {
            assertEquals(UnsupportedOperationException.class, assertThrows(WrappingAPIException.class, () -> ks.putMetaData(".key", null, md, "1", cmd)
                    .orTimeout(5, TimeUnit.SECONDS).join()).getCause().getClass());
        }
    }

    @Test
    public void testPutMetaDataWithNoRef() {
        MetaData md = mock(MetaData.class);
        CommitMetaData cmd = mock(CommitMetaData.class);
        try (KeyStorage ks = new KeyStorage(source, null, hashService, clusterService, "root", defaultExecutor, workStealer, registry)) {
            assertThrows(RefNotFoundException.class, () -> ks.putMetaData("key", "refs/heads/blah", md, "1", cmd)
                    .orTimeout(5, TimeUnit.SECONDS).join());
        }
    }

    @Test
    public void testDeleteFromTag() {
        CommitMetaData cmd = mock(CommitMetaData.class);
        try (KeyStorage ks = new KeyStorage(source, null, hashService, clusterService, "root", defaultExecutor, workStealer, registry)) {
            String tag = "refs/tags/blah";
            ks.addRef(tag);
            assertEquals(UnsupportedOperationException.class, assertThrows(WrappingAPIException.class, () -> ks.delete("key", tag, cmd)).getCause().getClass());
        }
    }

    private byte[] getByteArray(int c) {
        return ("{\"data\":\"value" + c + "\"}").getBytes(UTF_8);
    }

    private InputStream getMetaDataInputStream() { return new ByteArrayInputStream(getMetaData().getBytes(UTF_8)); }

    private InputStream getMetaDataHiddenInputStream() { return new ByteArrayInputStream(getMetaDataHidden().getBytes(UTF_8)); }

    private InputStream getMetaDataProtectedInputStream() { return new ByteArrayInputStream(getMetaDataProtected().getBytes(UTF_8)); }
}
