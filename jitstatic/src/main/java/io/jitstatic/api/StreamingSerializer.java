package io.jitstatic.api;

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

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import io.jitstatic.source.ObjectStreamProvider;

public class StreamingSerializer extends JsonSerializer<ObjectStreamProvider> {

    @Override
    public void serialize(final ObjectStreamProvider provider, final JsonGenerator gen, final SerializerProvider serializers) throws IOException {
        long size = provider.getSize();
        if (size > Integer.MAX_VALUE) {
            size = -1;
        }
        try (InputStream is = provider.getInputStream()) {
            gen.writeBinary(is, (int) size);
        }
    }
}
