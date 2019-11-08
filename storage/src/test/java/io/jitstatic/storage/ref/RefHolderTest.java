package io.jitstatic.storage.ref;

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

import static io.jitstatic.source.ObjectStreamProvider.toProvider;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.lib.ObjectLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.codahale.metrics.MetricRegistry;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.spencerwi.either.Either;

import io.jitstatic.CommitMetaData;
import io.jitstatic.MetaData;
import io.jitstatic.Role;
import io.jitstatic.auth.UserData;
import io.jitstatic.hosted.FailedToLock;
import io.jitstatic.hosted.LoadException;
import io.jitstatic.hosted.StoreInfo;
import io.jitstatic.source.Source;
import io.jitstatic.source.SourceInfo;
import io.jitstatic.storage.HashService;
import io.jitstatic.storage.KeyAlreadyExist;
import io.jitstatic.storage.NamingThreadFactory;
import io.jitstatic.storage.ref.LocalRefLockService;
import io.jitstatic.storage.ref.RefHolder;
import io.jitstatic.storage.ref.RefLockService;
import io.jitstatic.test.BaseTest;
import io.jitstatic.utils.Functions;
import io.jitstatic.utils.Functions.ThrowingSupplier;
import io.jitstatic.utils.Pair;
import io.jitstatic.utils.VersionIsNotSame;
import io.jitstatic.utils.WrappingAPIException;

public class RefHolderTest extends BaseTest {

    private static final String REF = "refs/heads/master";
    private Source source;
    private HashService hashService = new HashService();
    private LocalRefLockService clusterService;
    private ExecutorService workStealer;
    private ExecutorService repoWriter;
    private LockService lock;

    @BeforeEach
    public void setup() {
        source = mock(Source.class);
        workStealer = Executors.newWorkStealingPool();
        repoWriter = Executors.newSingleThreadExecutor(new NamingThreadFactory("test-repowriter"));
        clusterService = mock(LocalRefLockService.class);
        lock = new LockServiceImpl(clusterService, REF, workStealer, source, hashService, repoWriter);
        when(clusterService.getLockService(REF, workStealer, source, hashService)).thenReturn(lock);
    }

    @AfterEach
    public void tearDown() throws Exception {
        clusterService.close();
        shutdownExecutor(repoWriter);
        shutdownExecutor(workStealer);
    }

    @Test
    public void testAssertRefIsEmptyIfEmptyCachedKeyIsPresent() {
        try (RefHolder ref = new RefHolder(REF, source, hashService, clusterService, workStealer);) {
            ref.start();
            lock.putKeyFull("key", Either.left(Optional.empty()));
            assertNotNull(ref.readKey("key").isPresent());
            assertTrue(ref.isEmpty());
        }
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
        when(source.modifyKey(eq("key"), eq(REF), any(), eq(cmd))).thenReturn(Pair.of("2", ts));
        try (RefHolder ref = new RefHolder(REF, source, hashService, clusterService, workStealer);) {
            ref.start();
            lock.putKeyFull("key", Either.left(Optional.of(storeInfo)));
            ref.modifyKey("key", toProvider(data), "1", cmd).orTimeout(5, TimeUnit.SECONDS).join();
            assertEquals("2", ref.readKey("key").get().getVersion());
        }
    }

    @Test
    public void testRefreshMetaData() throws IOException {
        StoreInfo storeInfo = mock(StoreInfo.class);
        MetaData storageData = mock(MetaData.class);
        CommitMetaData commitMetaData = mock(CommitMetaData.class);

        when(source.modifyMetadata(any(), anyString(), any(), any(), any())).thenReturn("2");
        when(storeInfo.getMetaData()).thenReturn(storageData);
        when(storeInfo.getVersion()).thenReturn("1");
        when(storeInfo.getMetaDataVersion()).thenReturn("1");
        try (RefHolder ref = new RefHolder(REF, source, hashService, clusterService, workStealer);) {
            ref.start();
            lock.putKeyFull("key", Either.left(Optional.of(storeInfo)));
            CompletableFuture<Either<String, FailedToLock>> modifyMetadata = ref.modifyMetadata("key", storageData, "1", commitMetaData);
            assertEquals("2", modifyMetadata.orTimeout(5, TimeUnit.SECONDS).join().getLeft());
            Optional<StoreInfo> key = ref.readKey("key");
            assertEquals("2", key.get().getMetaDataVersion());
        }
    }

