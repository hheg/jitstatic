package io.jitstatic.auth;

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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.jitstatic.Role;

public class UserDataTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void testUserDataTest() {
        UserData d1 = new UserData(Set.of(new Role("role")), "pass", null, null);
        UserData d2 = new UserData(Set.of(new Role("role")), "pass", null, null);
        UserData d3 = new UserData(Set.of(new Role("role"), new Role("other")), "pass", null, null);
        UserData d4 = new UserData(Set.of(new Role("role")), "other", null, null);
        UserData d5 = new UserData(Set.of(new Role("role")), null, "salt", "hash");
        UserData d6 = new UserData(Set.of(new Role("role")), null, "alt", "hash");
        UserData d7 = new UserData(Set.of(new Role("role")), null, "alt", "hash");
        UserData d8 = new UserData(Set.of(new Role("role")), null,  null, "hash");
        UserData d9 = new UserData(Set.of(new Role("role")), null, "alt", null);
        assertTrue(d1.equals(d2));
        assertFalse(d1.equals(d3));
        assertTrue(d1.equals(d1));
        assertFalse(d1.equals(d4));
        assertTrue(d1.hashCode() == d2.hashCode());
        assertFalse(d5.equals(d6));
        assertFalse(d1.equals(d6));
        assertFalse(d3.equals(d6));
        assertFalse(d4.equals(d6));
        assertTrue(d7.equals(d6));
        assertFalse(d8.equals(d9));
        assertFalse(d8.equals(d1));
    }

    @Test
    public void testUserDataJson() throws JsonProcessingException {
        UserData d1 = new UserData(Set.of(new Role("role")), "pass", null, null);
        String value = MAPPER.writeValueAsString(d1);
        System.out.println(value);
    }

}
