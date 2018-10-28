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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.lib.Constants;
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
import io.jitstatic.hosted.RefHolderLock;
import io.jitstatic.hosted.StoreInfo;
import io.jitstatic.source.Source;
import io.jitstatic.source.SourceInfo;
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
    public Source source = mock(Source.class);

    @AfterEach
    public void tearDown() throws IOException {
        Mockito.reset(source);
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

            Optional<StoreInfo> key = gs.getKey("key", null);
            assertNotNull(key.get());
            gs.checkHealth();
        }
    }

    @Test
    public void testGetARootKey() throws Exception {
        try (GitStorage gs = new GitStorage(source, null)) {
            assertEquals(UnsupportedOperationException.class, assertThrows(WrappingAPIException.class, () -> gs.getKey("root/", null)).getCause().getClass());
            gs.checkHealth();
        }
    }

    @Test
    public void testPutARootKey() throws Exception {
        try (GitStorage gs = new GitStorage(source, null); InputStream mtest1 = getMetaData(); InputStream mtest2 = getMetaData()) {
            Mockito.when(source.modifyMetadata(Mockito.any(), Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                    .thenReturn(SHA_2_MD);
            SourceInfo si = Mockito.mock(SourceInfo.class);
            Mockito.when(si.getMetaDataVersion()).thenReturn(SHA_1_MD);
            Mockito.when(si.getMetadataInputStream()).thenReturn(mtest1).thenReturn(mtest2);
            Mockito.when(source.getSourceInfo(Mockito.eq("root/"), Mockito.anyString())).thenReturn(si);
            Mockito.when(source.getSourceInfo(Mockito.eq("root"), Mockito.anyString())).thenReturn(null);
            assertEquals(UnsupportedOperationException.class, assertThrows(WrappingAPIException.class, () -> gs.getKey("root/", null)).getCause().getClass());
            assertTrue(gs.getMetaKey("root/", null).isPresent());

            MetaData sd = new MetaData(Set.of(new User("u", "p")), "text/plain", false, false, List.of(), null, null);
            Either<String, FailedToLock> putMetaData = gs.putMetaData("root/", null, sd, SHA_1_MD, new CommitMetaData("user", "mail", "msg"));
            assertTrue(putMetaData.isLeft());
            assertEquals(SHA_2_MD, putMetaData.getLeft());
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

            gs.reload(List.of(REF_HEADS_MASTER));
            StoreInfo storage = new StoreInfo(readData("{\"data\":\"value1\"}"), new MetaData(users, null, false, false, List.of(), null, null), SHA_1,
                    SHA_1_MD);
            assertTrue(Arrays.equals(storage.getData(), gs.getKey("key", null).get().getData()));
            RefHolderLock refHolderLock = gs.getRefHolderLock(REF_HEADS_MASTER);
            refHolderLock.lockWriteAll(() -> {
                gs.reload(List.of(REF_HEADS_MASTER));
                return true;
            });

            storage = new StoreInfo(readData("{\"data\":\"value2\"}"), new MetaData(users, null, false, false, List.of(), null, null), SHA_2, SHA_2_MD);
            assertArrayEquals(storage.getData(), gs.getKey("key", null).get().getData());
            gs.checkHealth();
        }
    }

    private byte[] readData(String content) {
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

        try (GitStorage gs = new GitStorage(source, null); InputStream is = getInputStream(0); InputStream md = getMetaData()) {

            gs.reload(List.of(REF_HEADS_MASTER));
            assertFalse(gs.getKey("test3.json", null).isPresent());
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

        assertSame(cause, assertThrows(RuntimeException.class, () -> {
            try (GitStorage gs = new GitStorage(source, null);) {
                gs.reload(List.of(REF_HEADS_MASTER));
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
        try (GitStorage gs = new GitStorage(source, null); InputStream test3 = getInputStream(1); InputStream mtest3 = getMetaData()) {
            SourceInfo si = mock(SourceInfo.class);
            byte[] data = readData("{\"one\" : \"two\"}");
            String key = "key3";
            when(si.getSourceInputStream()).thenReturn(test3);
            when(si.getMetadataInputStream()).thenReturn(mtest3);
            when(si.getSourceVersion()).thenReturn(SHA_1);
            when(si.getMetaDataVersion()).thenReturn(SHA_1_MD);

            when(source.getSourceInfo(Mockito.eq("key3"), Mockito.anyString())).thenReturn(si);
            when(source.modifyKey(Mockito.eq(key), Mockito.any(), Mockito.any(), Mockito.eq(SHA_1), Mockito.any())).thenReturn((SHA_2));
            Optional<StoreInfo> first = gs.getKey(key, null);
            StoreInfo storeInfo = first.get();
            assertNotNull(storeInfo);
            assertNotEquals(data, storeInfo.getData());
            Either<String, FailedToLock> put = gs.put(key, null, data, SHA_1, new CommitMetaData("user", "mail", "msg"));
            String newVersion = put.getLeft();
            assertEquals(SHA_2, newVersion);
            first = gs.getKey(key, null);
            storeInfo = first.get();
            assertNotNull(storeInfo);
            assertArrayEquals(data, storeInfo.getData());
            gs.checkHealth();
        }
    }

    @Test
    public void testPutAOnANonWritableKey() throws Throwable {
        assertThat((UnsupportedOperationException) assertThrows(WrappingAPIException.class, () -> {
            try (GitStorage gs = new GitStorage(source, null); InputStream test3 = getInputStream(1); InputStream mtest3 = getMetaDataProtectedInputStream()) {
                SourceInfo si = mock(SourceInfo.class);
                byte[] data = readData("{\"one\" : \"two\"}");
                String key = "key3";
                when(si.getSourceInputStream()).thenReturn(test3);
                when(si.getMetadataInputStream()).thenReturn(mtest3);
                when(si.getSourceVersion()).thenReturn(SHA_1);
                when(si.getMetaDataVersion()).thenReturn(SHA_1_MD);

                when(source.getSourceInfo(Mockito.eq("key3"), Mockito.anyString())).thenReturn(si);
                when(source.modifyKey(Mockito.eq(key), Mockito.any(), Mockito.any(), Mockito.eq(SHA_1), Mockito.any())).thenReturn((SHA_2));
                Optional<StoreInfo> first = gs.getKey(key, null);
                StoreInfo storeInfo = first.get();
                assertNotNull(storeInfo);
                assertNotEquals(data, storeInfo.getData());
                gs.put(key, null, data, SHA_1, new CommitMetaData("user", "mail", "msg"));
            }
        }).getCause(), Matchers.isA(UnsupportedOperationException.class));
    }

    @Test
    public void testGetADotKey() throws Exception {
        try (GitStorage gs = new GitStorage(source, null)) {
            String key = ".key3";
            Optional<StoreInfo> first = gs.getKey(key, null);
            assertFalse(first.isPresent());
            gs.checkHealth();
        }
    }

    @Test
    public void testGetATrainDotKey() throws Exception {
        try (GitStorage gs = new GitStorage(source, null)) {
            String key = "key/key/.key3";
            Optional<StoreInfo> first = gs.getKey(key, null);
            assertFalse(first.isPresent());
            gs.checkHealth();
        }
    }

    @Test
    public void testGetAHiddenFile() throws Exception {
        SourceInfo si = mock(SourceInfo.class);
        when(si.getMetadataInputStream()).thenReturn(getMetaDataHiddenInputStream());
        when(source.getSourceInfo("key", REF_HEADS_MASTER)).thenReturn(si);
        try (GitStorage gs = new GitStorage(source, null)) {
            Optional<StoreInfo> key = gs.getKey("key", null);
            assertFalse(key.isPresent());
            gs.checkHealth();
        }
    }

    @Test
    public void testPutKeyWithEmptyMessage() throws Exception {
        try (GitStorage gs = new GitStorage(source, null);) {
            assertThrows(IllegalArgumentException.class, () -> {
                byte[] data = readData("{\"one\" : \"two\"}");
                String key = "key3";
                gs.put(key, null, data, SHA_1, new CommitMetaData("user", "mail", ""));
            });
            gs.checkHealth();
        }
    }

    @Test
    public void testPutKeyWithNoRef() {
        assertThat((RefNotFoundException) assertThrows(WrappingAPIException.class, () -> {
            try (GitStorage gs = new GitStorage(source, null);) {
                byte[] data = readData("{\"one\" : \"two\"}");
                String key = "key3";
                gs.checkHealth();
                gs.put(key, null, data, SHA_1, new CommitMetaData("user", "mail", "msg"));
            }
        }).getCause(), Matchers.isA(RefNotFoundException.class));
    }

    @Test
    public void testPutKeyWithNoKey() throws Throwable {
        assertThat((UnsupportedOperationException) assertThrows(WrappingAPIException.class, () -> {
            try (GitStorage gs = new GitStorage(source, null); InputStream test3 = getInputStream(1); InputStream mtest3 = getMetaData()) {
                SourceInfo si = mock(SourceInfo.class);
                byte[] data = readData("{\"one\" : \"two\"}");
                String key = "key3";
                when(si.getSourceInputStream()).thenReturn(test3);
                when(si.getMetadataInputStream()).thenReturn(mtest3);
                when(si.getSourceVersion()).thenReturn(SHA_1);
                when(si.getMetaDataVersion()).thenReturn(SHA_1_MD);

                when(source.getSourceInfo(Mockito.eq("key3"), Mockito.anyString())).thenReturn(si);
                when(source.modifyKey(Mockito.eq(key), Mockito.any(), Mockito.eq(data), Mockito.eq(SHA_1), Mockito.any())).thenReturn((SHA_2));
                Optional<StoreInfo> first = gs.getKey(key, null);
                StoreInfo storeInfo = first.get();
                assertNotNull(storeInfo);
                assertNotEquals(data, storeInfo.getData());
                gs.checkHealth();
                gs.put("other", null, data, SHA_1, new CommitMetaData("user", "mail", "msg"));
            }
        }).getCause(), Matchers.isA(UnsupportedOperationException.class));
    }

    @Test
    public void testAddKey() throws Exception {
        when(source.addKey(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any())).thenReturn((Pair.of("1", "1")));
        try (GitStorage gs = new GitStorage(source, null)) {
            byte[] data = getByteArray(1);
            byte[] pretty = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(MAPPER.readTree(data));
            String si = gs.addKey("somekey", "refs/heads/master", pretty, new MetaData(new HashSet<>(), null, false, false, List.of(), null, null),
                    new CommitMetaData("user", "mail", "msg"));
            assertEquals("1", si);
            gs.checkHealth();
        }
    }

    @Test
    public void testPutMetaDataKey() throws Exception {
        try (GitStorage gs = new GitStorage(source, null); InputStream test3 = getInputStream(1); InputStream mtest3 = getMetaData()) {
            SourceInfo si = mock(SourceInfo.class);
            String key = "key3";
            when(si.getSourceInputStream()).thenReturn(test3);
            when(si.getMetadataInputStream()).thenReturn(mtest3);
            when(si.getSourceVersion()).thenReturn(SHA_1);
            when(si.getMetaDataVersion()).thenReturn(SHA_1_MD);

            when(source.getSourceInfo(Mockito.eq("key3"), Mockito.anyString())).thenReturn(si);
            when(source.modifyMetadata(Mockito.<MetaData>any(), Mockito.eq(SHA_1_MD), Mockito.any(), Mockito.any(), Mockito.any())).thenReturn((SHA_2_MD));
            Optional<StoreInfo> first = gs.getKey(key, null);
            StoreInfo storeInfo = first.get();
            assertNotNull(storeInfo);

            MetaData sd = new MetaData(storeInfo.getStorageData().getUsers(), "application/test", false, false, List.of(), null, null);
            Either<String, FailedToLock> put = gs.putMetaData(key, null, sd, storeInfo.getMetaDataVersion(), new CommitMetaData("user", "mail", "msg"));
            String newVersion = put.getLeft();
            assertEquals(SHA_2_MD, newVersion);
            first = gs.getKey(key, null);
            storeInfo = first.get();
            assertNotNull(storeInfo);
            assertEquals(sd, storeInfo.getStorageData());
            gs.checkHealth();
        }
    }

    @Test
    public void testDelete() throws Exception {
        try (GitStorage gs = new GitStorage(source, null); InputStream test3 = getInputStream(1); InputStream mtest3 = getMetaData()) {
            SourceInfo si = mock(SourceInfo.class);

            String key = "key3";
            when(si.getSourceInputStream()).thenReturn(test3);
            when(si.getMetadataInputStream()).thenReturn(mtest3);
            when(si.getSourceVersion()).thenReturn(SHA_1);
            when(si.getMetaDataVersion()).thenReturn(SHA_1_MD);

            when(source.getSourceInfo(Mockito.eq(key), Mockito.anyString())).thenReturn(si);
            StoreInfo key2 = gs.getKey(key, null).get();
            assertNotNull(key2);
            gs.delete(key, null, new CommitMetaData("user", "mail", "msg"));
            Thread.sleep(1000);
            gs.checkHealth();
            Mockito.verify(source).deleteKey(Mockito.eq(key), Mockito.eq(REF_HEADS_MASTER), Mockito.any());
        }
    }

    @Test
    public void testAddkeyWithNewBranch() throws Exception {
        String key = "somekey";
        String branch = "refs/heads/newbranch";
        Mockito.when(source.getSourceInfo(Mockito.eq(key), Mockito.eq(branch))).thenThrow(RefNotFoundException.class);
        Mockito.when(source.addKey(Mockito.eq(key), Mockito.eq(branch), Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(Pair.of("1", "1"));
        try (GitStorage gs = new GitStorage(source, null)) {
            byte[] data = getByteArray(1);
            byte[] pretty = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(MAPPER.readTree(data));
            String si = gs.addKey(key, branch, pretty, new MetaData(new HashSet<>(), null, false, false, List.of(), null, null),
                    new CommitMetaData("user", "mail", "msg"));
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
        Mockito.when(source.getSourceInfo(Mockito.eq(key), Mockito.eq(branch))).thenThrow(RefNotFoundException.class);
        Mockito.when(source.getSourceInfo(Mockito.eq(key), Mockito.eq("refs/heads/master"))).thenReturn(mock(SourceInfo.class));
        try (GitStorage gs = new GitStorage(source, null)) {
            byte[] data = getByteArray(1);
            byte[] pretty = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(MAPPER.readTree(data));
            assertSame(KeyAlreadyExist.class,
                    assertThrows(WrappingAPIException.class, () -> gs.addKey(key, branch, pretty,
                            new MetaData(new HashSet<>(), null, false, false, List.of(), null, null), new CommitMetaData("user", "mail", "msg"))).getCause()
                                    .getClass());
        }
    }

    @Test
    public void testGetListForRef() throws RefNotFoundException, IOException {
        try (GitStorage gs = new GitStorage(source, null); InputStream test3 = getInputStream(1); InputStream mtest3 = getMetaData()) {
            SourceInfo si = mock(SourceInfo.class);
            when(si.getSourceInputStream()).thenReturn(test3);
            when(si.getMetadataInputStream()).thenReturn(mtest3);
            when(si.getSourceVersion()).thenReturn(SHA_1);
            when(si.getMetaDataVersion()).thenReturn(SHA_1_MD);
            String key = "dir/";
            String dirkey = "dir/key";
            when(source.getSourceInfo(Mockito.eq(dirkey), Mockito.anyString())).thenReturn(si);

            Mockito.when(source.getList(Mockito.eq(key), Mockito.anyString(), Mockito.anyBoolean())).thenReturn(List.of(dirkey));
            List<Pair<String, Boolean>> keys = List.of(Pair.of(key, false));
            List<Pair<String, StoreInfo>> list = gs.getListForRef(keys, REF_HEADS_MASTER);
            assertEquals(1, list.size());
            list = gs.getListForRef(keys, REF_HEADS_MASTER);
            assertEquals(1, list.size());
        }
    }

    @Test
    public void testGetList() throws Exception {
        try (GitStorage gs = new GitStorage(source, null); InputStream test3 = getInputStream(1); InputStream mtest3 = getMetaData()) {
            SourceInfo si = mock(SourceInfo.class);
            when(si.getSourceInputStream()).thenReturn(test3);
            when(si.getMetadataInputStream()).thenReturn(mtest3);
            when(si.getSourceVersion()).thenReturn(SHA_1);
            when(si.getMetaDataVersion()).thenReturn(SHA_1_MD);
            String key = "dir/";
            String dirkey = "dir/key";
            when(source.getSourceInfo(Mockito.eq(dirkey), Mockito.anyString())).thenReturn(si);

            Mockito.when(source.getList(Mockito.eq(key), Mockito.anyString(), Mockito.anyBoolean())).thenReturn(List.of(dirkey));
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
        try (GitStorage gs = new GitStorage(source, null)) {
            assertThrows(WrappingAPIException.class,
                    () -> gs.addKey("dot/.dot", null, new byte[] { 1 }, new MetaData(Set.of()), new CommitMetaData("d", "d", "d")));
        }
    }

    @Test
    public void testGetUser() throws RefNotFoundException, IOException {
        Mockito.when(source.getUser(Mockito.anyString(), Mockito.anyString())).thenReturn(Pair.of("1", new UserData(Set.of(new Role("role")), "1234")));
        try (GitStorage gs = new GitStorage(source, null)) {
            assertNotNull(gs.getUser("name", "refs/heads/secret", JitStaticConstants.GIT_REALM));
        }
    }

    @Test
    public void testGetListForNoKey() throws RefNotFoundException {
        Mockito.when(source.getSourceInfo(Mockito.eq("key"), Mockito.eq("refs/heads/master"))).thenReturn(null);
        try (GitStorage gs = new GitStorage(source, null)) {
            gs.getListForRef(List.of(Pair.of("key", false)), "refs/heads/master");
        }
    }

    @Test
    public void testGetListNoRef() throws RefNotFoundException, IOException {
        Mockito.when(source.getList(Mockito.eq("key/"), Mockito.eq("refs/heads/master"), Mockito.anyBoolean())).thenThrow(new RefNotFoundException("test"));
        try (GitStorage gs = new GitStorage(source, null)) {
            List<Pair<String, StoreInfo>> listForRef = gs.getListForRef(List.of(Pair.of("key/", false)), "refs/heads/master");
            assertTrue(listForRef.isEmpty());
        }
    }

    @Test
    public void testGetListIOException() throws RefNotFoundException, IOException {
        Mockito.when(source.getList(Mockito.eq("key/"), Mockito.eq("refs/heads/master"), Mockito.anyBoolean())).thenThrow(new IOException("test"));
        try (GitStorage gs = new GitStorage(source, null)) {
            List<Pair<String, StoreInfo>> listForRef = gs.getListForRef(List.of(Pair.of("key/", false)), "refs/heads/master");
            assertTrue(listForRef.isEmpty());
        }
    }

    @Test
    public void testGetListForAKey() throws RefNotFoundException, IOException {
        try (GitStorage gs = new GitStorage(source, null); InputStream test3 = getInputStream(1); InputStream mtest3 = getMetaData()) {
            SourceInfo si = mock(SourceInfo.class);
            when(si.getSourceInputStream()).thenReturn(test3);
            when(si.getMetadataInputStream()).thenReturn(mtest3);
            when(si.getSourceVersion()).thenReturn(SHA_1);
            when(si.getMetaDataVersion()).thenReturn(SHA_1_MD);
            Mockito.when(source.getSourceInfo(Mockito.eq("key"), Mockito.eq("refs/heads/master"))).thenReturn(si);
            gs.getListForRef(List.of(Pair.of("key", false)), "refs/heads/master");
        }
    }

    private byte[] getByteArray(int c) {
        return ("{\"data\":\"value" + c + "\"}").getBytes(UTF_8);
    }

    private InputStream getInputStream(int c) {
        return new ByteArrayInputStream(getByteArray(c));
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
