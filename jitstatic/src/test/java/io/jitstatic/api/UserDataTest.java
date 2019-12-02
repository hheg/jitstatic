package io.jitstatic.api;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import javax.validation.ConstraintViolation;
import javax.validation.Validation;
import javax.validation.Validator;
import javax.validation.groups.Default;

import org.junit.jupiter.api.Test;

import io.jitstatic.Role;
import io.jitstatic.api.constraints.Adding;

class UserDataTest {

    private Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void testUserData() {
        UserData data = new UserData(Set.of(new Role("role")), "pass", null);
        Set<ConstraintViolation<UserData>> validate = validator.validate(data);
        assertTrue(validate.isEmpty());
    }

    @Test
    public void testUserDataWithNoPassword() {
        UserData data = new UserData(Set.of(new Role("role")), null, null);
        Set<ConstraintViolation<UserData>> validate = validator.validate(data);
        assertTrue(validate.isEmpty());
        validate = validator.validate(data, Adding.class, Default.class);
        assertTrue(validate.size() == 1);
    }

    @Test
    public void testUserDataWithNoRole() {
        UserData data = new UserData(null, "pass", null);
        Set<ConstraintViolation<UserData>> validate = validator.validate(data);
        assertTrue(validate.size() == 1);
        validate = validator.validate(data, Adding.class, Default.class);
        assertTrue(validate.size() == 1);
    }

}
