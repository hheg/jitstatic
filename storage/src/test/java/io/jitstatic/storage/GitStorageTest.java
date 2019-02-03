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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static io.jitstatic.JitStaticConstants.JITSTATIC_NOWHERE;
import static io.jitstatic.storage.tools.Utils.toByte;
import static io.jitstatic.storage.tools.Utils.toProvider;

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

import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectLoader;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spencerwi.either.Either;

import io.jitstatic.CommitMetaData;
import io.jitstatic.JitStaticConstants;
import io.jitstatic.MetaData;
import io.jitstatic.Role;
import io.jitstatic.auth.User;
import io.jitstatic.auth.UserData;
import io.jitstatic.hosted.FailedToLock;
import io.jitstatic.hosted.KeyAlreadyExist;
import io.jitstatic.hosted.RefLockHolder;
import io.jitstatic.hosted.StoreInfo;
import io.jitstatic.source.Source;
import io.jitstatic.source.SourceInfo;
import io.jitstatic.storage.tools.Utils;
import io.jitstatic.utils.Functions;
import io.jitstatic.utils.Functions.ThrowingSupplier;
import io.jitstatic.utils.Pair;
import io.jitstatic.utils.WrappingAPIException;

public class GitStorageTest {

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

    @AfterEach
    public void tearDown() throws IOException {
        Mockito.reset(source);
    }

    @Test
    public void testGetAKey() throws Exception {
        try (GitStorage gs = new GitStorage(source, null, hashService, "root"); InputStream mtest1 = getMetaData();) {
            SourceInfo si1 = mock(SourceInfo.class);
            when(si1.getSourceProvider()).thenReturn(toProvider(getByteArray(1)));
            when(si1.readMetaData()).thenCallRealMethod();
            when(si1.getMetadataInputStream()).thenReturn(mtest1);
            when(si1.getSourceVersion()).thenReturn(SHA_1);
            when(si1.getMetaDataVersion()).thenReturn(SHA_1_MD);
            when(source.getSourceInfo(eq("key"), anyString())).thenReturn(si1);

            Optional<StoreInfo> key = gs.getKey("key", null);
            assertNotNull(key.get());
            gs.checkHealth();
        }
    }

    @Test
    public void testGetARootKey() throws Exception {
        try (GitStorage gs = new GitStorage(source, null, hashService, "root")) {
            assertEquals(UnsupportedOperationException.class, assertThrows(WrappingAPIException.class, () -> gs.getKey("root/", null)).getCause().getClass());
            gs.checkHealth();
        }
    }

    @Test
    public void testPutARootKey() throws Exception {
        try (GitStorage gs = new GitStorage(source, null, hashService, "root"); InputStream mtest1 = getMetaData(); InputStream mtest2 = getMetaData()) {
            when(source.modifyMetadata(any(), anyString(), anyString(), anyString(), any()))
                    .thenReturn(SHA_2_MD);
            SourceInfo si = mock(SourceInfo.class);
            when(si.readMetaData()).thenCallRealMethod();
            when(si.getMetaDataVersion()).thenReturn(SHA_1_MD);
            when(si.getMetadataInputStream()).thenReturn(mtest1).thenReturn(mtest2);
            when(source.getSourceInfo(eq("root/"), anyString())).thenReturn(si);
            when(source.getSourceInfo(eq("root"), anyString())).thenReturn(null);
            assertEquals(UnsupportedOperationException.class, assertThrows(WrappingAPIException.class, () -> gs.getKey("root/", null)).getCause().getClass());
            assertTrue(gs.getMetaKey("root/", null).isPresent());

            MetaData sd = new MetaData(Set.of(new User("u", "p")), "text/plain", false, false, List.of(), null, null);
            Either<String, FailedToLock> putMetaData = gs.putMetaData("root/", null, sd, SHA_1_MD,
                    new CommitMetaData("user", "mail", "msg", "test", JITSTATIC_NOWHERE));
            assertTrue(putMetaData.isLeft());
            assertEquals(SHA_2_MD, putMetaData.getLeft());
            gs.checkHealth();
        }
    }

    @Test
    public void testInitGitStorageWithNullSource() {
        assertEquals("Source cannot be null", assertThrows(NullPointerException.class, () -> {
            try (GitStorage gs = new GitStorage(null, null, hashService, "root");) {
            }
        }).getLocalizedMessage());
    }

