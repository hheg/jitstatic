package io.jitstatic.storage;

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

import static io.jitstatic.storage.tools.Utils.toProvider;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.lib.ObjectLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.spencerwi.either.Either;

import io.jitstatic.CommitMetaData;
import io.jitstatic.MetaData;
import io.jitstatic.hosted.FailedToLock;
import io.jitstatic.hosted.LoadException;
import io.jitstatic.hosted.StoreInfo;
import io.jitstatic.source.Source;
import io.jitstatic.source.SourceInfo;
import io.jitstatic.utils.Functions;
import io.jitstatic.utils.Functions.ThrowingSupplier;
import io.jitstatic.utils.LinkedException;
import io.jitstatic.utils.Pair;
import io.jitstatic.utils.WrappingAPIException;

public class RefHolderTest {

    private static final String REF = "refs/heads/master";
    private Source source;

    @BeforeEach
    public void setup() {
        source = mock(Source.class);
    }

    @Test
    public void testPutGet() {
        RefHolder ref = new RefHolder(REF, source);
        ref.putKey("key", Optional.empty());
        assertNotNull(ref.getKey("key"));
        assertTrue(ref.isEmpty());
    }

    @Test
    public void testLockWrite() throws FailedToLock, InterruptedException {
        RefHolder ref = new RefHolder(REF, source);
        AtomicBoolean b = new AtomicBoolean(true);
        AtomicBoolean c = new AtomicBoolean(true);
        CompletableFuture<Void> async = CompletableFuture.runAsync(() -> {
            Either<Boolean, FailedToLock> lockWrite = ref.lockWrite(() -> {
                c.set(false);
                while (b.get()) {
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                }
                return true;
            }, "a");
            assertTrue(lockWrite.isLeft());
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
        RefHolder ref = new RefHolder(REF, source);
        ref.lockWrite(() -> {
            Either<Boolean, FailedToLock> lockWrite = ref.lockWrite(() -> true, "a");
            assertTrue(lockWrite.isLeft());
            return true;
        }, "a");
    }

    @Test
    public void testLockWriteFail() throws FailedToLock {
        RefHolder ref = new RefHolder(REF, source);
        assertEquals(FailedToLock.class, assertThrows(RuntimeException.class, () -> ref.lockWrite(() -> {
            CompletableFuture.runAsync(() -> {
                Either<Boolean, FailedToLock> lockWrite = ref.lockWrite(() -> {
                    return true;
                }, "a");
                if (lockWrite.isRight()) {
                    throw new RuntimeException("a", lockWrite.getRight());
                }
            }).join();
            return true;
        }, "a")).getCause().getCause().getClass());
    }

    @Test
    public void testReadWrite() {
        RefHolder ref = new RefHolder(REF, source);
        ref.read(() -> "1");
        ref.write(() -> {
        });
        ref.write(() -> "1");
    }

    @Test
    public void testReloadAll() {
        RefHolder ref = new RefHolder(REF, source);
        ref.write(() -> {
            ref.reloadAll(() -> {
            });
        });
    }

    @Test
    public void testReloadAllWithoutLock() {
        RefHolder ref = new RefHolder(REF, source);
        assertFalse(ref.reloadAll(() -> {
        }));
    }

    @Test
    public void testLockWriteAll() throws FailedToLock {
        RefHolder ref = new RefHolder(REF, source);
        AtomicBoolean b = new AtomicBoolean(true);
        AtomicBoolean c = new AtomicBoolean(true);
        CompletableFuture<Boolean> async = CompletableFuture.supplyAsync(() -> {
            return ref.lockWriteAll(() -> {
                c.set(false);
                while (b.get()) {
                }
                return true;
            }).getLeft();
        });
        while (c.get()) {
        }
        assertThrows(FailedToLock.class, () -> {
            Either<String, FailedToLock> lock = ref.lockWriteAll(() -> "1");
            if (lock.isRight()) {
                throw lock.getRight();
            }
        });
        b.set(false);
        async.join();
    }

    @Test
    public void testRefreshNoFiles() {
        RefHolder ref = new RefHolder(REF, source);
        boolean refresh = ref.refresh();
        assertFalse(refresh);
    }

    @Test
    public void testRefresh() throws RefNotFoundException, IOException {
        StoreInfo storeInfo = mock(StoreInfo.class);
        SourceInfo sourceInfo = mock(SourceInfo.class);
        when(sourceInfo.getSourceProvider()).thenReturn(toProvider(getData().getBytes(UTF_8)));
        when(sourceInfo.readMetaData()).thenCallRealMethod();
        when(sourceInfo.getMetadataInputStream()).thenReturn(asStream(getMetaData()));
        when(sourceInfo.getMetaDataVersion()).thenReturn("2");
        when(sourceInfo.getSourceVersion()).thenReturn("2");
        when(source.getSourceInfo(eq("key"), eq(REF))).thenReturn(sourceInfo);
        RefHolder ref = new RefHolder(REF, source);
        ref.putKey("key", Optional.of(storeInfo));
        boolean refresh = ref.refresh();
        assertTrue(refresh);
    }

    @Test
    public void testRefreshKeyIsNotFound() {
        StoreInfo storeInfo = mock(StoreInfo.class);
        RefHolder ref = new RefHolder(REF, source);
        ref.putKey("key", Optional.of(storeInfo));
        boolean refresh = ref.refresh();
        assertFalse(refresh);
    }

    @Test
    public void testRefreshKeyLoadedWithError() throws RefNotFoundException {
        StoreInfo storeInfo = mock(StoreInfo.class);
        RuntimeException re = new RuntimeException("Test Exception");
        when(source.getSourceInfo(eq("key"), eq(REF))).thenThrow(re);
        RefHolder ref = new RefHolder(REF, source);
        ref.putKey("key", Optional.of(storeInfo));
        assertThrows(LinkedException.class, () -> ref.refresh());
    }

    @Test
    public void testRefreshrefNotFoundForKey() throws RefNotFoundException {
        StoreInfo storeInfo = mock(StoreInfo.class);
        when(source.getSourceInfo(eq("key"), eq(REF))).thenThrow(new RefNotFoundException(""));
        RefHolder ref = new RefHolder(REF, source);
        ref.putKey("key", Optional.of(storeInfo));
        assertFalse(ref.refresh());
    }

    @Test
    public void testRefreshKey() throws IOException {
        StoreInfo storeInfo = mock(StoreInfo.class);
        CommitMetaData cmd = mock(CommitMetaData.class);
        @SuppressWarnings("unchecked")
        ThrowingSupplier<ObjectLoader, IOException> ts = mock(Functions.ThrowingSupplier.class);
        when(storeInfo.getMetaData()).thenReturn(mock(MetaData.class));
        when(storeInfo.getVersion()).thenReturn("1");
        when(storeInfo.getMetaDataVersion()).thenReturn("1");

        byte[] data = getData().getBytes(UTF_8);
        when(source.modifyKey("key", REF, data, "1", cmd)).thenReturn(Pair.of("2", ts));
        RefHolder ref = new RefHolder(REF, source);
        ref.putKey("key", Optional.of(storeInfo));
        ref.modifyKey("key", REF, data, "1", cmd);
        assertEquals("2", ref.getKey("key").get().getVersion());
    }

    @Test
    public void testRefreshMetaData() throws IOException {
        StoreInfo storeInfo = mock(StoreInfo.class);
        MetaData storageData = mock(MetaData.class);
        when(storeInfo.getMetaData()).thenReturn(storageData);
        when(storeInfo.getVersion()).thenReturn("1");
        when(storeInfo.getMetaDataVersion()).thenReturn("1");
        RefHolder ref = new RefHolder(REF, source);
        ref.putKey("key", Optional.of(storeInfo));
        ref.refreshMetaData(storageData, "key", "1", "2");
        assertEquals("2", ref.getKey("key").get().getMetaDataVersion());
    }

    @Test
    public void testLoadAndStore() throws IOException, RefNotFoundException {
        SourceInfo sourceInfo = mock(SourceInfo.class);
        when(sourceInfo.getSourceProvider()).thenReturn(toProvider(getData().getBytes(UTF_8)));
        when(sourceInfo.readMetaData()).thenCallRealMethod();
        when(sourceInfo.getMetadataInputStream()).thenReturn(asStream(getMetaData()));
        when(sourceInfo.getMetaDataVersion()).thenReturn("2");
        when(sourceInfo.getSourceVersion()).thenReturn("2");
        when(source.getSourceInfo(eq("key"), eq(REF))).thenReturn(sourceInfo);
        RefHolder ref = new RefHolder(REF, source);
        assertTrue(ref.loadAndStore("key").isPresent());
    }

    @Test
    public void testLoadAndStoreRefNotFound() throws IOException, RefNotFoundException {
        when(source.getSourceInfo(eq("key"), eq(REF))).thenThrow(new RefNotFoundException(REF));
        RefHolder ref = new RefHolder(REF, source);
        assertThrows(LoadException.class, () -> ref.loadAndStore("key"));
    }

    @Test
    public void testLoadAndStoreHidden() throws RefNotFoundException, IOException {
        SourceInfo sourceInfo = mock(SourceInfo.class);
        when(sourceInfo.getSourceProvider()).thenReturn(toProvider(getData().getBytes(UTF_8)));
        when(sourceInfo.readMetaData()).thenCallRealMethod();
        when(sourceInfo.getMetadataInputStream()).thenReturn(asStream(getMetaDataHidden()));
        when(sourceInfo.getMetaDataVersion()).thenReturn("2");
        when(sourceInfo.getSourceVersion()).thenReturn("2");
        when(source.getSourceInfo(eq("key"), eq(REF))).thenReturn(sourceInfo);
        RefHolder ref = new RefHolder(REF, source);
        Optional<StoreInfo> loadAndStore = ref.loadAndStore("key");
        assertEquals(ref.getKey("key"), loadAndStore);
        assertFalse(loadAndStore.isPresent());
    }

    @Test
    public void testLoadAndStoreMetaData() throws IOException, RefNotFoundException {
        SourceInfo sourceInfo = mock(SourceInfo.class);
        when(sourceInfo.readMetaData()).thenCallRealMethod();
        when(sourceInfo.getMetadataInputStream()).thenReturn(asStream(getMetaData()));
        when(sourceInfo.getMetaDataVersion()).thenReturn("2");
        when(source.getSourceInfo(eq("key/"), eq(REF))).thenReturn(sourceInfo);
        RefHolder ref = new RefHolder(REF, source);
        Optional<StoreInfo> loadAndStore = ref.loadAndStore("key/");
        assertEquals(ref.getKey("key/"), loadAndStore);
        assertTrue(loadAndStore.isPresent());
    }

    @Test
    public void testLoadAndStoreMetaDataInputThrowsIOException() throws IOException, RefNotFoundException {
        SourceInfo sourceInfo = mock(SourceInfo.class);
        IOException ioException = new IOException("Test exception");
        when(sourceInfo.readMetaData()).thenCallRealMethod();
        when(sourceInfo.getMetadataInputStream()).thenThrow(ioException);
        when(source.getSourceInfo(eq("key"), eq(REF))).thenReturn(sourceInfo);
        RefHolder ref = new RefHolder(REF, source);
        assertSame(ioException, assertThrows(UncheckedIOException.class, () -> ref.loadAndStore("key")).getCause());
    }

    @Test
    public void testCheckIfPlainKeyDoesNotExist() {
        RefHolder ref = new RefHolder(REF, source);
        ref.putKey("key", Optional.empty());
        ref.checkIfPlainKeyExist("key/");
    }

    @Test
    public void testCheckIfPlainKeyDoesNotExistNull() {
        RefHolder ref = new RefHolder(REF, source);
        ref.checkIfPlainKeyExist("key/");
    }

    @Test
    public void testCheckIfPlainKeyExist() {
        StoreInfo storeInfo = mock(StoreInfo.class);
        RefHolder ref = new RefHolder(REF, source);
        ref.putKey("key", Optional.of(storeInfo));
        assertThrows(WrappingAPIException.class, () -> ref.checkIfPlainKeyExist("key/"));
    }

    private String getMetaDataHidden() {
        int i = 0;
        return "{\"users\":[{\"password\":\"" + i + "234\",\"user\":\"user1\"}],\"hidden\": true}";
    }

    private ByteArrayInputStream asStream(String data) {
        return new ByteArrayInputStream(data.getBytes(UTF_8));
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
