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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import javax.servlet.http.HttpServletRequest;

import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Config.SectionParser;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.transport.UploadPack;
import org.eclipse.jgit.transport.UploadPackInternalServerErrorException;
import org.eclipse.jgit.transport.WantNotValidException;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;
import org.eclipse.jgit.transport.resolver.ServiceNotEnabledException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import io.jitstatic.hosted.JitStaticUploadPackFactory.ServiceConfig;

public class JitStaticUploadPackFactoryTest {

    private static final String REFS_HEADS_MASTER = "refs/heads/master";
    private static ExecutorService service = Executors.newSingleThreadExecutor();

    @Test
    public void testCreate() throws ServiceNotEnabledException, ServiceNotAuthorizedException {
        HttpServletRequest req = mock(HttpServletRequest.class);
        Repository db = mock(Repository.class);
        StoredConfig scfg = mock(StoredConfig.class);
        Config cfg = mock(Config.class);
        RefLockHolderManager rhm = mock(RefLockHolderManager.class);
        when(db.getConfig()).thenReturn(scfg);
        // This is sneaky
        @SuppressWarnings("unchecked")
        ArgumentCaptor<SectionParser<ServiceConfig>> argcaptor = ArgumentCaptor.forClass(SectionParser.class);
        when(cfg.getBoolean(Mockito.anyString(), Mockito.anyString(), Mockito.anyBoolean())).thenReturn(true);
        when(scfg.get(argcaptor.capture())).thenAnswer((i) -> argcaptor.getValue().parse(cfg));

        JitStaticUploadPackFactory jsrpf = new JitStaticUploadPackFactory(service, rhm, REFS_HEADS_MASTER);
        assertNotNull(jsrpf.create(req, db));
    }

    @Test
    public void testServiceNotEnabledException() throws ServiceNotEnabledException, ServiceNotAuthorizedException {
        HttpServletRequest req = mock(HttpServletRequest.class);
        Repository db = mock(Repository.class);
        StoredConfig scfg = mock(StoredConfig.class);
        Config cfg = mock(Config.class);
        RefLockHolderManager rhm = mock(RefLockHolderManager.class);
        when(db.getConfig()).thenReturn(scfg);
        // This is sneaky
        @SuppressWarnings("unchecked")
        ArgumentCaptor<SectionParser<ServiceConfig>> argcaptor = ArgumentCaptor.forClass(SectionParser.class);
        when(cfg.getBoolean(Mockito.anyString(), Mockito.anyString(), Mockito.anyBoolean())).thenReturn(false);
        when(scfg.get(argcaptor.capture())).thenAnswer((i) -> argcaptor.getValue().parse(cfg));

        JitStaticUploadPackFactory jsrpf = new JitStaticUploadPackFactory(service, rhm, REFS_HEADS_MASTER);
        assertThrows(ServiceNotEnabledException.class, () -> jsrpf.create(req, db));
    }

