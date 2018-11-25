package io.jitstatic.source;

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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.Test;

import io.jitstatic.check.FileObjectIdStore;
import io.jitstatic.check.MetaFileData;
import io.jitstatic.check.SourceFileData;
import io.jitstatic.hosted.InputStreamHolder;

public class SourceInfoTest {

    private static final String SHA_1 = "5f12e3846fef8c259efede1a55e12667effcc461";

    @Test
    public void testSourceInfo() throws IOException {
        FileObjectIdStore fois = mock(FileObjectIdStore.class);
        InputStreamHolder ish = mock(InputStreamHolder.class);
        FileObjectIdStore fois2 = mock(FileObjectIdStore.class);
        InputStreamHolder ish2 = mock(InputStreamHolder.class);
        when(fois.getObjectId()).thenReturn(ObjectId.fromString(SHA_1));
        when(ish.isPresent()).thenReturn(true);
        when(ish.getInputStreamProvider()).thenReturn(() -> new ByteArrayInputStream(new byte[] { 1 }));
        SourceFileData sdf = new SourceFileData(fois, ish);
        MetaFileData mfd = new MetaFileData(fois2, ish2);
        SourceInfo si = new SourceInfo(mfd, sdf);
        assertArrayEquals(toByteArray(new ByteArrayInputStream(new byte[] { 1 })), toByteArray(si.getSourceProvider().getInputStream()));
        assertEquals(SHA_1, si.getSourceVersion());
    }
    
    private byte[] toByteArray(InputStream is) throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        is.transferTo(byteArrayOutputStream);
        return byteArrayOutputStream.toByteArray();
    }

    @Test
    public void testSourceInfoLargeObjectStreamProvider() throws IOException {
        MetaFileData metaFileData = mock(MetaFileData.class);
        SourceFileData sourceFileData = mock(SourceFileData.class);
        InputStreamHolder inputStreamHolder = mock(InputStreamHolder.class);
        when(inputStreamHolder.getSize()).thenReturn(1_000_000L);
        when(inputStreamHolder.getInputStreamProvider()).thenReturn(() -> new ByteArrayInputStream(new byte[] { 1 }));
        when(sourceFileData.getInputStreamHolder()).thenReturn(inputStreamHolder);
        SourceInfo si = new SourceInfo(metaFileData, sourceFileData);
        ObjectStreamProvider sourceProvider = si.getSourceProvider();
        assertTrue(sourceProvider instanceof LargeObjectStreamProvider);
        assertTrue(sourceProvider.getSize() == 1_000_000L);
    }

    @Test
    public void testSourceInfoSmallObjectStreamProvider() throws IOException {
        MetaFileData metaFileData = mock(MetaFileData.class);
        SourceFileData sourceFileData = mock(SourceFileData.class);
        InputStreamHolder inputStreamHolder = mock(InputStreamHolder.class);
        when(inputStreamHolder.getInputStreamProvider()).thenReturn(() -> new ByteArrayInputStream(new byte[] { 1 }));
        when(sourceFileData.getInputStreamHolder()).thenReturn(inputStreamHolder);
        SourceInfo si = new SourceInfo(metaFileData, sourceFileData);
        ObjectStreamProvider sourceProvider = si.getSourceProvider();
        assertTrue(sourceProvider instanceof SmallObjectStreamProvider);
        assertTrue(sourceProvider.getSize() == 1L);
    }

    @Test
    public void testSourceInfoHasFailed() throws IOException {
        FileObjectIdStore fois = mock(FileObjectIdStore.class);
        InputStreamHolder ish = mock(InputStreamHolder.class);
        FileObjectIdStore fois2 = mock(FileObjectIdStore.class);
        InputStreamHolder ish2 = mock(InputStreamHolder.class);

        when(ish.exception()).thenReturn(new IOException("Fake IO"));
        SourceFileData sdf = new SourceFileData(fois, ish);
        MetaFileData mfd = new MetaFileData(fois2, ish2);
        SourceInfo si = new SourceInfo(mfd, sdf);
        assertThrows(RuntimeException.class, () -> si.getSourceProvider().getInputStream());
    }

    @Test
    public void testSourceInfoHasException() throws IOException {
        FileObjectIdStore fois = mock(FileObjectIdStore.class);
        InputStreamHolder ish = mock(InputStreamHolder.class);
        FileObjectIdStore fois2 = mock(FileObjectIdStore.class);
        InputStreamHolder ish2 = mock(InputStreamHolder.class);
        IOException ioException = new IOException("Fake IO");
        when(ish.exception()).thenReturn(ioException);
        SourceFileData sdf = new SourceFileData(fois, ish);
        MetaFileData mfd = new MetaFileData(fois2, ish2);
        SourceInfo si = new SourceInfo(mfd, sdf);
        assertThrows(RuntimeException.class, () -> si.getSourceProvider().getInputStream());
    }

    @Test
    public void testNotMasterdata() {
        MetaFileData meta = mock(MetaFileData.class);
        when(meta.isMasterMetaData()).thenReturn(false).thenReturn(true);
        assertThrows(IllegalArgumentException.class, () -> new SourceInfo(meta, null));
        assertNull(new SourceInfo(meta, null).getSourceVersion());
    }
}
