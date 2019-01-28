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

public class HasPasswordValidator implements ConstraintValidator<HasPassword, UserData> {

    @Override
    public void initialize(HasPassword constraintAnnotation) {
    }

    @Override
    public boolean isValid(final UserData value, final ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        boolean isValid = !((value.getBasicPassword() == null || value.getBasicPassword().trim().isEmpty())
                && ((value.getHash() == null || value.getHash().trim().isEmpty())
                        || (value.getSalt() == null || value.getSalt().trim().isEmpty())));
        if (!isValid) {
            context.disableDefaultConstraintViolation();
            if (value.getHash() == null || value.getHash().trim().isEmpty()) {
                context.buildConstraintViolationWithTemplate("Missing hash value")
                        .addPropertyNode("hash").addConstraintViolation();
            }
            if (value.getSalt() == null || value.getSalt().trim().isEmpty()) {
                context.buildConstraintViolationWithTemplate("Missing salt value")
                        .addPropertyNode("salt").addConstraintViolation();
            }
        }
        return isValid;
    }

}
