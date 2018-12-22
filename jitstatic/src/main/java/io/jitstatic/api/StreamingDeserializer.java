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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import io.jitstatic.source.ObjectStreamProvider;

public class StreamingDeserializer extends JsonDeserializer<ObjectStreamProvider> {

    @Override
    public ObjectStreamProvider deserialize(final JsonParser p, final DeserializationContext ctxt) throws IOException, JsonProcessingException {
        // Slurp this for now
        // TODO Fix this to handle large arrays
        final byte[] binaryValue = p.getBinaryValue();        
        
        return new ObjectStreamProvider() {
            @Override
            public long getSize() throws IOException {
                return (long) binaryValue.length;
            }

            @Override
            public InputStream getInputStream() throws IOException {
                return new ByteArrayInputStream(binaryValue);
            }
        };

    }

}
