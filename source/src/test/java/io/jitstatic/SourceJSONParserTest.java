package io.jitstatic;

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

import static org.junit.jupiter.api.Assertions.assertThrows;

import static org.hamcrest.MatcherAssert.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

import org.hamcrest.CoreMatchers;
import org.junit.jupiter.api.Test;

import io.jitstatic.SourceJSONParser;

public class SourceJSONParserTest {

    private final SourceJSONParser p = new SourceJSONParser();

    @Test
    public void testReadValidParser() throws IOException {
        try (InputStream bc = SourceJSONParserTest.class.getResourceAsStream("/test3.md.json")) {
            p.parseMetaData(bc);
        }
    }

    @Test
    public void testReadJSONWithMissingUserField() throws IOException {
        assertThat(assertThrows(IOException.class, () -> {
            try (InputStream bc = SourceJSONParserTest.class.getResourceAsStream("/test5.json")) {
                p.parseMetaData(bc);
            }
        }).getLocalizedMessage(), CoreMatchers.containsString("metadata is missing users field"));
    }

    @Test
    public void testReadObjectWithUserWithNoUser() throws UnsupportedEncodingException, IOException {
        assertThat(assertThrows(IOException.class, () -> {
            try (InputStream bc = new ByteArrayInputStream("{\"users\":[{\"password\":\"1234\"}]}".getBytes(StandardCharsets.UTF_8.name()))) {
                p.parseMetaData(bc);
            }
        }).getLocalizedMessage(), CoreMatchers.containsString("Property="));
    }
}
