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

import java.util.Set;

import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;

import io.jitstatic.JitStaticConstants;
import io.jitstatic.Role;

public class GitRolesValidator implements ConstraintValidator<GitRoles, Set<Role>> {

    @Override
    public void initialize(GitRoles constraintAnnotation) {
        // NOOP
    }

    @Override
    public boolean isValid(Set<Role> value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        return value.stream().map(Role::getRole).allMatch(JitStaticConstants.ROLES::contains);
    }

}
