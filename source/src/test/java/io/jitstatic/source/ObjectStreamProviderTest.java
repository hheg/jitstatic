package io.jitstatic.source;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectStream;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ObjectStreamProviderTest {

    @Test
    void testGetLargeObjectStreamProvider() throws IOException {
        ObjectLoader ol = Mockito.mock(ObjectLoader.class);
        ObjectStream mock = Mockito.mock(ObjectStream.class);
        when(ol.openStream()).thenReturn(mock);
        ObjectStreamProvider osp = new ObjectStreamProvider() {
            @Override
            public long getSize() throws IOException {
                return 2;
            }

            @Override
            public InputStream getInputStream() throws IOException {
                return new ByteArrayInputStream(new byte[] { 1 });
            }
        };
        ObjectStreamProvider objectStreamProvider = osp.getObjectStreamProvider(() -> ol, 1);
        assertEquals(LargeObjectStreamProvider.class, objectStreamProvider.getClass());
        assertSame(mock, objectStreamProvider.getInputStream());
    }

    @Test
    void testGetSmallObjectStreamProvider() throws IOException {
        ObjectLoader ol = Mockito.mock(ObjectLoader.class);
        ObjectStreamProvider osp = new ObjectStreamProvider() {
            @Override
            public long getSize() throws IOException {
                return 1;
            }

            @Override
            public InputStream getInputStream() throws IOException {
                return new ByteArrayInputStream(new byte[] { 1 });
            }
        };
        assertEquals(osp.getClass(), osp.getObjectStreamProvider(() -> ol, 2).getClass());
    }
}
