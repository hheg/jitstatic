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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.lib.ObjectLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.spencerwi.either.Either;

import io.jitstatic.CommitMetaData;
import io.jitstatic.MetaData;
import io.jitstatic.Role;
import io.jitstatic.auth.UserData;
import io.jitstatic.hosted.FailedToLock;
import io.jitstatic.hosted.KeyAlreadyExist;
import io.jitstatic.hosted.LoadException;
import io.jitstatic.hosted.StoreInfo;
import io.jitstatic.source.Source;
import io.jitstatic.source.SourceInfo;
import io.jitstatic.storage.tools.Utils;
import io.jitstatic.utils.Functions;
import io.jitstatic.utils.Functions.ThrowingSupplier;
import io.jitstatic.utils.Pair;
import io.jitstatic.utils.VersionIsNotSame;
import io.jitstatic.utils.WrappingAPIException;

public class RefHolderTest {

    private static final String REF = "refs/heads/master";
    private Source source;
    private HashService hashService = new HashService();

    @BeforeEach
    public void setup() {
        source = mock(Source.class);
    }

    @Test
    public void testPutGet() {
        RefHolder ref = new RefHolder(REF, source, hashService);
        ref.putKey("key", Optional.empty());
        assertNotNull(ref.readKey("key"));
        assertTrue(ref.isEmpty());
    }

    @Test
    public void testLockWrite() throws FailedToLock, InterruptedException {
        RefHolder ref = new RefHolder(REF, source, hashService);
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
        RefHolder ref = new RefHolder(REF, source, hashService);
        ref.lockWrite(() -> {
            Either<Boolean, FailedToLock> lockWrite = ref.lockWrite(() -> true, "a");
            assertTrue(lockWrite.isLeft());
            return true;
        }, "a");
    }

