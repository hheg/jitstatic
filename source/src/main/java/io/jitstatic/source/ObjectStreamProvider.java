package io.jitstatic.source;

import java.io.ByteArrayInputStream;

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

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import org.eclipse.jgit.lib.ObjectLoader;

import io.jitstatic.utils.Functions.ThrowingSupplier;

public interface ObjectStreamProvider {

    InputStream getInputStream() throws IOException;

    long getSize();
    
    byte[] asByteArray() throws IOException;

    public default ObjectStreamProvider getObjectStreamProvider(final ThrowingSupplier<ObjectLoader, IOException> objectLoaderFactory, final int threshold) {
        final long size = getSize();
        if (size < threshold) {
            return this;
        } else {
            return new LargeObjectStreamProvider(() -> objectLoaderFactory.get().openStream(), size);
        }
    }
    
    public static ObjectStreamProvider toProvider(byte[] data) {
        return new ObjectStreamProvider() {

            @Override
            public long getSize() {
                return data.length;
            }

            @Override
            public InputStream getInputStream() throws IOException {
                return new ByteArrayInputStream(data);
            }

            @Override
            public byte[] asByteArray() {
                return Arrays.copyOf(data, data.length);
            }
        };
    }
    public static byte[] toByte(final ObjectStreamProvider provider) throws IOException {
        return provider.asByteArray();
    }
}
