package io.jitstatic.storage;

/*-
 * #%L
 * jitstatic
 * %%
 * Copyright (C) 2017 - 2019 H.Hegardt
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

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;

import org.junit.jupiter.api.Test;

import io.jitstatic.auth.UserData;

class HashServiceTest {

    @Test
    void testHashPassword() {
        HashService hash = new HashService();
        UserData userData = hash.constructUserData(Set.of(), "password");
        assertTrue(hash.validatePassword("user", userData, "password"));
        assertNotNull(userData.getHash());
        assertNotNull(userData.getSalt());
        assertEquals(userData.getHash().length(), userData.getSalt().length());
    }

    @Test
    public void testLegacyPasswordCheck() {
        HashService hash = new HashService();
        UserData data = new UserData(Set.of(), "password", null, null);
        assertTrue(hash.validatePassword("user", data, "password"));
    }

}