    @Test
    public void testLockWriteFail() throws FailedToLock {
        RefHolder ref = new RefHolder(REF, source, hashService);
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
    public void testLockWriteAll() throws FailedToLock {
        RefHolder ref = new RefHolder(REF, source, hashService);
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
    public void testRefreshKey() throws IOException {
        StoreInfo storeInfo = mock(StoreInfo.class);
        CommitMetaData cmd = mock(CommitMetaData.class);
        @SuppressWarnings("unchecked")
        ThrowingSupplier<ObjectLoader, IOException> ts = mock(Functions.ThrowingSupplier.class);
        when(storeInfo.getMetaData()).thenReturn(mock(MetaData.class));
        when(storeInfo.getVersion()).thenReturn("1");
        when(storeInfo.getMetaDataVersion()).thenReturn("1");

        byte[] data = getData().getBytes(UTF_8);
        when(source.modifyKey(eq("key"), eq(REF), any(), eq("1"), eq(cmd))).thenReturn(Pair.of("2", ts));
        RefHolder ref = new RefHolder(REF, source, hashService);
        ref.putKey("key", Optional.of(storeInfo));
        ref.modifyKey("key", REF, Utils.toProvider(data), "1", cmd);
        assertEquals("2", ref.readKey("key").get().getVersion());
    }

    @Test
    public void testRefreshMetaData() throws IOException {
        StoreInfo storeInfo = mock(StoreInfo.class);
        MetaData storageData = mock(MetaData.class);
        CommitMetaData commitMetaData = mock(CommitMetaData.class);
        when(source.modifyMetadata(any(), any(), any(), any(), any())).thenReturn("2");
        when(storeInfo.getMetaData()).thenReturn(storageData);
        when(storeInfo.getVersion()).thenReturn("1");
        when(storeInfo.getMetaDataVersion()).thenReturn("1");
        RefHolder ref = new RefHolder(REF, source, hashService);
        ref.putKey("key", Optional.of(storeInfo));
        Either<String, FailedToLock> modifyMetadata = ref.modifyMetadata(storageData, "1", commitMetaData, "key", null);
        assertEquals("2", modifyMetadata.getLeft());
        Optional<StoreInfo> key = ref.readKey("key");
        assertEquals("2", key.get().getMetaDataVersion());
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
        RefHolder ref = new RefHolder(REF, source, hashService);
        assertTrue(ref.readKey("key").isPresent());
    }

    @Test
    public void testLoadAndStoreRefNotFound() throws IOException, RefNotFoundException {
        when(source.getSourceInfo(eq("key"), eq(REF))).thenThrow(new RefNotFoundException(REF));
        RefHolder ref = new RefHolder(REF, source, hashService);
        assertThrows(LoadException.class, () -> ref.readKey("key"));
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
        RefHolder ref = new RefHolder(REF, source, hashService);
        Optional<StoreInfo> loadAndStore = ref.readKey("key");
        assertEquals(ref.readKey("key"), loadAndStore);
        assertFalse(loadAndStore.isPresent());
    }

    @Test
    public void testLoadAndStoreMetaData() throws IOException, RefNotFoundException {
        SourceInfo sourceInfo = mock(SourceInfo.class);
        when(sourceInfo.readMetaData()).thenCallRealMethod();
        when(sourceInfo.getMetadataInputStream()).thenReturn(asStream(getMetaData()));
        when(sourceInfo.getMetaDataVersion()).thenReturn("2");
        when(sourceInfo.isMetaDataSource()).thenReturn(true);
        when(source.getSourceInfo(eq("key/"), eq(REF))).thenReturn(sourceInfo);
        RefHolder ref = new RefHolder(REF, source, hashService);
        Optional<StoreInfo> loadAndStore = ref.readKey("key/");
        assertEquals(ref.readKey("key/"), loadAndStore);
        assertTrue(loadAndStore.isPresent());
    }

    @Test
    public void testLoadAndStoreMetaDataInputThrowsIOException() throws IOException, RefNotFoundException {
        SourceInfo sourceInfo = mock(SourceInfo.class);
        IOException ioException = new IOException("Test exception");
        when(sourceInfo.readMetaData()).thenCallRealMethod();
        when(sourceInfo.getMetadataInputStream()).thenThrow(ioException);
        when(source.getSourceInfo(eq("key"), eq(REF))).thenReturn(sourceInfo);
        RefHolder ref = new RefHolder(REF, source, hashService);
        assertSame(ioException, assertThrows(UncheckedIOException.class, () -> ref.readKey("key")).getCause());
    }

    @Test
    public void testCheckIfPlainKeyDoesNotExist() {
        RefHolder ref = new RefHolder(REF, source, hashService);
        ref.putKey("key", Optional.empty());
        ref.checkIfPlainKeyExist("key/");
    }

    @Test
    public void testCheckIfPlainKeyDoesNotExistNull() {
        RefHolder ref = new RefHolder(REF, source, hashService);
        ref.checkIfPlainKeyExist("key/");
    }

    @Test
    public void testCheckIfPlainKeyExist() {
        StoreInfo storeInfo = mock(StoreInfo.class);
        RefHolder ref = new RefHolder(REF, source, hashService);
        ref.putKey("key", Optional.of(storeInfo));
        assertThrows(WrappingAPIException.class, () -> ref.checkIfPlainKeyExist("key/"));
    }

    @Test
    public void testmodifyUserButNotLoaded() throws RefNotFoundException, IOException {
        RefHolder ref = new RefHolder(REF, source, hashService);
        assertEquals(VersionIsNotSame.class, assertThrows(WrappingAPIException.class,
                () -> ref.updateUser("user", "user", new UserData(Set.of(new Role("role")), null, "salt", "hash"), "1")).getCause().getClass());
    }

    @Test
    public void testModifyUserWithNewRoles() throws RefNotFoundException, IOException {
        Mockito.when(source.addUser(Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any())).thenReturn("1");
        RefHolder ref = new RefHolder(REF, source, hashService);
        assertEquals("1", ref.addUser("user", "modifyinguser", new UserData(Set.of(new Role("role")), null, "salt", "hash")).getLeft());
        ref.updateUser("user", "modifyinguser", new UserData(Set.of(new Role("role")), null, "salt", "hash"), "1");
        Mockito.verify(source).updateUser(Mockito.eq(".users/user"), Mockito.anyString(), Mockito.eq("modifyinguser"),
                Mockito.eq(new UserData(Set.of(new Role("role")), null, "salt", "hash")));
    }

    @Test
    public void testCheckIfPlainKeyDoesExist() {
        RefHolder ref = new RefHolder(REF, source, hashService);
        StoreInfo si = Mockito.mock(StoreInfo.class);
        ref.putKey("key", Optional.of(si));
        assertEquals(KeyAlreadyExist.class, assertThrows(WrappingAPIException.class, () -> ref.checkIfPlainKeyExist("key/")).getCause().getClass());
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
