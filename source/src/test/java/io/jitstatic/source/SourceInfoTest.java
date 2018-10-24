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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.InputStream;

import org.eclipse.jgit.lib.ObjectId;
import org.hamcrest.CoreMatchers;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.jitstatic.MetaData;
import io.jitstatic.check.FileObjectIdStore;
import io.jitstatic.check.MetaFileData;
import io.jitstatic.check.SourceFileData;
import io.jitstatic.hosted.InputStreamHolder;

public class SourceInfoTest {

    private static final String SHA_1 = "5f12e3846fef8c259efede1a55e12667effcc461";

    @Test
    public void testSourceInfo() throws IOException {
        InputStream is = Mockito.mock(InputStream.class);

        FileObjectIdStore fois = Mockito.mock(FileObjectIdStore.class);
        InputStreamHolder ish = Mockito.mock(InputStreamHolder.class);
        FileObjectIdStore fois2 = Mockito.mock(FileObjectIdStore.class);
        InputStreamHolder ish2 = Mockito.mock(InputStreamHolder.class);

        Mockito.when(ish.inputStream()).thenReturn(is);
        Mockito.when(fois.getObjectId()).thenReturn(ObjectId.fromString(SHA_1));
        Mockito.when(ish.isPresent()).thenReturn(true);
        SourceFileData sdf = new SourceFileData(fois, ish);
        MetaFileData mfd = new MetaFileData(fois2, ish2);
        SourceInfo si = new SourceInfo(mfd, sdf);
        assertEquals(is, si.getSourceInputStream());
        assertEquals(SHA_1, si.getSourceVersion());
    }

    @Test
    public void testSourceInfoWithNoInputStream() throws IOException {
        assertThat((IOException) assertThrows(RuntimeException.class, () -> {
            FileObjectIdStore fois = Mockito.mock(FileObjectIdStore.class);
            InputStreamHolder ish = Mockito.mock(InputStreamHolder.class);
            FileObjectIdStore fois2 = Mockito.mock(FileObjectIdStore.class);
            InputStreamHolder ish2 = Mockito.mock(InputStreamHolder.class);
            Mockito.when(ish.exception()).thenReturn(new IOException("Fake IO"));
            Mockito.when(ish.isPresent()).thenReturn(false);
            SourceFileData sdf = new SourceFileData(fois, ish);
            MetaFileData mfd = new MetaFileData(fois2, ish2);
            SourceInfo si = new SourceInfo(mfd, sdf);
            si.getSourceInputStream();
        }).getCause(), CoreMatchers.isA(IOException.class));
    }

    @Test
    public void testSourceInfoWithFailingReadingInputstream() throws IOException {
        assertThat(assertThrows(IOException.class, () -> {
            FileObjectIdStore fois = Mockito.mock(FileObjectIdStore.class);

            InputStreamHolder ish = Mockito.mock(InputStreamHolder.class);
            FileObjectIdStore fois2 = Mockito.mock(FileObjectIdStore.class);
            InputStreamHolder ish2 = Mockito.mock(InputStreamHolder.class);

            Mockito.when(ish.inputStream()).thenThrow(new IOException("Fake IO"));
            Mockito.when(ish.isPresent()).thenReturn(true);
            SourceFileData sdf = new SourceFileData(fois, ish);
            MetaFileData mfd = new MetaFileData(fois2, ish2);
            SourceInfo si = new SourceInfo(mfd, sdf);
            si.getSourceInputStream();
        }).getLocalizedMessage(), CoreMatchers.containsString("Error reading null"));
    }

    @Test
    public void testSourceInfoHasFailed() throws IOException {
        FileObjectIdStore fois = Mockito.mock(FileObjectIdStore.class);
        InputStreamHolder ish = Mockito.mock(InputStreamHolder.class);
        FileObjectIdStore fois2 = Mockito.mock(FileObjectIdStore.class);
        InputStreamHolder ish2 = Mockito.mock(InputStreamHolder.class);

        Mockito.when(ish.exception()).thenReturn(new IOException("Fake IO"));
        SourceFileData sdf = new SourceFileData(fois, ish);
        MetaFileData mfd = new MetaFileData(fois2, ish2);
        SourceInfo si = new SourceInfo(mfd, sdf);
        assertThrows(RuntimeException.class, () -> si.getSourceInputStream());
    }

    @Test
    public void testSourceInfoHasException() throws IOException {
        FileObjectIdStore fois = Mockito.mock(FileObjectIdStore.class);
        InputStreamHolder ish = Mockito.mock(InputStreamHolder.class);
        FileObjectIdStore fois2 = Mockito.mock(FileObjectIdStore.class);
        InputStreamHolder ish2 = Mockito.mock(InputStreamHolder.class);
        IOException ioException = new IOException("Fake IO");
        Mockito.when(ish.exception()).thenReturn(ioException);
        SourceFileData sdf = new SourceFileData(fois, ish);
        MetaFileData mfd = new MetaFileData(fois2, ish2);
        SourceInfo si = new SourceInfo(mfd, sdf);
        assertThrows(RuntimeException.class, () -> si.getSourceInputStream());
    }

    @Test
    public void testNotMasterdata() {
        MetaFileData meta = Mockito.mock(MetaFileData.class);
        Mockito.when(meta.isMasterMetaData()).thenReturn(false).thenReturn(true);
        assertThrows(IllegalArgumentException.class, () -> new SourceInfo(meta, null));
        assertNull(new SourceInfo(meta, null).getSourceVersion());
    }
}
