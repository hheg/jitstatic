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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import javax.validation.ConstraintValidatorContext;
import javax.validation.ConstraintValidatorContext.ConstraintViolationBuilder;
import javax.validation.ConstraintValidatorContext.ConstraintViolationBuilder.NodeBuilderCustomizableContext;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.jitstatic.auth.UserData;

class DuplicatedAuthenticationMethodsValidatorTest {

    @Test
    public void testDuplicatedAutenticationMethods() {
        ConstraintValidatorContext cvc = Mockito.mock(ConstraintValidatorContext.class);
        ConstraintViolationBuilder builder = Mockito.mock(ConstraintViolationBuilder.class);
        NodeBuilderCustomizableContext nbcc = Mockito.mock(NodeBuilderCustomizableContext.class);
        Mockito.when(cvc.buildConstraintViolationWithTemplate(Mockito.anyString())).thenReturn(builder);
        Mockito.when(builder.addPropertyNode(Mockito.anyString())).thenReturn(nbcc);

        UserData data = new UserData(Set.of(), "pass", "salt", "hash");
        DuplicatedAuthenticationMethodsValidator validator = new DuplicatedAuthenticationMethodsValidator();
        assertFalse(validator.isValid(data, cvc));
    }

    @Test
    public void testDuplicatedAutenticationMethodsNullValue() {
        ConstraintValidatorContext cvc = Mockito.mock(ConstraintValidatorContext.class);
        DuplicatedAuthenticationMethodsValidator validator = new DuplicatedAuthenticationMethodsValidator();
        assertTrue(validator.isValid(null, cvc));
    }

    @Test
    public void testDuplicatedAutenticationMethodsCorrectValue() {
        ConstraintValidatorContext cvc = Mockito.mock(ConstraintValidatorContext.class);
        UserData data = new UserData(Set.of(), null, "salt", "hash");
        DuplicatedAuthenticationMethodsValidator validator = new DuplicatedAuthenticationMethodsValidator();
        assertTrue(validator.isValid(data, cvc));
    }
}