    @Test
    public void testLoadCache() throws Exception {
        Set<User> users = new HashSet<>();
        users.add(new User("user", "1234"));
        try (GitStorage gs = new GitStorage(source, null, hashService, "root");
                InputStream mtest1 = getMetaData();
                InputStream mtest2 = getMetaData();) {
            SourceInfo si1 = mock(SourceInfo.class);
            SourceInfo si2 = mock(SourceInfo.class);
            when(si1.getSourceProvider()).thenReturn(toProvider(getByteArray(1)));
            when(si1.readMetaData()).thenCallRealMethod();
            when(si1.getMetadataInputStream()).thenReturn(mtest1);
            when(si2.getSourceProvider()).thenReturn(toProvider(getByteArray(2)));
            when(si2.readMetaData()).thenCallRealMethod();
            when(si2.getMetadataInputStream()).thenReturn(mtest2);
            when(si1.getSourceVersion()).thenReturn(SHA_1);
            when(si2.getSourceVersion()).thenReturn(SHA_2);

            when(si1.getMetaDataVersion()).thenReturn(SHA_1_MD);
            when(si2.getMetaDataVersion()).thenReturn(SHA_2_MD);
            when(source.getSourceInfo(eq("key"), eq(REF_HEADS_MASTER))).thenReturn(si1).thenReturn(si2);

            gs.reload(REF_HEADS_MASTER);
            StoreInfo storage = new StoreInfo(toProvider(readData("{\"data\":\"value1\"}")), new MetaData(users, null, false, false, List.of(), null, null),
                    SHA_1,
                    SHA_1_MD);
            assertTrue(Arrays.equals(toByte(storage.getStreamProvider()), toByte(gs.getKey("key", null).get().getStreamProvider())));
            RefLockHolder refHolderLock = gs.getRefHolderLock(REF_HEADS_MASTER);
            refHolderLock.lockWriteAll(() -> {
                gs.reload(REF_HEADS_MASTER);
                return true;
            });

            storage = new StoreInfo(toProvider(readData("{\"data\":\"value2\"}")), new MetaData(users, null, false, false, List.of(), null, null), SHA_2,
                    SHA_2_MD);
            assertArrayEquals(toByte(storage.getStreamProvider()), toByte(gs.getKey("key", null).get().getStreamProvider()));
            gs.checkHealth();
        }
    }

    private byte[] readData(String content) {
        return content.getBytes(UTF_8);
    }

    @Test
    public void testLoadNewCache() throws Exception {

        try (GitStorage gs = new GitStorage(source, null, hashService, "root");
                InputStream mtest3 = getMetaData();
                InputStream mtest4 = getMetaData()) {
            SourceInfo si1 = mock(SourceInfo.class);
            SourceInfo si2 = mock(SourceInfo.class);
            when(si1.getSourceProvider()).thenReturn(toProvider(getByteArray(1)));
            when(si1.readMetaData()).thenCallRealMethod();
            when(si1.getMetadataInputStream()).thenReturn(mtest3);
            when(si1.getSourceVersion()).thenReturn(SHA_1);
            when(si2.getSourceProvider()).thenReturn(toProvider(getByteArray(2)));
            when(si2.readMetaData()).thenCallRealMethod();
            when(si2.getMetadataInputStream()).thenReturn(mtest4);
            when(si2.getSourceVersion()).thenReturn(SHA_2);
            when(si1.getMetaDataVersion()).thenReturn(SHA_1_MD);
            when(si2.getMetaDataVersion()).thenReturn(SHA_2_MD);

            when(source.getSourceInfo(eq("key3"), anyString())).thenReturn(si1);
            when(source.getSourceInfo(eq("key4"), anyString())).thenReturn(si2);
            Optional<StoreInfo> key3Data = gs.getKey("key3", null);
            Optional<StoreInfo> key4Data = gs.getKey("key4", null);
            assertNotNull(key3Data.get());
            assertNotNull(key4Data.get());
            gs.checkHealth();
        }
    }