    @Test
    public void testLoadAndStore() throws IOException, RefNotFoundException {
        SourceInfo sourceInfo = mock(SourceInfo.class);

        when(sourceInfo.getStreamProvider()).thenReturn(toProvider(getData().getBytes(UTF_8)));
        when(sourceInfo.readMetaData()).thenCallRealMethod();
        when(sourceInfo.getMetadataInputStream()).thenReturn(asStream(getMetaData()));
        when(sourceInfo.getMetaDataVersion()).thenReturn("2");
        when(sourceInfo.getSourceVersion()).thenReturn("2");
        when(source.getSourceInfo(eq("key"), eq(REF))).thenReturn(sourceInfo);
        try (RefHolder ref = new RefHolder(REF, source, hashService, clusterService, workStealer);) {
            ref.start();
            assertTrue(ref.readKey("key").isPresent());
        }
    }

    @Test
    public void testLoadAndStoreRefNotFound() throws IOException, RefNotFoundException {
        when(source.getSourceInfo(eq("key"), eq(REF))).thenThrow(new RefNotFoundException(REF));
        try (RefHolder ref = new RefHolder(REF, source, hashService, clusterService, workStealer);) {
            ref.start();
            assertThrows(LoadException.class, () -> ref.readKey("key"));
        }
    }

    @Test
    public void testLoadAndStoreHidden() throws RefNotFoundException, IOException {
        SourceInfo sourceInfo = mock(SourceInfo.class);

        when(sourceInfo.getStreamProvider()).thenReturn(toProvider(getData().getBytes(UTF_8)));
        when(sourceInfo.readMetaData()).thenCallRealMethod();
        when(sourceInfo.getMetadataInputStream()).thenReturn(asStream(getMetaDataHidden()));
        when(sourceInfo.getMetaDataVersion()).thenReturn("2");
        when(sourceInfo.getSourceVersion()).thenReturn("2");
        when(source.getSourceInfo(eq("key"), eq(REF))).thenReturn(sourceInfo);
        try (RefHolder ref = new RefHolder(REF, source, hashService, clusterService, workStealer);) {
            ref.start();
            Optional<StoreInfo> loadAndStore = ref.readKey("key");
            assertEquals(ref.readKey("key"), loadAndStore);
            assertFalse(loadAndStore.isPresent());
        }
    }

    @Test
    public void testLoadAndStoreMetaData() throws IOException, RefNotFoundException {
        SourceInfo sourceInfo = mock(SourceInfo.class);

        when(sourceInfo.readMetaData()).thenCallRealMethod();
        when(sourceInfo.getMetadataInputStream()).thenReturn(asStream(getMetaData()));
        when(sourceInfo.getMetaDataVersion()).thenReturn("2");
        when(sourceInfo.isMetaDataSource()).thenReturn(true);
        when(source.getSourceInfo(eq("key/"), eq(REF))).thenReturn(sourceInfo);
        try (RefHolder ref = new RefHolder(REF, source, hashService, clusterService, workStealer);) {
            ref.start();
            Optional<StoreInfo> loadAndStore = ref.readKey("key/");
            assertEquals(ref.readKey("key/"), loadAndStore);
            assertTrue(loadAndStore.isPresent());
        }
    }

