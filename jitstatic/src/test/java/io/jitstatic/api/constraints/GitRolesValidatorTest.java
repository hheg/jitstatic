package io.jitstatic.api.constraints;

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

import io.jitstatic.Role;

class GitRolesValidatorTest {

    @Test
    void testMatchRole() {
        GitRolesValidator grv = new GitRolesValidator();
        assertTrue(grv.isValid(Set.of(new Role("secrets")), null));
    }

    @Test
    void testMatchAllRole() {
        GitRolesValidator grv = new GitRolesValidator();
        assertTrue(grv.isValid(Set.of(new Role("secrets"), new Role("forcepush"), new Role("pull"), new Role("push")), null));
    }

    @Test
    void testMatchAllRolePlusOne() {
        GitRolesValidator grv = new GitRolesValidator();
        assertFalse(grv.isValid(Set.of(new Role("secrets"), new Role("forcepush"), new Role("pull"), new Role("push"), new Role("blah")), null));
    }

    
    @Test
    void testNonMatchingRole() {
        GitRolesValidator grv = new GitRolesValidator();
        assertFalse(grv.isValid(Set.of(new Role("blah")), null));
    }

    @Test
    void testNullValue() {
        GitRolesValidator grv = new GitRolesValidator();
        assertTrue(grv.isValid(null, null));
    }
}
