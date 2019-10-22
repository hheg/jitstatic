package io.jitstatic;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.jitstatic.MetaData;
import io.jitstatic.auth.User;

public class MetaDataTest {

    @Test
    public void testMetaData() {
        MetaData sd1 = new MetaData(Set.of(), Set.of());
        MetaData sd2 = new MetaData(Set.of(), Set.of());
        MetaData sd3 = new MetaData("text/plain", false, false, List.of(), Set.of(), Set.of());

        assertEquals(sd1, sd2);
        assertNotEquals(sd1, sd3);
        assertEquals(sd1.hashCode(), sd2.hashCode());
        assertNotEquals(sd1.hashCode(), sd3.hashCode());
    }

    @Test
    public void testMetaDataJSON() throws JsonProcessingException {
        Set<User> users = new HashSet<>();
        users.add(new User("name", "pass"));
        MetaData sd1 = new MetaData(null, false, false, List.of(HeaderPair.of("tag", "1234"), HeaderPair.of("header", "value")), Set.of(), Set.of());
        ObjectMapper mapper = new ObjectMapper();
        System.out.println(mapper.writeValueAsString(sd1));
    }
}
