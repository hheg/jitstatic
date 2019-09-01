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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Objects;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

@SuppressFBWarnings(value = { "EI_EXPOSE_REP", "EI_EXPOSE_REP2" }, justification = "Want to avoid copying the array twice")
public class SmallObjectStreamProvider implements ObjectStreamProvider {

    private final byte[] buffer;

    public SmallObjectStreamProvider(final byte[] bs) {
        this.buffer = Objects.requireNonNull(bs);
    }

    public InputStream getInputStream() {
        return new ByteArrayInputStream(buffer);
    }

    @Override
    public long getSize() {
        return buffer.length;
    }
    @Override
    public byte[] asByteArray() {
        return Arrays.copyOf(buffer, buffer.length);
    }

}