    @Test
    public void testLoadAndStoreMetaDataInputThrowsIOException() throws IOException, RefNotFoundException {
        SourceInfo sourceInfo = mock(SourceInfo.class);
        IOException ioException = new IOException("Test exception");

        when(sourceInfo.readMetaData()).thenCallRealMethod();
        when(sourceInfo.getMetadataInputStream()).thenThrow(ioException);
        when(source.getSourceInfo(eq("key"), eq(REF))).thenReturn(sourceInfo);
        try (RefHolder ref = new RefHolder(REF, source, hashService, clusterService, workStealer);) {
            ref.start();
            assertSame(ioException, assertThrows(UncheckedIOException.class, () -> ref.readKey("key")).getCause());
        }
    }

    @Test
    public void testmodifyUserButNotLoaded() throws RefNotFoundException, IOException {
        try (RefHolder ref = new RefHolder(REF, source, hashService, clusterService, workStealer);) {
            ref.start();
            CompletionException ex = assertThrows(CompletionException.class, () -> ref
                    .modifyUser("user", "user", new UserData(Set.of(new Role("role")), null, "salt", "hash"), "1").orTimeout(5, TimeUnit.SECONDS)
                    .join());
            assertEquals(WrappingAPIException.class, ex.getCause().getClass());
            assertEquals(VersionIsNotSame.class, ex.getCause().getCause().getClass());
        }
    }

    @Test
    public void testModifyUserWithNewRoles() throws RefNotFoundException, IOException {
        when(source.addUser(Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any())).thenReturn("1");

        try (RefHolder ref = new RefHolder(REF, source, hashService, clusterService, workStealer);) {
            ref.start();
            assertEquals("1", ref.addUser("user", "modifyinguser", new UserData(Set.of(new Role("role")), null, "salt", "hash")).orTimeout(5, TimeUnit.SECONDS)
                    .join()
                    .getLeft());
            ref.modifyUser("user", "modifyinguser", new UserData(Set.of(new Role("role")), null, "salt", "hash"), "1").orTimeout(5, TimeUnit.SECONDS).join();
            verify(source).updateUser(eq(".users/user"), eq(REF), eq("modifyinguser"), eq(new UserData(Set.of(new Role("role")), null, "salt", "hash")));
        }
    }

    @Test
    public void testModifyUserButIOException() throws RefNotFoundException, IOException {
        when(source.addUser(Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any())).thenReturn("1");
        when(source.updateUser(Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any())).thenThrow(new IOException("test"));
        try (RefHolder ref = new RefHolder(REF, source, hashService, clusterService, workStealer);) {
            ref.start();
            assertEquals("1", ref.addUser("user", "modifyinguser", new UserData(Set.of(new Role("role")), null, "salt", "hash"))
                    .orTimeout(5, TimeUnit.SECONDS).join().getLeft());
            Throwable cause = assertThrows(CompletionException.class, () -> ref
                    .modifyUser("user", "modifyinguser", new UserData(Set.of(new Role("role")), null, "salt", "hash"), "1")
                    .orTimeout(5, TimeUnit.SECONDS).join()).getCause();
            assertEquals(UncheckedIOException.class, cause.getClass());
            assertEquals(IOException.class, cause.getCause().getClass());
        }
    }

    @Test
    public void testModifyUserButRefNotFound() throws RefNotFoundException, IOException {
        when(source.addUser(Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any())).thenReturn("1");
        when(source.updateUser(Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any())).thenThrow(new RefNotFoundException("test"));
        try (RefHolder ref = new RefHolder(REF, source, hashService, clusterService, workStealer);) {
            ref.start();
            assertEquals("1", ref.addUser("user", "modifyinguser", new UserData(Set.of(new Role("role")), null, "salt", "hash"))
                    .orTimeout(5, TimeUnit.SECONDS).join().getLeft());
            Throwable cause = assertThrows(CompletionException.class, () -> ref
                    .modifyUser("user", "modifyinguser", new UserData(Set.of(new Role("role")), null, "salt", "hash"), "1")
                    .orTimeout(5, TimeUnit.SECONDS).join()).getCause();
            assertEquals(WrappingAPIException.class, cause.getClass());
            assertEquals(RefNotFoundException.class, cause.getCause().getClass());
        }
    }

