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

import static io.jitstatic.source.ObjectStreamProvider.toProvider;
import static java.nio.charset.StandardCharsets.UTF_8;
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
import java.util.concurrent.TimeUnit;

import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.lib.ObjectLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.codahale.metrics.MetricRegistry;
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
    private RefLockService clusterService;
    private ExecutorService workStealer;

    @BeforeEach
    public void setup() {
        source = mock(Source.class);
        workStealer = mock(ExecutorService.class);
        clusterService = new LocalRefLockService(new MetricRegistry());
    }

    @AfterEach
    public void tearDown() throws Exception {
        clusterService.close();
    }

    // TODO Fix this with a better name and check that the logic holds
    @Test
    public void testPutGet() {
        try (RefHolder ref = new RefHolder(REF, source, hashService, clusterService, workStealer);) {
            ref.start();
            ref.putKey("key", Optional.empty());
            assertNotNull(ref.readKey("key"));
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
            ref.putKey("key", Optional.of(storeInfo));
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
            ref.putKey("key", Optional.of(storeInfo));
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
    public void testCheckIfPlainKeyDoesNotExist() {
        try (RefHolder ref = new RefHolder(REF, source, hashService, clusterService, workStealer);) {
            ref.start();
            ref.putKey("key", Optional.empty());
            ref.checkIfPlainKeyExist("key/");
        }
    }

    @Test
    public void testCheckIfPlainKeyDoesNotExistNull() {
        try (RefHolder ref = new RefHolder(REF, source, hashService, clusterService, workStealer);) {
            ref.start();
            ref.checkIfPlainKeyExist("key/");
        }
    }

    @Test
    public void testCheckIfPlainKeyExist() {
        StoreInfo storeInfo = mock(StoreInfo.class);
        try (RefHolder ref = new RefHolder(REF, source, hashService, clusterService, workStealer);) {
            ref.start();
            ref.putKey("key", Optional.of(storeInfo));
            assertThrows(WrappingAPIException.class, () -> ref.checkIfPlainKeyExist("key/"));
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
    public void testCheckIfPlainKeyDoesExist() {
        try (RefHolder ref = new RefHolder(REF, source, hashService, clusterService, workStealer);) {
            ref.start();
            StoreInfo si = Mockito.mock(StoreInfo.class);
            ref.putKey("key", Optional.of(si));
            assertEquals(KeyAlreadyExist.class, assertThrows(WrappingAPIException.class, () -> ref.checkIfPlainKeyExist("key/")).getCause().getClass());
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

    private ByteArrayInputStream asStream(String data) {
        return new ByteArrayInputStream(data.getBytes(UTF_8));
    }

    @Override
    protected File getFolderFile() throws IOException { return null; }

}
