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

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonParser.Feature;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.jitstatic.MetaData;
//TODO Remove this SpotBugs Error
@SuppressFBWarnings(value="RCN_REDUNDANT_NULLCHECK_WOULD_HAVE_BEEN_A_NPE",justification="This is a false positive in Java 11, should be removed")
public class SourceHandler {
    private static final JsonFactory MAPPER = new ObjectMapper().enable(Feature.ALLOW_COMMENTS).getFactory();

    public static byte[] readStorageData(final InputStream is) throws IOException {
        Objects.requireNonNull(is);
        return is.readAllBytes();
    }

    public static MetaData readMetaData(final InputStream storageStream) throws IOException {
        Objects.requireNonNull(storageStream);
        try (final JsonParser parser = MAPPER.createParser(storageStream);) {
            return parser.readValueAs(MetaData.class);
        }
    }
}
