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
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Config.SectionParser;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.transport.UploadPack;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;
import org.eclipse.jgit.transport.resolver.ServiceNotEnabledException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import io.jitstatic.hosted.JitStaticUploadPackFactory.ServiceConfig;

public class JitStaticUploadPackFactoryTest {

    @Test
    public void testCreate() throws ServiceNotEnabledException, ServiceNotAuthorizedException {
        ErrorReporter reporter = new ErrorReporter();
        HttpServletRequest req = mock(HttpServletRequest.class);
        Repository db = mock(Repository.class);
        StoredConfig scfg = mock(StoredConfig.class);
        Config cfg = mock(Config.class);
        when(db.getConfig()).thenReturn(scfg);
        // This is sneaky
        @SuppressWarnings("unchecked")
        ArgumentCaptor<SectionParser<ServiceConfig>> argcaptor = ArgumentCaptor.forClass(SectionParser.class);
        when(cfg.getBoolean(Mockito.anyString(), Mockito.anyString(), Mockito.anyBoolean())).thenReturn(true);
        when(scfg.get(argcaptor.capture())).thenAnswer((i) -> argcaptor.getValue().parse(cfg));

        JitStaticUploadPackFactory jsrpf = new JitStaticUploadPackFactory(reporter);
        assertNotNull(jsrpf.create(req, db));
    }

    @Test
    public void testServiceNotEnabledException() throws ServiceNotEnabledException, ServiceNotAuthorizedException {
        ErrorReporter reporter = new ErrorReporter();
        HttpServletRequest req = mock(HttpServletRequest.class);
        Repository db = mock(Repository.class);
        StoredConfig scfg = mock(StoredConfig.class);
        Config cfg = mock(Config.class);
        when(db.getConfig()).thenReturn(scfg);
        // This is sneaky
        @SuppressWarnings("unchecked")
        ArgumentCaptor<SectionParser<ServiceConfig>> argcaptor = ArgumentCaptor.forClass(SectionParser.class);
        when(cfg.getBoolean(Mockito.anyString(), Mockito.anyString(), Mockito.anyBoolean())).thenReturn(false);
        when(scfg.get(argcaptor.capture())).thenAnswer((i) -> argcaptor.getValue().parse(cfg));

        JitStaticUploadPackFactory jsrpf = new JitStaticUploadPackFactory(reporter);
        assertThrows(ServiceNotEnabledException.class, () -> jsrpf.create(req, db));
    }

    @Test
    public void testSecretsFilter() throws ServiceNotEnabledException, ServiceNotAuthorizedException {
        ErrorReporter reporter = new ErrorReporter();
        HttpServletRequest req = mock(HttpServletRequest.class);
        Repository db = mock(Repository.class);
        StoredConfig scfg = mock(StoredConfig.class);
        Config cfg = mock(Config.class);
        Ref secrets = mock(Ref.class);
        Ref head = mock(Ref.class);
        ObjectId oid = ObjectId.fromString("5f12e3846fef8c259efede1a55e12667effcc461");
        when(secrets.getObjectId()).thenReturn(oid);
        when(head.getObjectId()).thenReturn(oid);
        when(db.getConfig()).thenReturn(scfg);
        // This is sneaky
        @SuppressWarnings("unchecked")
        ArgumentCaptor<SectionParser<ServiceConfig>> argcaptor = ArgumentCaptor.forClass(SectionParser.class);
        when(cfg.getBoolean(Mockito.anyString(), Mockito.anyString(), Mockito.anyBoolean())).thenReturn(true);
        when(scfg.get(argcaptor.capture())).thenAnswer((i) -> argcaptor.getValue().parse(cfg));

        JitStaticUploadPackFactory jsrpf = new JitStaticUploadPackFactory(reporter);
        UploadPack up = jsrpf.create(req, db);
        Map<String, Ref> map = new HashMap<>();
        map.put("refs/heads/secrets", secrets);
        map.put("HEAD", head);
        Map<String, Ref> filtered = up.getRefFilter().filter(map);
        assertTrue(filtered.isEmpty());
    }
}
