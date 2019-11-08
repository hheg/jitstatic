package io.jitstatic.storage.ref;

/*-
 * #%L
 * jitstatic
 * %%
 * Copyright (C) 2017 - 2019 H.Hegardt
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
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.lib.ObjectLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.jitstatic.CommitMetaData;
import io.jitstatic.MetaData;
import io.jitstatic.Role;
import io.jitstatic.auth.UserData;
import io.jitstatic.hosted.LoadException;
import io.jitstatic.hosted.StoreInfo;
import io.jitstatic.source.Source;
import io.jitstatic.source.SourceInfo;
import io.jitstatic.storage.HashService;
import io.jitstatic.storage.KeyAlreadyExist;
import io.jitstatic.storage.NamingThreadFactory;
import io.jitstatic.test.BaseTest;
import io.jitstatic.utils.Functions;
import io.jitstatic.utils.Functions.ThrowingSupplier;
import io.jitstatic.utils.Pair;
import io.jitstatic.utils.VersionIsNotSame;
import io.jitstatic.utils.WrappingAPIException;

class LockServiceImplTest extends BaseTest {

    private static final String REF = "refs/heads/master";
    private Source source;
    private HashService hashService = new HashService();
    private LocalRefLockService clusterService;
    private ExecutorService workStealer;
    private LockService lock;

    private ExecutorService repoWriter;

    @BeforeEach
    public void setup() {
        source = mock(Source.class);
        workStealer = Executors.newWorkStealingPool();
        repoWriter = Executors.newSingleThreadExecutor(new NamingThreadFactory("test-repowriter"));
        clusterService = mock(LocalRefLockService.class);
        lock = mock(LockService.class);
        when(clusterService.getLockService(REF, workStealer, source, hashService)).thenReturn(lock);
    }

    @AfterEach
    void tearDown() throws Exception {
        shutdownExecutor(workStealer);
        shutdownExecutor(repoWriter);
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
        try (LockServiceImpl lock = new LockServiceImpl(clusterService, REF, workStealer, source, hashService, repoWriter)) {
            assertTrue(lock.readKey("key").isPresent());
        }
    }

    @Test
    public void testLoadAndStoreRefNotFound() throws IOException, RefNotFoundException {
        when(source.getSourceInfo(eq("key"), eq(REF))).thenThrow(new RefNotFoundException(REF));
        try (LockServiceImpl lock = new LockServiceImpl(clusterService, REF, workStealer, source, hashService, repoWriter)) {
            assertThrows(LoadException.class, () -> lock.readKey("key"));
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
        try (LockServiceImpl lock = new LockServiceImpl(clusterService, REF, workStealer, source, hashService, repoWriter)) {
            Optional<StoreInfo> loadAndStore = lock.readKey("key");
            assertEquals(lock.readKey("key"), loadAndStore);
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
        try (LockServiceImpl lock = new LockServiceImpl(clusterService, REF, workStealer, source, hashService, repoWriter)) {
            Optional<StoreInfo> loadAndStore = lock.readKey("key/");
            assertEquals(lock.readKey("key/"), loadAndStore);
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
        try (LockServiceImpl lock = new LockServiceImpl(clusterService, REF, workStealer, source, hashService, repoWriter)) {
            assertSame(ioException, assertThrows(UncheckedIOException.class, () -> lock.readKey("key")).getCause());
        }
    }
    

    @Test
    public void testCheckIfPlainKeyDoesNotExist() {
        try (LockServiceImpl lock = new LockServiceImpl(clusterService, REF, workStealer, source, hashService, repoWriter)) {
            lock.putKey("key", Optional.empty());
            lock.checkIfPlainKeyExist("key/");
        }
    }

    @Test
    public void testCheckIfPlainKeyDoesNotExistNull() {
        try (LockServiceImpl lock = new LockServiceImpl(clusterService, REF, workStealer, source, hashService, repoWriter)) {
            lock.checkIfPlainKeyExist("key/");
        }
    }

    @Test
    public void testCheckIfPlainKeyExist() {
        StoreInfo storeInfo = mock(StoreInfo.class);
        try (LockServiceImpl lock = new LockServiceImpl(clusterService, REF, workStealer, source, hashService, repoWriter)) {
            lock.putKey("key", Optional.of(storeInfo));
            assertThrows(WrappingAPIException.class, () -> lock.checkIfPlainKeyExist("key/"));
        }
    }
    
    @Test
    public void testCheckIfPlainKeyDoesExist() {
        try (LockServiceImpl lock = new LockServiceImpl(clusterService, REF, workStealer, source, hashService, repoWriter)) {
            StoreInfo si = Mockito.mock(StoreInfo.class);
            lock.putKey("key", Optional.of(si));
            assertEquals(KeyAlreadyExist.class, assertThrows(WrappingAPIException.class, () -> lock.checkIfPlainKeyExist("key/")).getCause().getClass());
        }
    }
    
    private ByteArrayInputStream asStream(String data) {
        return new ByteArrayInputStream(data.getBytes(UTF_8));
    }

}
