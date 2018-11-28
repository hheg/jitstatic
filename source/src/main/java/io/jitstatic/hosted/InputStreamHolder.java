package io.jitstatic.hosted;

/*-
 * #%L
 * jitstatic
 * %%
 * Copyright (C) 2017 H.Hegardt
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
import java.util.NoSuchElementException;

import org.eclipse.jgit.lib.ObjectLoader;

import io.jitstatic.utils.Functions.ThrowingSupplier;

public class InputStreamHolder {
    private final ThrowingSupplier<ObjectLoader, IOException> loaderFactory;
    private final Exception e;

    public InputStreamHolder(final ThrowingSupplier<ObjectLoader, IOException> loaderFactory) {
        this(loaderFactory, null);
    }

    private InputStreamHolder(final ThrowingSupplier<ObjectLoader, IOException> loaderFactory, final Exception e) {
        this.loaderFactory = loaderFactory;
        this.e = e;
    }

    public InputStreamHolder(final Exception e) {
        this(null, e);
    }

    public boolean isPresent() {
        return loaderFactory != null;
    }
    @Deprecated
    public InputStream inputStream() throws IOException {
        if (isPresent()) {
            return loaderFactory.get().openStream();
        }
        throw new NoSuchElementException();
    }

    public Exception exception() {
        if (!isPresent()) {
            return e;
        }
        throw new NoSuchElementException();
    }

    public long getSize() throws IOException {
        if (isPresent()) {
            return loaderFactory.get().getSize();
        }
        throw new NoSuchElementException();
    }

    public ThrowingSupplier<InputStream, IOException> getInputStreamProvider() {
        return () -> inputStream();
    }

}