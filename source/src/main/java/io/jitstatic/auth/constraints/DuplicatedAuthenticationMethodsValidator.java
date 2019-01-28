package io.jitstatic.auth.constraints;

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

import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;

import io.jitstatic.auth.UserData;

public class DuplicatedAuthenticationMethodsValidator implements ConstraintValidator<DuplicatedAuthenticationMethods, UserData> {

    @Override
    public void initialize(DuplicatedAuthenticationMethods constraintAnnotation) {
    }

    @Override
    public boolean isValid(UserData value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        boolean isValid = !(value.getBasicPassword() != null && (value.getSalt() != null && value.getHash() != null));

        if (!isValid) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate("Clear text password will be ignored")
                    .addPropertyNode("password")
                    .addConstraintViolation();
        }
        return isValid;
    }
}