    @Test
    public void testDeleteUser() throws IOException {
        try (RefHolder ref = new RefHolder(REF, source, hashService, clusterService, workStealer);) {
            ref.start();
            ref.deleteUser("user", "asUser").orTimeout(5, TimeUnit.SECONDS).join();
            verify(source).deleteUser(eq(".users/user"), eq(REF), eq("asUser"));
        }
    }

    @Test
    public void testAddUser() {
        try (RefHolder ref = new RefHolder(REF, source, hashService, clusterService, workStealer);) {
            ref.start();
            Either<String, FailedToLock> newUser = ref.addUser("blah", "root", new UserData(Set.of(), "pass", "salt", "hash")).orTimeout(5, TimeUnit.SECONDS)
                    .join();
            assertTrue(newUser.isLeft());
        }
    }

    @Test
    public void testAddKey() throws JsonParseException, JsonMappingException, IOException, RefNotFoundException {
        @SuppressWarnings("unchecked")
        ThrowingSupplier<ObjectLoader, IOException> ts = mock(Functions.ThrowingSupplier.class);
        when(source.getSourceInfo(eq("key"), eq(REF))).thenReturn(null);
        when(source.addKey(Mockito.eq("key"), anyString(), any(), any(), any())).thenReturn(Pair.of(Pair.of(ts, "one"), "two"));
        try (RefHolder ref = new RefHolder(REF, source, hashService, clusterService, workStealer);) {
            ref.start();
            Either<String, FailedToLock> newKey = ref.addKey("key", toProvider(getData()
                    .getBytes(UTF_8)), parse(getMetaData()), new CommitMetaData("user", "mail", "message", "proxyUser", "proxyUserMail"))
                    .orTimeout(5, TimeUnit.SECONDS).join();
            assertTrue(newKey.isLeft());
            assertNotNull(newKey.getLeft());
        }
    }

    @Test
    public void testDeleteKey() throws JsonParseException, JsonMappingException, IOException {
        try (RefHolder ref = new RefHolder(REF, source, hashService, clusterService, workStealer);) {
            ref.start();
            Either<String, FailedToLock> newKey = ref.deleteKey("key", new CommitMetaData("user", "mail", "message", "proxyUser", "proxyUserMail"))
                    .orTimeout(5, TimeUnit.SECONDS).join();
            assertTrue(newKey.isLeft());
            assertNotNull(newKey.getLeft());
            Mockito.verify(source).deleteKey(anyString(), any(), any());
        }
    }

    @Test
    public void testRefHolderAddKeyWhichAlreadyExist() throws JsonParseException, JsonMappingException, IOException, RefNotFoundException {
        SourceInfo sourceInfo = mock(SourceInfo.class);

        when(sourceInfo.getStreamProvider()).thenReturn(toProvider(getData().getBytes(UTF_8)));
        when(sourceInfo.readMetaData()).thenReturn(parse(getMetaData()));
        when(sourceInfo.getMetadataInputStream()).thenReturn(asStream(getMetaData()));
        when(sourceInfo.getMetaDataVersion()).thenReturn("2");
        when(sourceInfo.getSourceVersion()).thenReturn("1");
        when(source.getSourceInfo(eq("key"), eq(REF))).thenReturn(sourceInfo);
        try (RefHolder ref = new RefHolder(REF, source, hashService, clusterService, workStealer)) {
            Throwable cause = assertThrows(CompletionException.class, () -> ref
                    .addKey("key", toProvider(getData()
                            .getBytes(UTF_8)), parse(getMetaData()), new CommitMetaData("user", "mail", "message", "proxyuser", "proxymail"))
                    .orTimeout(5, TimeUnit.SECONDS).join()).getCause();
            assertEquals(WrappingAPIException.class, cause.getClass());
            assertEquals(KeyAlreadyExist.class, cause.getCause().getClass());
        }
    }

