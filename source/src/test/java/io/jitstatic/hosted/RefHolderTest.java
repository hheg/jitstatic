package io.jitstatic.hosted;

/*-
 * #%L
 * jitstatic
 * %%
 * Copyright (C) 2017 - 2018 H.Hegardt
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.jitstatic.StorageData;
import io.jitstatic.source.Source;
import io.jitstatic.source.SourceInfo;
import io.jitstatic.utils.LinkedException;
import io.jitstatic.utils.ShouldNeverHappenException;
import io.jitstatic.utils.WrappingAPIException;

public class RefHolderTest {

    private static final String REF = "refs/heads/master";
    private Source source;

    @BeforeEach
    public void setup() {
        source = Mockito.mock(Source.class);
    }

    @Test
    public void testPutGet() {
        RefHolder ref = new RefHolder(REF, new ConcurrentHashMap<>(), source);
        ref.putKey("key", Optional.empty());
        assertNotNull(ref.getKey("key"));
        assertTrue(ref.isEmpty());
    }

    @Test
    public void testLockWrite() throws FailedToLock, InterruptedException {
        RefHolder ref = new RefHolder(REF, new ConcurrentHashMap<>(), source);
        AtomicBoolean b = new AtomicBoolean(true);
        AtomicBoolean c = new AtomicBoolean(true);
        CompletableFuture<Void> async = CompletableFuture.runAsync(() -> {
            try {
                ref.lockWrite(() -> {
                    c.set(false);
                    while (b.get()) {
                    }
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                    }
                    return true;
                }, "a");
            } catch (FailedToLock e) {
                throw new ShouldNeverHappenException("a", e);
            }
        });
        while (c.get()) {
        }
        Thread.sleep(100);
        b.set(false);
        ref.lockWrite(() -> {
            return true;
        }, "b");
        async.join();
    }

    @Test
    public void testLockTwiceOnKeys() throws FailedToLock {
        RefHolder ref = new RefHolder(REF, new ConcurrentHashMap<>(), source);
        ref.lockWrite(() -> {
            try {
                ref.lockWrite(() -> true, "a");
            } catch (FailedToLock e) {
                throw new ShouldNeverHappenException("inner a");
            }
            return true;
        }, "a");
    }

    @Test
    public void testLockWriteFail() throws FailedToLock {
        RefHolder ref = new RefHolder(REF, new ConcurrentHashMap<>(), source);
        assertEquals(FailedToLock.class, assertThrows(RuntimeException.class, () -> ref.lockWrite(() -> {
            CompletableFuture.runAsync(() -> {
                try {
                    ref.lockWrite(() -> {
                        return true;
                    }, "a");
                } catch (FailedToLock e) {
                    throw new RuntimeException("a", e);
                }
            }).join();
            return true;
        }, "a")).getCause().getCause().getClass());
    }

    @Test
    public void testReadWrite() {
        RefHolder ref = new RefHolder(REF, new ConcurrentHashMap<>(), source);
        ref.read(() -> "1");
        ref.write(() -> {
        });
        ref.write(() -> "1");
    }

    @Test
    public void testReloadAll() {
        RefHolder ref = new RefHolder(REF, new ConcurrentHashMap<>(), source);
        ref.write(() -> {
            try {
                ref.reloadAll(() -> {
                });
            } catch (FailedToLock e) {
                throw new ShouldNeverHappenException("lock");
            }
        });
    }

    @Test
    public void testReloadAllWithoutLock() {
        RefHolder ref = new RefHolder(REF, new ConcurrentHashMap<>(), source);
        assertThrows(FailedToLock.class, () -> ref.reloadAll(() -> {
        }));
    }

    @Test
    public void testLockWriteAll() throws FailedToLock {
        RefHolder ref = new RefHolder(REF, new ConcurrentHashMap<>(), source);
        AtomicBoolean b = new AtomicBoolean(true);
        AtomicBoolean c = new AtomicBoolean(true);
        CompletableFuture<Boolean> async = CompletableFuture.supplyAsync(() -> {
            try {
                return ref.lockWriteAll(() -> {
                    c.set(false);
                    while (b.get()) {
                    }
                    return true;
                });
            } catch (FailedToLock e) {
                throw new ShouldNeverHappenException("");
            }
        });
        while (c.get()) {
        }
        assertThrows(FailedToLock.class, () -> ref.lockWriteAll(() -> "1"));
        b.set(false);
        async.join();
    }

    @Test
    public void testRefreshNoFiles() {
        RefHolder ref = new RefHolder(REF, new ConcurrentHashMap<>(), source);
        boolean refresh = ref.refresh();
        assertFalse(refresh);
    }

    @Test
    public void testRefresh() throws RefNotFoundException, IOException {
        StoreInfo storeInfo = Mockito.mock(StoreInfo.class);
        SourceInfo sourceInfo = Mockito.mock(SourceInfo.class);
        Mockito.when(sourceInfo.getMetadataInputStream()).thenReturn(asStream(getMetaData()));
        Mockito.when(sourceInfo.getSourceInputStream()).thenReturn(asStream(getData()));
        Mockito.when(sourceInfo.getMetaDataVersion()).thenReturn("2");
        Mockito.when(sourceInfo.getSourceVersion()).thenReturn("2");
        Mockito.when(source.getSourceInfo(Mockito.eq("key"), Mockito.eq(REF))).thenReturn(sourceInfo);
        RefHolder ref = new RefHolder(REF, new ConcurrentHashMap<>(), source);
        ref.putKey("key", Optional.of(storeInfo));
        boolean refresh = ref.refresh();
        assertTrue(refresh);
    }

    @Test
    public void testRefreshKeyIsNotFound() {
        StoreInfo storeInfo = Mockito.mock(StoreInfo.class);
        RefHolder ref = new RefHolder(REF, new ConcurrentHashMap<>(), source);
        ref.putKey("key", Optional.of(storeInfo));
        boolean refresh = ref.refresh();
        assertFalse(refresh);
    }

    @Test
    public void testRefreshKeyLoadedWithError() throws RefNotFoundException {
        StoreInfo storeInfo = Mockito.mock(StoreInfo.class);
        RuntimeException re = new RuntimeException("Test Exception");
        Mockito.when(source.getSourceInfo(Mockito.eq("key"), Mockito.eq(REF))).thenThrow(re);
        RefHolder ref = new RefHolder(REF, new ConcurrentHashMap<>(), source);
        ref.putKey("key", Optional.of(storeInfo));
        assertThrows(LinkedException.class, () -> ref.refresh());
    }

    @Test
    public void testRefreshrefNotFoundForKey() throws RefNotFoundException {
        StoreInfo storeInfo = Mockito.mock(StoreInfo.class);
        Mockito.when(source.getSourceInfo(Mockito.eq("key"), Mockito.eq(REF))).thenThrow(new RefNotFoundException(""));
        RefHolder ref = new RefHolder(REF, new ConcurrentHashMap<>(), source);
        ref.putKey("key", Optional.of(storeInfo));
        assertFalse(ref.refresh());
    }

    @Test
    public void testRefreshKey() throws IOException {
        StoreInfo storeInfo = Mockito.mock(StoreInfo.class);
        Mockito.when(storeInfo.getStorageData()).thenReturn(Mockito.mock(StorageData.class));
        Mockito.when(storeInfo.getVersion()).thenReturn("1");
        Mockito.when(storeInfo.getMetaDataVersion()).thenReturn("1");
        RefHolder ref = new RefHolder(REF, new ConcurrentHashMap<>(), source);
        ref.putKey("key", Optional.of(storeInfo));
        ref.refreshKey(asStream(getData()).readAllBytes(), "key", "1", "2", "application/json");
        assertEquals("2", ref.getKey("key").get().getVersion());
    }

    @Test
    public void testRefreshMetaData() throws IOException {
        StoreInfo storeInfo = Mockito.mock(StoreInfo.class);
        StorageData storageData = Mockito.mock(StorageData.class);
        Mockito.when(storeInfo.getStorageData()).thenReturn(storageData);
        Mockito.when(storeInfo.getVersion()).thenReturn("1");
        Mockito.when(storeInfo.getMetaDataVersion()).thenReturn("1");
        RefHolder ref = new RefHolder(REF, new ConcurrentHashMap<>(), source);
        ref.putKey("key", Optional.of(storeInfo));
        ref.refreshMetaData(storageData, "key", "1", "2");
        assertEquals("2", ref.getKey("key").get().getMetaDataVersion());
    }

    @Test
    public void testLoadAndStore() throws IOException, RefNotFoundException {
        SourceInfo sourceInfo = Mockito.mock(SourceInfo.class);
        Mockito.when(sourceInfo.getMetadataInputStream()).thenReturn(asStream(getMetaData()));
        Mockito.when(sourceInfo.getSourceInputStream()).thenReturn(asStream(getData()));
        Mockito.when(sourceInfo.getMetaDataVersion()).thenReturn("2");
        Mockito.when(sourceInfo.getSourceVersion()).thenReturn("2");
        Mockito.when(source.getSourceInfo(Mockito.eq("key"), Mockito.eq(REF))).thenReturn(sourceInfo);
        RefHolder ref = new RefHolder(REF, new ConcurrentHashMap<>(), source);
        assertTrue(ref.loadAndStore("key").isPresent());
    }

    @Test
    public void testLoadAndStoreRefNotFound() throws IOException, RefNotFoundException {
        Mockito.when(source.getSourceInfo(Mockito.eq("key"), Mockito.eq(REF))).thenThrow(new RefNotFoundException(REF));
        RefHolder ref = new RefHolder(REF, new ConcurrentHashMap<>(), source);
        assertThrows(LoadException.class, () -> ref.loadAndStore("key"));
    }

    @Test
    public void testLoadAndStoreHidden() throws RefNotFoundException, IOException {
        SourceInfo sourceInfo = Mockito.mock(SourceInfo.class);
        Mockito.when(sourceInfo.getMetadataInputStream()).thenReturn(asStream(getMetaDataHidden()));
        Mockito.when(sourceInfo.getSourceInputStream()).thenReturn(asStream(getData()));
        Mockito.when(sourceInfo.getMetaDataVersion()).thenReturn("2");
        Mockito.when(sourceInfo.getSourceVersion()).thenReturn("2");
        Mockito.when(source.getSourceInfo(Mockito.eq("key"), Mockito.eq(REF))).thenReturn(sourceInfo);
        RefHolder ref = new RefHolder(REF, new ConcurrentHashMap<>(), source);
        Optional<StoreInfo> loadAndStore = ref.loadAndStore("key");
        assertEquals(ref.getKey("key"), loadAndStore);
        assertFalse(loadAndStore.isPresent());
    }

    @Test
    public void testLoadAndStoreMetaData() throws IOException, RefNotFoundException {
        SourceInfo sourceInfo = Mockito.mock(SourceInfo.class);
        Mockito.when(sourceInfo.getMetadataInputStream()).thenReturn(asStream(getMetaData()));
        Mockito.when(sourceInfo.getMetaDataVersion()).thenReturn("2");
        Mockito.when(source.getSourceInfo(Mockito.eq("key/"), Mockito.eq(REF))).thenReturn(sourceInfo);
        RefHolder ref = new RefHolder(REF, new ConcurrentHashMap<>(), source);
        Optional<StoreInfo> loadAndStore = ref.loadAndStore("key/");
        assertEquals(ref.getKey("key/"), loadAndStore);
        assertTrue(loadAndStore.isPresent());
    }

    @Test
    public void testLoadAndStoreSourceInputThrowsIOException() throws IOException, RefNotFoundException {
        SourceInfo sourceInfo = Mockito.mock(SourceInfo.class);
        IOException ioException = new IOException("Test exception");
        Mockito.when(sourceInfo.getMetadataInputStream()).thenReturn(asStream(getMetaData()));
        Mockito.when(sourceInfo.getSourceInputStream()).thenThrow(ioException);
        Mockito.when(source.getSourceInfo(Mockito.eq("key"), Mockito.eq(REF))).thenReturn(sourceInfo);
        RefHolder ref = new RefHolder(REF, new ConcurrentHashMap<>(), source);
        assertSame(ioException, assertThrows(UncheckedIOException.class, () -> ref.loadAndStore("key")).getCause());
    }

    @Test
    public void testLoadAndStoreMetaDataInputThrowsIOException() throws IOException, RefNotFoundException {
        SourceInfo sourceInfo = Mockito.mock(SourceInfo.class);
        IOException ioException = new IOException("Test exception");
        Mockito.when(sourceInfo.getMetadataInputStream()).thenThrow(ioException);
        ;
        Mockito.when(source.getSourceInfo(Mockito.eq("key"), Mockito.eq(REF))).thenReturn(sourceInfo);
        RefHolder ref = new RefHolder(REF, new ConcurrentHashMap<>(), source);
        assertSame(ioException, assertThrows(UncheckedIOException.class, () -> ref.loadAndStore("key")).getCause());
    }

    @Test
    public void testCheckIfPlainKeyDoesNotExist() {
        RefHolder ref = new RefHolder(REF, new ConcurrentHashMap<>(), source);
        ref.putKey("key", Optional.empty());
        ref.checkIfPlainKeyExist("key/");
    }

    @Test
    public void testCheckIfPlainKeyDoesNotExistNull() {
        RefHolder ref = new RefHolder(REF, new ConcurrentHashMap<>(), source);
        ref.checkIfPlainKeyExist("key/");
    }

    @Test
    public void testCheckIfPlainKeyExist() {
        StoreInfo storeInfo = Mockito.mock(StoreInfo.class);
        RefHolder ref = new RefHolder(REF, new ConcurrentHashMap<>(), source);
        ref.putKey("key", Optional.of(storeInfo));
        assertThrows(WrappingAPIException.class, () -> ref.checkIfPlainKeyExist("key/"));
    }

    private String getMetaDataHidden() {
        int i = 0;
        return "{\"users\":[{\"password\":\"" + i + "234\",\"user\":\"user1\"}],\"hidden\": true}";
    }

    private ByteArrayInputStream asStream(String data) {
        return new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8));
    }

    private String getData() {
        return getData(0);
    }

    private String getMetaData(int i) {
        return "{\"users\":[{\"password\":\"" + i + "234\",\"user\":\"user1\"}]}";
    }

    private String getMetaData() {
        return getMetaData(0);
    }

    private String getData(int i) {
        return "{\"key" + i
                + "\":{\"data\":\"value1\",\"users\":[{\"password\":\"1234\",\"user\":\"user1\"}]},\"mkey3\":{\"data\":\"value3\",\"users\":[{\"password\":\"1234\",\"user\":\"user1\"}]}}";
    }

}
