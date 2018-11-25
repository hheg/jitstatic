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

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import io.jitstatic.utils.Functions.ThrowingSupplier;

public class LargeObjectStreamProvider implements ObjectStreamProvider {

    private final ThrowingSupplier<InputStream, IOException> inputStreamProvider;
    private final long size;

    public LargeObjectStreamProvider(final ThrowingSupplier<InputStream, IOException> inputStreamProvider, final long size) {
        this.inputStreamProvider = Objects.requireNonNull(inputStreamProvider);
        this.size = size;
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return inputStreamProvider.get();
    }

    @Override
    public long getSize() throws IOException {
        return size;
    }

}