    @Test
    public void testGetAlreadyLoadedUser() throws RefNotFoundException, IOException {
        when(source.getUser(eq(".users/userName"), any())).thenReturn(Pair.of("1", new UserData(Set.of(), "pass", "salt", "hash")));
        try (RefHolder ref = new RefHolder(REF, source, hashService, clusterService, workStealer)) {
            ref.start();
            Pair<String, UserData> user = ref.getUser("userName").orTimeout(5, TimeUnit.SECONDS).join();
            assertNotNull(user);
            assertTrue(user.isPresent());
            user = ref.getUser("userName").orTimeout(5, TimeUnit.SECONDS).join();
            assertNotNull(user);
            assertTrue(user.isPresent());
        }
    }

    @Test
    public void testEnqueueAndBlock() throws InterruptedException {
        try (RefHolder ref = new RefHolder(REF, source, hashService, clusterService, workStealer)) {
            ref.start();
            AtomicBoolean b = new AtomicBoolean(true);
            CompletableFuture<Either<String, FailedToLock>> blockingOperation = ref.enqueueAndBlock(() -> {
                while (b.get());
                return null;
            }, () -> null, e -> {});
            CompletableFuture<Either<String, FailedToLock>> blocked = ref.enqueueAndBlock(() -> {
                return null;
            }, () -> null, e -> {});
            TimeUnit.SECONDS.sleep(2);
            b.set(false);
            assertTrue(blockingOperation.orTimeout(5, TimeUnit.SECONDS).join().isLeft());
            blocked.orTimeout(5, TimeUnit.SECONDS).join();
        }
    }

    @Test
    public void testEnqueueAndBlockWithFailure() throws InterruptedException {
        try (RefHolder ref = new RefHolder(REF, source, hashService, clusterService, workStealer)) {
            ref.start();
            AtomicBoolean b = new AtomicBoolean(true);
            CompletableFuture<Either<String, FailedToLock>> blockingOperation = ref.enqueueAndBlock(() -> {
                while (b.get());
                return new Exception("test");
            }, () -> null, e -> {});
            CompletableFuture<Either<String, FailedToLock>> blocked = ref.enqueueAndBlock(() -> {
                return null;
            }, () -> null, e -> {});
            TimeUnit.SECONDS.sleep(2);
            b.set(false);
            assertTrue(blockingOperation.orTimeout(5, TimeUnit.SECONDS).join().isRight());
            blocked.orTimeout(5, TimeUnit.SECONDS).join();
        }
    }

    @Test
    public void testEnqueueAndReadBlock() {
        try (RefHolder ref = new RefHolder(REF, source, hashService, clusterService, workStealer)) {
            ref.start();
            AtomicBoolean b = new AtomicBoolean(true);
            CompletableFuture<Either<Object, FailedToLock>> blocking = ref.enqueueAndReadBlock(() -> {
                while (b.get());
                return null;
            });
            CompletableFuture<Either<Object, FailedToLock>> waiting = ref.enqueueAndReadBlock(() -> {
                return null;
            });
            assertFalse(blocking.isDone(), "Blocking was done");
            assertFalse(waiting.isDone(), "Waiting was done");
            b.set(false);
            CompletableFuture.allOf(blocking, waiting).orTimeout(5, TimeUnit.SECONDS).join();
            assertTrue(blocking.isDone(), "Blocking didn't complete");
            assertTrue(waiting.isDone(), "Waiting didn't complete");
        }
    }

    private MetaData parse(String metaData) throws JsonParseException, JsonMappingException, IOException {
        return MAPPER.readValue(metaData, MetaData.class);
    }

    private ByteArrayInputStream asStream(String data) {
        return new ByteArrayInputStream(data.getBytes(UTF_8));
    }
}
