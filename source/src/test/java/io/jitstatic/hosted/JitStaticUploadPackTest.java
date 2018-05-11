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

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.concurrent.Executor;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.transport.RefFilter;
import org.eclipse.jgit.transport.TransferConfig;
import org.eclipse.jgit.transport.UploadPackInternalServerErrorException;
import org.eclipse.jgit.transport.WantNotValidException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class JitStaticUploadPackTest {

    @Test
    public void testUploadPack() throws IOException {
        Executor service = new PriorityExecutor();
        Repository copyFrom = mock(Repository.class);
        ObjectReader or = mock(ObjectReader.class);
        RefFilter rf = mock(RefFilter.class);
        TransferConfig tc = mock(TransferConfig.class);
        StoredConfig sc = mock(StoredConfig.class);
        when(tc.getRefFilter()).thenReturn(rf);
        when(copyFrom.newObjectReader()).thenReturn(or);
        when(copyFrom.getConfig()).thenReturn(sc);

        InputStream is = new ByteArrayInputStream(new byte[] { 1 });
        OutputStream os = new ByteArrayOutputStream();
        OutputStream messages = new ByteArrayOutputStream();
        ErrorReporter errorReporter = new ErrorReporter();
        JitStaticUploadPack up = new JitStaticUploadPack(copyFrom, service, errorReporter);
        up.setTransferConfig(tc);
        up.setAdvertisedRefs(new HashMap<>());
        up.upload(is, os, messages);
        assertNull(errorReporter.getFault());
    }

    @Test
    public void testUploadFailsWithStaleBaseline() throws IOException {
        Executor service = new PriorityExecutor();
        Repository copyFrom = mock(Repository.class);
        ObjectReader or = mock(ObjectReader.class);
        RefFilter rf = mock(RefFilter.class);
        TransferConfig tc = mock(TransferConfig.class);
        StoredConfig sc = mock(StoredConfig.class);
        when(tc.getRefFilter()).thenReturn(rf);
        when(copyFrom.newObjectReader()).thenReturn(or);
        when(copyFrom.getConfig()).thenReturn(sc);

        InputStream is = new ByteArrayInputStream(new byte[] { 1 });
        OutputStream os = mock(OutputStream.class);
        OutputStream messages = mock(OutputStream.class);
        Mockito.doThrow(new WantNotValidException(ObjectId.zeroId())).when(os).write(Mockito.any(), Mockito.anyInt(), Mockito.anyInt());
        ErrorReporter errorReporter = new ErrorReporter();
        JitStaticUploadPack up = new JitStaticUploadPack(copyFrom, service, errorReporter);
        up.setBiDirectionalPipe(true);
        up.setTransferConfig(tc);
        up.setAdvertisedRefs(new HashMap<>());
        assertThrows(WantNotValidException.class, () -> up.upload(is, os, messages));
        assertNull(errorReporter.getFault());
    }

    @Test
    public void testUploadFailsWithUnknownError() throws IOException {
        Executor service = new PriorityExecutor();
        Repository copyFrom = mock(Repository.class);
        ObjectReader or = mock(ObjectReader.class);
        RefFilter rf = mock(RefFilter.class);
        TransferConfig tc = mock(TransferConfig.class);
        StoredConfig sc = mock(StoredConfig.class);
        when(tc.getRefFilter()).thenReturn(rf);
        when(copyFrom.newObjectReader()).thenReturn(or);
        when(copyFrom.getConfig()).thenReturn(sc);

        InputStream is = new ByteArrayInputStream(new byte[] { 1 });
        OutputStream os = mock(OutputStream.class);
        OutputStream messages = mock(OutputStream.class);
        Mockito.doThrow(new UploadPackInternalServerErrorException(new RuntimeException())).when(os).write(Mockito.any(), Mockito.anyInt(),
                Mockito.anyInt());
        ErrorReporter errorReporter = new ErrorReporter();
        JitStaticUploadPack up = new JitStaticUploadPack(copyFrom, service, errorReporter);
        up.setBiDirectionalPipe(true);
        up.setTransferConfig(tc);
        up.setAdvertisedRefs(new HashMap<>());
        assertThrows(UploadPackInternalServerErrorException.class, () -> up.upload(is, os, messages));
        assertNull(errorReporter.getFault());
    }
}