    @Test
    public void testSecretsFilter() throws ServiceNotEnabledException, ServiceNotAuthorizedException {
        HttpServletRequest req = mock(HttpServletRequest.class);
        Repository db = mock(Repository.class);
        StoredConfig scfg = mock(StoredConfig.class);
        Config cfg = mock(Config.class);
        Ref secrets = mock(Ref.class);
        Ref head = mock(Ref.class);
        RefLockHolderManager rhm = mock(RefLockHolderManager.class);
        ObjectId oid = ObjectId.fromString("5f12e3846fef8c259efede1a55e12667effcc461");
        when(secrets.getObjectId()).thenReturn(oid);
        when(head.getObjectId()).thenReturn(oid);
        when(db.getConfig()).thenReturn(scfg);
        // This is sneaky
        @SuppressWarnings("unchecked")
        ArgumentCaptor<SectionParser<ServiceConfig>> argcaptor = ArgumentCaptor.forClass(SectionParser.class);
        when(cfg.getBoolean(Mockito.anyString(), Mockito.anyString(), Mockito.anyBoolean())).thenReturn(true);
        when(scfg.get(argcaptor.capture())).thenAnswer((i) -> argcaptor.getValue().parse(cfg));

        JitStaticUploadPackFactory jsrpf = new JitStaticUploadPackFactory(service, rhm, REFS_HEADS_MASTER);
        UploadPack up = jsrpf.create(req, db);
        Map<String, Ref> map = new HashMap<>();
        map.put("refs/heads/secrets", secrets);
        map.put("HEAD", head);
        Map<String, Ref> filtered = up.getRefFilter().filter(map);
        assertTrue(filtered.isEmpty());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testUploadThrowingException() throws ServiceNotEnabledException, ServiceNotAuthorizedException, IOException {
        HttpServletRequest req = mock(HttpServletRequest.class);
        Repository db = mock(Repository.class);
        StoredConfig scfg = mock(StoredConfig.class);
        Config cfg = mock(Config.class);
        Ref secrets = mock(Ref.class);
        Ref head = mock(Ref.class);
        RefLockHolder rh = mock(RefLockHolder.class);
        RefLockHolderManager rhm = mock(RefLockHolderManager.class);
        when(rhm.getRefHolder(Mockito.anyString())).thenReturn(rh);
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Supplier> supplierCaptor = ArgumentCaptor.forClass(Supplier.class);
        when(rh.enqueueAndReadBlock(supplierCaptor.capture())).then((i) -> {
            try {
                return CompletableFuture.completedFuture(supplierCaptor.getValue().get());
            } catch (Throwable t) {
                return CompletableFuture.failedFuture(t);
            }
        });

        ObjectId oid = ObjectId.fromString("5f12e3846fef8c259efede1a55e12667effcc461");
        when(db.newObjectReader()).thenReturn(mock(ObjectReader.class));
        when(secrets.getObjectId()).thenReturn(oid);
        when(head.getObjectId()).thenReturn(oid);
        when(db.getConfig()).thenReturn(scfg);
        // This is sneaky
        ArgumentCaptor<SectionParser<ServiceConfig>> argcaptor = ArgumentCaptor.forClass(SectionParser.class);
        when(cfg.getBoolean(Mockito.anyString(), Mockito.anyString(), Mockito.anyBoolean())).thenReturn(true);
        when(scfg.get(argcaptor.capture())).thenAnswer((i) -> argcaptor.getValue().parse(cfg));

        JitStaticUploadPackFactory jsrpf = new JitStaticUploadPackFactory(service, rhm, REFS_HEADS_MASTER);
        UploadPack up = jsrpf.create(req, db);
        InputStream input = Mockito.mock(InputStream.class);
        OutputStream output = Mockito.mock(OutputStream.class);
        OutputStream messages = Mockito.mock(OutputStream.class);
        Mockito.doThrow(new UploadPackInternalServerErrorException(new Exception("test"))).when(output).write(Mockito.any(), Mockito.anyInt(),
                Mockito.anyInt());
        assertThrows(UploadPackInternalServerErrorException.class, () -> up.upload(input, output, messages));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testUploadThrowingWantNotValidException() throws ServiceNotEnabledException, ServiceNotAuthorizedException, IOException {
        HttpServletRequest req = mock(HttpServletRequest.class);
        Repository db = mock(Repository.class);
        when(db.newObjectReader()).thenReturn(mock(ObjectReader.class));
        StoredConfig scfg = mock(StoredConfig.class);
        Config cfg = mock(Config.class);
        Ref secrets = mock(Ref.class);
        Ref head = mock(Ref.class);
        RefLockHolder rh = mock(RefLockHolder.class);
        RefLockHolderManager rhm = mock(RefLockHolderManager.class);
        when(rhm.getRefHolder(Mockito.anyString())).thenReturn(rh);
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Supplier> supplierCaptor = ArgumentCaptor.forClass(Supplier.class);
        when(rh.enqueueAndReadBlock(supplierCaptor.capture())).then((i) -> {
            try {
                return CompletableFuture.completedFuture(supplierCaptor.getValue().get());
            } catch (Throwable t) {
                return CompletableFuture.failedFuture(t);
            }
        });
        ObjectId oid = ObjectId.fromString("5f12e3846fef8c259efede1a55e12667effcc461");
        when(secrets.getObjectId()).thenReturn(oid);
        when(head.getObjectId()).thenReturn(oid);
        when(db.getConfig()).thenReturn(scfg);
        // This is sneaky
        ArgumentCaptor<SectionParser<ServiceConfig>> argcaptor = ArgumentCaptor.forClass(SectionParser.class);
        when(cfg.getBoolean(Mockito.anyString(), Mockito.anyString(), Mockito.anyBoolean())).thenReturn(true);
        when(scfg.get(argcaptor.capture())).thenAnswer((i) -> argcaptor.getValue().parse(cfg));

        JitStaticUploadPackFactory jsrpf = new JitStaticUploadPackFactory(service, rhm, REFS_HEADS_MASTER);
        UploadPack up = jsrpf.create(req, db);
        InputStream input = Mockito.mock(InputStream.class);
        OutputStream output = Mockito.mock(OutputStream.class);
        OutputStream messages = Mockito.mock(OutputStream.class);
        Mockito.doThrow(new UploadPackInternalServerErrorException(new WantNotValidException(ObjectId.zeroId()))).when(output).write(Mockito.any(),
                Mockito.anyInt(),
                Mockito.anyInt());
        up.upload(input, output, messages);
        Mockito.verify(output).write(Mockito.any(), Mockito.anyInt(), Mockito.anyInt());
    }

    @AfterAll
    public static void tearDown() {
        try {
            service.shutdown();
            service.awaitTermination(10, TimeUnit.SECONDS);
        } catch (Exception ignore) {
            // ignore
        }
    }
}
