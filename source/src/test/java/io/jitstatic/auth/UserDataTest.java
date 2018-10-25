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
        UserData d1 = new UserData(Set.of(new Role("role")), "pass");
        UserData d2 = new UserData(Set.of(new Role("role")), "pass");
        UserData d3 = new UserData(Set.of(new Role("role"), new Role("other")), "pass");
        UserData d4 = new UserData(Set.of(new Role("role")), "other");
        assertTrue(d1.equals(d2));
        assertFalse(d1.equals(d3));
        assertTrue(d1.equals(d1));
        assertFalse(d1.equals(d4));
        assertTrue(d1.hashCode() == d2.hashCode());
    }

    @Test
    public void testUserDataJson() throws JsonProcessingException {
        UserData d1 = new UserData(Set.of(new Role("role")), "pass");
        String value = MAPPER.writeValueAsString(d1);
        System.out.println(value);
    }

}
