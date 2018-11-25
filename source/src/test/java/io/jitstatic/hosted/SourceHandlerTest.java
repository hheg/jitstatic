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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import io.jitstatic.MetaData;
import io.jitstatic.auth.User;

public class SourceHandlerTest {

    @Test
    public void testSourceHandlerMetaData() throws IOException {
        MetaData metaData = SourceHandler.readMetaData(new ByteArrayInputStream(getMetaData().getBytes(StandardCharsets.UTF_8)));
        User user = new User("user1", "1234");
        assertTrue(metaData.getUsers().contains(user));
    }

    @Test
    public void testSourceStorageData() throws IOException {
        byte[] bytes = getMetaData().getBytes(StandardCharsets.UTF_8);
        byte[] expected = SourceHandler.readStorageData(new ByteArrayInputStream(bytes));
        assertArrayEquals(bytes, expected);
    }

    private String getMetaData() {
        return "{\"users\":[{\"password\":\"1234\",\"user\":\"user1\"}]}";
    }
}