    @Test
    public void testCheckHealth() throws Exception {
        NullPointerException npe = new NullPointerException("Test exception");
        when(source.getSourceInfo(anyString(), anyString())).thenThrow(npe);
        assertSame(assertThrows(NullPointerException.class, () -> {
            try (GitStorage gs = new GitStorage(source, null, hashService, "root");) {
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
        doThrow(cause).when(source).getSourceInfo(anyString(), anyString());
        try (GitStorage gs = new GitStorage(source, null, hashService, "root"); InputStream md = getMetaData()) {
            gs.reload(REF_HEADS_MASTER);
            assertFalse(gs.getKey("test3.json", null).isPresent());
            assertEquals(cause.getLocalizedMessage(), assertThrows(RuntimeException.class, () -> gs.checkHealth()).getLocalizedMessage());
            Mockito.reset(source);
            SourceInfo info = mock(SourceInfo.class);
            when(info.getSourceProvider()).thenReturn(toProvider(getByteArray(1)));
            when(info.readMetaData()).thenCallRealMethod();
            when(info.getSourceVersion()).thenReturn(SHA_1);
            when(info.getMetaDataVersion()).thenReturn(SHA_1_MD);
            when(info.getMetadataInputStream()).thenReturn(md);
            when(source.getSourceInfo(anyString(), anyString())).thenReturn(info);
            assertNotNull(gs.getKey("test3.json", null).get());
            gs.checkHealth();
        }
    }

    @Test
    public void testCheckHealthWithOldFault() throws Exception {
        RuntimeException cause = new RuntimeException("Fault reading something");
        doThrow(cause).when(source).getSourceInfo(anyString(), anyString());

        assertSame(cause, assertThrows(RuntimeException.class, () -> {
            try (GitStorage gs = new GitStorage(source, null, hashService, "root");) {
                gs.reload(REF_HEADS_MASTER);
                assertFalse(gs.getKey("key", null).isPresent());
                assertEquals(cause.getLocalizedMessage(), assertThrows(RuntimeException.class, () -> gs.checkHealth()).getLocalizedMessage());
                gs.getKey("key", null);
                gs.checkHealth();
            }
        }));
    }

    @Test
    public void testSourceCloseFailed() {
        doThrow(new RuntimeException()).when(source).close();
        try (GitStorage gs = new GitStorage(source, null, hashService, "root");) {
        }
    }

    @Test
    public void testRefIsFoundButKeyIsNot() throws Exception {

        try (GitStorage gs = new GitStorage(source, null, hashService, "root");
                InputStream mtest3 = getMetaData();
                InputStream mtest4 = getMetaData()) {
            SourceInfo si1 = mock(SourceInfo.class);
            SourceInfo si2 = mock(SourceInfo.class);
            when(si1.getSourceProvider()).thenReturn(toProvider(getByteArray(1)));
            when(si1.readMetaData()).thenCallRealMethod();
            when(si1.getMetadataInputStream()).thenReturn(mtest3);
            when(si1.getSourceVersion()).thenReturn(SHA_1);
            when(si2.getSourceProvider()).thenReturn(toProvider(getByteArray(2)));
            when(si2.readMetaData()).thenCallRealMethod();
            when(si2.getMetadataInputStream()).thenReturn(mtest4);
            when(si2.getSourceVersion()).thenReturn(SHA_2);
            when(si1.getMetaDataVersion()).thenReturn(SHA_1_MD);
            when(si2.getMetaDataVersion()).thenReturn(SHA_2_MD);

            when(source.getSourceInfo(eq("key3"), anyString())).thenReturn(si1);
            when(source.getSourceInfo(eq("key4"), anyString())).thenReturn(si2);
            Optional<StoreInfo> key3Data = gs.getKey("key3", null);
            assertNotNull(key3Data.get());
            Optional<StoreInfo> key4Data = gs.getKey("key4", null);
            assertNotNull(key4Data.get());
            key4Data = gs.getKey("key4", REF_HEADS_MASTER);
            assertNotNull(key4Data.get());
            gs.checkHealth();
        }

    }

    @Test
    public void testPutAKey() throws Exception {
        try (GitStorage gs = new GitStorage(source, null, hashService, "root"); InputStream mtest3 = getMetaData()) {
            SourceInfo si = mock(SourceInfo.class);
            byte[] data = readData("{\"one\" : \"two\"}");
            String key = "key3";
            when(si.getSourceProvider()).thenReturn(toProvider(getByteArray(1)));
            when(si.readMetaData()).thenCallRealMethod();
            when(si.getMetadataInputStream()).thenReturn(mtest3);
            when(si.getSourceVersion()).thenReturn(SHA_1);
            when(si.getMetaDataVersion()).thenReturn(SHA_1_MD);

            when(source.getSourceInfo(eq("key3"), anyString())).thenReturn(si);
            when(source.modifyKey(eq(key), any(), any(), eq(SHA_1), any())).thenReturn(Pair.of(SHA_2, factory));
            Optional<StoreInfo> first = gs.getKey(key, null);
            StoreInfo storeInfo = first.get();
            assertNotNull(storeInfo);
            assertNotEquals(data, toByte(storeInfo.getStreamProvider()));
            Either<String, FailedToLock> put = gs.put(key, null, Utils.toProvider(data), SHA_1,
                    new CommitMetaData("user", "mail", "msg", "Test", JITSTATIC_NOWHERE));
            String newVersion = put.getLeft();
            assertEquals(SHA_2, newVersion);
            first = gs.getKey(key, null);
            storeInfo = first.get();
            assertNotNull(storeInfo);
            assertArrayEquals(data, toByte(storeInfo.getStreamProvider()));
            gs.checkHealth();
        }
    }

    @Test
    public void testPutAOnANonWritableKey() throws Throwable {
        assertThat((UnsupportedOperationException) assertThrows(WrappingAPIException.class, () -> {
            GitStorage gs = new GitStorage(source, null, hashService, "root");
            try (gs; InputStream mtest3 = getMetaDataProtectedInputStream()) {
                SourceInfo si = mock(SourceInfo.class);
                byte[] data = readData("{\"one\" : \"two\"}");
                String key = "key3";
                when(si.getSourceProvider()).thenReturn(toProvider(data));
                when(si.readMetaData()).thenCallRealMethod();
                when(si.getMetadataInputStream()).thenReturn(mtest3);
                when(si.getSourceVersion()).thenReturn(SHA_1);
                when(si.getMetaDataVersion()).thenReturn(SHA_1_MD);

                when(source.getSourceInfo(eq(key), anyString())).thenReturn(si);
                when(source.modifyKey(eq(key), any(), any(), eq(SHA_1), any())).thenReturn(Pair.of(SHA_2, factory));
                Optional<StoreInfo> first = gs.getKey(key, null);
                StoreInfo storeInfo = first.get();
                assertNotNull(storeInfo);
                assertNotEquals(data, toByte(storeInfo.getStreamProvider()));
                gs.put(key, null, Utils.toProvider(data), SHA_1, new CommitMetaData("user", "mail", "msg", "Test", JITSTATIC_NOWHERE));
            }
            gs.checkHealth();
        }).getCause(), Matchers.isA(UnsupportedOperationException.class));
    }

    @Test
    public void testGetADotKey() throws Exception {
        try (GitStorage gs = new GitStorage(source, null, hashService, "root")) {
            String key = ".key3";
            Optional<StoreInfo> first = gs.getKey(key, null);
            assertFalse(first.isPresent());
            gs.checkHealth();
        }
    }

    @Test
    public void testGetATrainDotKey() throws Exception {
        try (GitStorage gs = new GitStorage(source, null, hashService, "root")) {
            String key = "key/key/.key3";
            Optional<StoreInfo> first = gs.getKey(key, null);
            assertFalse(first.isPresent());
            gs.checkHealth();
        }
    }

    @Test
    public void testGetAHiddenFile() throws Exception {
        SourceInfo si = mock(SourceInfo.class);
        when(si.getSourceProvider()).thenReturn(toProvider(getByteArray(1)));
        when(si.readMetaData()).thenCallRealMethod();
        when(si.getMetadataInputStream()).thenReturn(getMetaDataHiddenInputStream());
        when(source.getSourceInfo("key", REF_HEADS_MASTER)).thenReturn(si);
        try (GitStorage gs = new GitStorage(source, null, hashService, "root")) {
            Optional<StoreInfo> key = gs.getKey("key", null);
            assertFalse(key.isPresent());
            gs.checkHealth();
        }
    }

    @Test
    public void testPutKeyWithEmptyMessage() throws Exception {
        try (GitStorage gs = new GitStorage(source, null, hashService, "root");) {
            assertThrows(IllegalArgumentException.class, () -> {
                byte[] data = readData("{\"one\" : \"two\"}");
                String key = "key3";
                gs.put(key, null, Utils.toProvider(data), SHA_1, new CommitMetaData("user", "mail", "", "Test", JITSTATIC_NOWHERE));
            });
            gs.checkHealth();
        }
    }

    @Test
    public void testPutKeyWithNoRef() {
        assertThat((RefNotFoundException) assertThrows(WrappingAPIException.class, () -> {
            try (GitStorage gs = new GitStorage(source, null, hashService, "root");) {
                byte[] data = readData("{\"one\" : \"two\"}");
                String key = "key3";
                gs.checkHealth();
                gs.put(key, null, Utils.toProvider(data), SHA_1, new CommitMetaData("user", "mail", "msg", "Test", JITSTATIC_NOWHERE));
            }
        }).getCause(), Matchers.isA(RefNotFoundException.class));
    }

    @Test
    public void testPutKeyWithNoKey() throws Throwable {
        assertThat((UnsupportedOperationException) assertThrows(WrappingAPIException.class, () -> {
            try (GitStorage gs = new GitStorage(source, null, hashService, "root"); InputStream mtest3 = getMetaData()) {
                SourceInfo si = mock(SourceInfo.class);
                byte[] data = readData("{\"one\" : \"two\"}");
                String key = "key3";
                when(si.getSourceProvider()).thenReturn(toProvider(getByteArray(1)));
                when(si.readMetaData()).thenCallRealMethod();
                when(si.getMetadataInputStream()).thenReturn(mtest3);
                when(si.getSourceVersion()).thenReturn(SHA_1);
                when(si.getMetaDataVersion()).thenReturn(SHA_1_MD);

                when(source.getSourceInfo(eq("key3"), anyString())).thenReturn(si);
                when(source.modifyKey(eq(key), any(), any(), eq(SHA_1), any())).thenReturn(Pair.of(SHA_2, factory));
                Optional<StoreInfo> first = gs.getKey(key, null);
                StoreInfo storeInfo = first.get();
                assertNotNull(storeInfo);
                assertNotEquals(data, toByte(storeInfo.getStreamProvider()));
                gs.checkHealth();
                gs.put("other", null, Utils.toProvider(data), SHA_1, new CommitMetaData("user", "mail", "msg", "Test", JITSTATIC_NOWHERE));
            }
        }).getCause(), Matchers.isA(UnsupportedOperationException.class));
    }

    @Test
    public void testAddKey() throws Exception {
        when(source.addKey(any(), any(), any(), any(), any())).thenReturn(Pair.of(Pair.of(factory, "1"), "1"));
        try (GitStorage gs = new GitStorage(source, null, hashService, "root")) {
            byte[] data = getByteArray(1);
            byte[] pretty = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(MAPPER.readTree(data));
            String si = gs.addKey("somekey", "refs/heads/master", Utils.toProvider(pretty),
                    new MetaData(new HashSet<>(), null, false, false, List.of(), null, null),
                    new CommitMetaData("user", "mail", "msg", "Test", JITSTATIC_NOWHERE));
            assertEquals("1", si);
            gs.checkHealth();
        }
    }

    @Test
    public void testPutMetaDataKey() throws Exception {
        try (GitStorage gs = new GitStorage(source, null, hashService, "root"); InputStream mtest3 = getMetaData()) {
            SourceInfo si = mock(SourceInfo.class);
            String key = "key3";
            when(si.getSourceProvider()).thenReturn(toProvider(getByteArray(1)));
            when(si.readMetaData()).thenCallRealMethod();
            when(si.getMetadataInputStream()).thenReturn(mtest3);
            when(si.getSourceVersion()).thenReturn(SHA_1);
            when(si.getMetaDataVersion()).thenReturn(SHA_1_MD);

            when(source.getSourceInfo(eq(key), anyString())).thenReturn(si);
            when(source.modifyMetadata(any(), eq(SHA_1_MD), any(), any(), any())).thenReturn((SHA_2_MD));
            Optional<StoreInfo> first = gs.getKey(key, null);
            StoreInfo storeInfo = first.get();
            assertNotNull(storeInfo);

            MetaData sd = new MetaData(storeInfo.getMetaData().getUsers(), "application/test", false, false, List.of(), null, null);
            Either<String, FailedToLock> put = gs.putMetaData(key, null, sd, storeInfo.getMetaDataVersion(),
                    new CommitMetaData("user", "mail", "msg", "Test", JITSTATIC_NOWHERE));
            String newVersion = put.getLeft();
            assertEquals(SHA_2_MD, newVersion);
            first = gs.getKey(key, null);
            storeInfo = first.get();
            assertNotNull(storeInfo);
            assertEquals(sd, storeInfo.getMetaData());
            gs.checkHealth();
        }
    }

    @Test
    public void testDelete() throws Exception {
        try (GitStorage gs = new GitStorage(source, null, hashService, "root"); InputStream mtest3 = getMetaData()) {
            SourceInfo si = mock(SourceInfo.class);

            String key = "key3";
            when(si.getSourceProvider()).thenReturn(toProvider(getByteArray(1)));
            when(si.readMetaData()).thenCallRealMethod();
            when(si.getMetadataInputStream()).thenReturn(mtest3);
            when(si.getSourceVersion()).thenReturn(SHA_1);
            when(si.getMetaDataVersion()).thenReturn(SHA_1_MD);

            when(source.getSourceInfo(eq(key), anyString())).thenReturn(si);
            StoreInfo key2 = gs.getKey(key, null).get();
            assertNotNull(key2);
            gs.delete(key, null, new CommitMetaData("user", "mail", "msg", "Test", JITSTATIC_NOWHERE));
            Thread.sleep(1000);
            gs.checkHealth();
            Mockito.verify(source).deleteKey(eq(key), eq(REF_HEADS_MASTER), any());
        }
    }

    @Test
    public void testDeleteMetaKey() throws IOException {
        try (GitStorage gs = new GitStorage(source, null, hashService, "root")) {
            assertSame(UnsupportedOperationException.class,
                    assertThrows(WrappingAPIException.class,
                            () -> gs.delete("key/", null, new CommitMetaData("user", "mail", "msg", "Test", JITSTATIC_NOWHERE))).getCause().getClass());
        }
    }

    @Test
    public void testAddkeyWithNewBranch() throws Exception {
        String key = "somekey";
        String branch = "refs/heads/newbranch";
        when(source.getSourceInfo(eq(key), eq(branch))).thenThrow(RefNotFoundException.class);
        when(source.addKey(eq(key), eq(branch), any(), any(), any()))
                .thenReturn(Pair.of(Pair.of(factory, "1"), "1"));
        try (GitStorage gs = new GitStorage(source, null, hashService, "root")) {
            byte[] data = getByteArray(1);
            byte[] pretty = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(MAPPER.readTree(data));
            String si = gs.addKey(key, branch, Utils.toProvider(pretty), new MetaData(new HashSet<>(), null, false, false, List.of(), null, null),
                    new CommitMetaData("user", "mail", "msg", "Test", JITSTATIC_NOWHERE));
            assertEquals("1", si);
            gs.checkHealth();
            ArgumentCaptor<String> argument = ArgumentCaptor.forClass(String.class);
            Mockito.verify(source).createRef(argument.capture());
            assertEquals(branch, argument.getValue());
        }
    }

    @Test
    public void testAddKeyWithExistingKey() throws Exception {
        String key = "somekey";
        String branch = "refs/heads/newbranch";
        when(source.getSourceInfo(eq(key), eq(branch))).thenReturn(mock(SourceInfo.class));
        try (GitStorage gs = new GitStorage(source, null, hashService, "root")) {
            byte[] data = getByteArray(1);
            byte[] pretty = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(MAPPER.readTree(data));
            assertSame(KeyAlreadyExist.class,
                    assertThrows(WrappingAPIException.class, () -> gs.addKey(key, branch, Utils.toProvider(pretty),
                            new MetaData(new HashSet<>(), null, false, false, List.of(), null, null),
                            new CommitMetaData("user", "mail", "msg", "Test", JITSTATIC_NOWHERE))).getCause()
                                    .getClass());
        }
    }

    @Test
    public void testGetListForRef() throws RefNotFoundException, IOException {
        try (GitStorage gs = new GitStorage(source, null, hashService, "root"); InputStream mtest3 = getMetaData()) {
            SourceInfo si = mock(SourceInfo.class);
            when(si.getSourceProvider()).thenReturn(toProvider(getByteArray(1)));
            when(si.readMetaData()).thenCallRealMethod();
            when(si.getMetadataInputStream()).thenReturn(mtest3);
            when(si.getSourceVersion()).thenReturn(SHA_1);
            when(si.getMetaDataVersion()).thenReturn(SHA_1_MD);
            String key = "dir/";
            String dirkey = "dir/key";
            when(source.getSourceInfo(eq(dirkey), anyString())).thenReturn(si);

            when(source.getList(eq(key), anyString(), Mockito.anyBoolean())).thenReturn(List.of(dirkey));
            List<Pair<String, Boolean>> keys = List.of(Pair.of(key, false));
            List<Pair<String, StoreInfo>> list = gs.getListForRef(keys, REF_HEADS_MASTER);
            assertEquals(1, list.size());
            list = gs.getListForRef(keys, REF_HEADS_MASTER);
            assertEquals(1, list.size());
        }
    }

    @Test
    public void testGetList() throws Exception {
        try (GitStorage gs = new GitStorage(source, null, hashService, "root"); InputStream mtest3 = getMetaData()) {
            SourceInfo si = mock(SourceInfo.class);
            when(si.getSourceProvider()).thenReturn(toProvider(getByteArray(1)));
            when(si.readMetaData()).thenCallRealMethod();
            when(si.getMetadataInputStream()).thenReturn(mtest3);
            when(si.getSourceVersion()).thenReturn(SHA_1);
            when(si.getMetaDataVersion()).thenReturn(SHA_1_MD);
            String key = "dir/";
            String dirkey = "dir/key";
            when(source.getSourceInfo(eq(dirkey), anyString())).thenReturn(si);

            when(source.getList(eq(key), anyString(), Mockito.anyBoolean())).thenReturn(List.of(dirkey));
            List<Pair<List<Pair<String, Boolean>>, String>> keys = List.of(Pair.of(List.of(Pair.of(key, false)), REF_HEADS_MASTER));
            List<Pair<List<Pair<String, StoreInfo>>, String>> list = gs.getList(keys);
            assertEquals(1, list.size());
            Pair<List<Pair<String, StoreInfo>>, String> masterResult = list.get(0);
            assertEquals(REF_HEADS_MASTER, masterResult.getRight());
            assertFalse(masterResult.getLeft().isEmpty());
            list = gs.getList(keys);
            assertEquals(1, list.size());
            masterResult = list.get(0);
            assertEquals(REF_HEADS_MASTER, masterResult.getRight());
            assertEquals(dirkey, masterResult.getLeft().get(0).getLeft());
        }
    }

    @Test
    public void testAddDotFile() {
        try (GitStorage gs = new GitStorage(source, null, hashService, "root")) {
            assertThrows(WrappingAPIException.class,
                    () -> gs.addKey("dot/.dot", null, Utils.toProvider(new byte[] { 1 }), new MetaData(Set.of()),
                            new CommitMetaData("d", "d", "d", "Test", JITSTATIC_NOWHERE)));
        }
    }

    @Test
    public void testGetUser() throws RefNotFoundException, IOException {
        when(source.getUser(anyString(), anyString())).thenReturn(Pair.of("1", new UserData(Set.of(new Role("role")), "1234", null, null)));
        try (GitStorage gs = new GitStorage(source, null, hashService, "root")) {
            assertNotNull(gs.getUser("name", "refs/heads/secret", JitStaticConstants.GIT_REALM));
        }
    }

    @Test
    public void testGetListForNoKey() throws RefNotFoundException {
        when(source.getSourceInfo(eq("key"), eq("refs/heads/master"))).thenReturn(null);
        try (GitStorage gs = new GitStorage(source, null, hashService, "root")) {
            gs.getListForRef(List.of(Pair.of("key", false)), "refs/heads/master");
        }
    }

    @Test
    public void testGetListNoRef() throws RefNotFoundException, IOException {
        when(source.getList(eq("key/"), eq("refs/heads/master"), Mockito.anyBoolean())).thenThrow(new RefNotFoundException("test"));
        try (GitStorage gs = new GitStorage(source, null, hashService, "root")) {
            List<Pair<String, StoreInfo>> listForRef = gs.getListForRef(List.of(Pair.of("key/", false)), "refs/heads/master");
            assertTrue(listForRef.isEmpty());
        }
    }

    @Test
    public void testGetListIOException() throws RefNotFoundException, IOException {
        when(source.getList(eq("key/"), eq("refs/heads/master"), Mockito.anyBoolean())).thenThrow(new IOException("test"));
        try (GitStorage gs = new GitStorage(source, null, hashService, "root")) {
            List<Pair<String, StoreInfo>> listForRef = gs.getListForRef(List.of(Pair.of("key/", false)), "refs/heads/master");
            assertTrue(listForRef.isEmpty());
        }
    }

    @Test
    public void testGetListForAKey() throws RefNotFoundException, IOException {
        try (GitStorage gs = new GitStorage(source, null, hashService, "root"); InputStream mtest3 = getMetaData()) {
            SourceInfo si = mock(SourceInfo.class);
            when(si.getMetadataInputStream()).thenReturn(mtest3);
            when(si.getSourceVersion()).thenReturn(SHA_1);
            when(si.getMetaDataVersion()).thenReturn(SHA_1_MD);
            when(source.getSourceInfo(eq("key"), eq("refs/heads/master"))).thenReturn(si);
            gs.getListForRef(List.of(Pair.of("key", false)), "refs/heads/master");
        }
    }

    @Test
    public void testGetUserDataNoBranch() throws RefNotFoundException, IOException {
        when(source.getUser(eq(".users/git/kit"), eq(REF_HEADS_MASTER))).thenThrow(new RefNotFoundException("Test"));
        try (GitStorage gs = new GitStorage(source, null, hashService, "root")) {
            assertThrows(RefNotFoundException.class, () -> gs.getUserData("kit", null, JitStaticConstants.GIT_REALM));
            Mockito.verify(source).getUser(".users/git/kit", REF_HEADS_MASTER);
        }
    }

    @Test
    public void testGetUserDataIOError() throws RefNotFoundException, IOException {
        when(source.getUser(eq(".users/git/kit"), eq(REF_HEADS_MASTER))).thenThrow(new IOException("Test"));
        try (GitStorage gs = new GitStorage(source, null, hashService, "root")) {
            assertThrows(UncheckedIOException.class, () -> gs.getUserData("kit", null, JitStaticConstants.GIT_REALM));
            Mockito.verify(source).getUser(".users/git/kit", REF_HEADS_MASTER);
        }
    }

    @Test
    public void testUpdateUserNoRef() {
        try (GitStorage gs = new GitStorage(source, null, hashService, "root")) {
            assertThrows(UnsupportedOperationException.class,
                    () -> gs.updateUser("kit", null, JitStaticConstants.GIT_REALM, "updater", new UserData(Set.of(new Role("role")), "p", null, null), "1"));
        }
    }

    @Test
    public void testUpdateUser() throws RefNotFoundException, IOException {
        when(source.addUser(anyString(), anyString(), anyString(), any())).thenReturn("1");
        when(source.updateUser(anyString(), anyString(), anyString(), any())).thenReturn("2");
        try (GitStorage gs = new GitStorage(source, null, hashService, "root")) {
            gs.addUser("kit", null, JitStaticConstants.GIT_REALM, "creator", new UserData(Set.of(new Role("role")), "pa", null, null));
            assertEquals("2", gs.updateUser("kit", null, JitStaticConstants.GIT_REALM, "updater", new UserData(Set.of(new Role("role")), "pb", null, null), "1")
                    .getLeft());
        }
    }

    @Test
    public void testUpdateUserNoKey() throws RefNotFoundException, IOException {
        when(source.addUser(anyString(), anyString(), anyString(), any())).thenReturn("1");
        when(source.updateUser(anyString(), anyString(), anyString(), any())).thenReturn("2");
        try (GitStorage gs = new GitStorage(source, null, hashService, "root")) {
            gs.addUser("kit", null, JitStaticConstants.GIT_REALM, "creator", new UserData(Set.of(new Role("role")), "pa", null, null));
            assertEquals("2", gs.updateUser("kit", null, JitStaticConstants.GIT_REALM, "updater", new UserData(Set.of(new Role("role")), "pb", null, null), "1")
                    .getLeft());
        }
    }

    @Test
    public void testDeleteUser() throws RefNotFoundException, IOException {
        when(source.addUser(anyString(), anyString(), anyString(), any())).thenReturn("1");
        try (GitStorage gs = new GitStorage(source, null, hashService, "root")) {
            gs.addUser("kit", null, JitStaticConstants.GIT_REALM, "creator", new UserData(Set.of(new Role("role")), "pa", null, null));
            gs.deleteUser("kit", null, JitStaticConstants.GIT_REALM, "creator");
        }
    }

    @Test
    public void testAddUserRefNotFound() throws RefNotFoundException, IOException {
        when(source.addUser(anyString(), anyString(), anyString(), any())).thenThrow(RefNotFoundException.class);
        try (GitStorage gs = new GitStorage(source, null, hashService, "root")) {
            assertEquals(UnsupportedOperationException.class,
                    assertThrows(WrappingAPIException.class,
                            () -> gs.addUser("kit", null, JitStaticConstants.GIT_REALM, "creator", new UserData(Set.of(new Role("role")), "pa", null, null)))
                                    .getCause()
                                    .getClass());
        }
    }

    @Test
    public void testAddUserReadError() throws RefNotFoundException, IOException {
        when(source.addUser(anyString(), anyString(), anyString(), any())).thenThrow(IOException.class);
        try (GitStorage gs = new GitStorage(source, null, hashService, "root")) {
            assertEquals(IOException.class,
                    assertThrows(UncheckedIOException.class,
                            () -> gs.addUser("kit", null, JitStaticConstants.GIT_REALM, "creator", new UserData(Set.of(new Role("role")), "pa", null, null)))
                                    .getCause()
                                    .getClass());
        }
    }

    @Test
    public void testAddUserMatchingRoot() {
        try (GitStorage gs = new GitStorage(source, null, hashService, "root")) {
            assertEquals("io.jitstatic.hosted.KeyAlreadyExist: Key 'root' already exist in branch refs/heads/master", assertThrows(WrappingAPIException.class,
                    () -> gs.addUser("root", null, JitStaticConstants.GIT_REALM, "creator", new UserData(Set.of(new Role("role")), "pa", null, null)))
                            .getMessage());
        }
    }

    @Test
    public void testPutMetaDataDotKey() {
        MetaData md = mock(MetaData.class);
        CommitMetaData cmd = mock(CommitMetaData.class);
        try (GitStorage gs = new GitStorage(source, null, hashService, "root")) {
            assertEquals(UnsupportedOperationException.class,
                    assertThrows(WrappingAPIException.class, () -> gs.putMetaData(".key", null, md, "1", cmd)).getCause().getClass());
        }
    }
    
    @Test
    public void testPutMetaDataWithNoRef() {
        MetaData md = mock(MetaData.class);
        CommitMetaData cmd = mock(CommitMetaData.class);
        try (GitStorage gs = new GitStorage(source, null, hashService, "root")) {
            assertEquals(RefNotFoundException.class,
                    assertThrows(WrappingAPIException.class, () -> gs.putMetaData("key", null, md, "1", cmd)).getCause().getClass());
        }
    }
    
    @Test
    public void testDeleteFromTag() {
        CommitMetaData cmd = mock(CommitMetaData.class);
        try (GitStorage gs = new GitStorage(source, null, hashService, "root")) {
            assertEquals(UnsupportedOperationException.class,
                    assertThrows(WrappingAPIException.class, () -> gs.delete("key", "refs/tags/blah", cmd)).getCause().getClass());
        }
    }

    private byte[] getByteArray(int c) {
        return ("{\"data\":\"value" + c + "\"}").getBytes(UTF_8);
    }

    private InputStream getMetaData() {
        return new ByteArrayInputStream("{\"users\": [{\"user\": \"user\",\"password\": \"1234\"}]}".getBytes(UTF_8));
    }

    private InputStream getMetaDataHiddenInputStream() {
        return new ByteArrayInputStream("{\"users\": [{\"user\": \"user\",\"password\": \"1234\"}],\"hidden\":true}".getBytes(UTF_8));
    }

    private InputStream getMetaDataProtectedInputStream() {
        return new ByteArrayInputStream("{\"users\": [{\"user\": \"user\",\"password\": \"1234\"}],\"protected\":true}".getBytes(UTF_8));
    }
}
