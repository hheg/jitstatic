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

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;

import javax.validation.ConstraintValidatorContext;
import javax.validation.ConstraintValidatorContext.ConstraintViolationBuilder;
import javax.validation.ConstraintValidatorContext.ConstraintViolationBuilder.NodeBuilderCustomizableContext;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.jitstatic.auth.UserData;

class HasPasswordValidatorTest {

    @Test
    void testHashedValidatorCorrectValue() {
        ConstraintValidatorContext cvc = Mockito.mock(ConstraintValidatorContext.class);
        UserData data = new UserData(Set.of(), null, "salt", "hash");
        HasPasswordValidator hv = new HasPasswordValidator();
        assertTrue(hv.isValid(data, cvc));
    }

    @Test
    void testHashedValidatorNoHash() {
        ConstraintValidatorContext cvc = Mockito.mock(ConstraintValidatorContext.class);
        ConstraintViolationBuilder builder = Mockito.mock(ConstraintViolationBuilder.class);
        NodeBuilderCustomizableContext nbcc = Mockito.mock(NodeBuilderCustomizableContext.class);
        Mockito.when(cvc.buildConstraintViolationWithTemplate(Mockito.anyString())).thenReturn(builder);
        Mockito.when(builder.addPropertyNode(Mockito.anyString())).thenReturn(nbcc);

        UserData data = new UserData(Set.of(), null, "salt", null);
        HasPasswordValidator hv = new HasPasswordValidator();
        assertFalse(hv.isValid(data, cvc));
    }

    @Test
    void testHashedValidatorNoSalt() {
        ConstraintValidatorContext cvc = Mockito.mock(ConstraintValidatorContext.class);
        ConstraintViolationBuilder builder = Mockito.mock(ConstraintViolationBuilder.class);
        NodeBuilderCustomizableContext nbcc = Mockito.mock(NodeBuilderCustomizableContext.class);
        Mockito.when(cvc.buildConstraintViolationWithTemplate(Mockito.anyString())).thenReturn(builder);
        Mockito.when(builder.addPropertyNode(Mockito.anyString())).thenReturn(nbcc);

        UserData data = new UserData(Set.of(), null, null, "hash");
        HasPasswordValidator hv = new HasPasswordValidator();
        assertFalse(hv.isValid(data, cvc));
    }

    @Test
    void testHashedValidatorEmptyHash() {
        ConstraintValidatorContext cvc = Mockito.mock(ConstraintValidatorContext.class);
        ConstraintViolationBuilder builder = Mockito.mock(ConstraintViolationBuilder.class);
        NodeBuilderCustomizableContext nbcc = Mockito.mock(NodeBuilderCustomizableContext.class);
        Mockito.when(cvc.buildConstraintViolationWithTemplate(Mockito.anyString())).thenReturn(builder);
        Mockito.when(builder.addPropertyNode(Mockito.anyString())).thenReturn(nbcc);

        UserData data = new UserData(Set.of(), null, "", null);
        HasPasswordValidator hv = new HasPasswordValidator();
        assertFalse(hv.isValid(data, cvc));
    }

    @Test
    void testHashedValidatorEmptySalt() {
        ConstraintValidatorContext cvc = Mockito.mock(ConstraintValidatorContext.class);
        ConstraintViolationBuilder builder = Mockito.mock(ConstraintViolationBuilder.class);
        NodeBuilderCustomizableContext nbcc = Mockito.mock(NodeBuilderCustomizableContext.class);
        Mockito.when(cvc.buildConstraintViolationWithTemplate(Mockito.anyString())).thenReturn(builder);
        Mockito.when(builder.addPropertyNode(Mockito.anyString())).thenReturn(nbcc);

        UserData data = new UserData(Set.of(), null, null, "");
        HasPasswordValidator hv = new HasPasswordValidator();
        assertFalse(hv.isValid(data, cvc));
    }

    @Test
    void testHashedValidatorEmptySaltWithHash() {
        ConstraintValidatorContext cvc = Mockito.mock(ConstraintValidatorContext.class);
        ConstraintViolationBuilder builder = Mockito.mock(ConstraintViolationBuilder.class);
        NodeBuilderCustomizableContext nbcc = Mockito.mock(NodeBuilderCustomizableContext.class);
        Mockito.when(cvc.buildConstraintViolationWithTemplate(Mockito.anyString())).thenReturn(builder);
        Mockito.when(builder.addPropertyNode(Mockito.anyString())).thenReturn(nbcc);

        UserData data = new UserData(Set.of(), null, "", "hash");
        HasPasswordValidator hv = new HasPasswordValidator();
        assertFalse(hv.isValid(data, cvc));
    }

    @Test
    void testHashedValidatorEmptyHashWithSalt() {
        ConstraintValidatorContext cvc = Mockito.mock(ConstraintValidatorContext.class);
        ConstraintViolationBuilder builder = Mockito.mock(ConstraintViolationBuilder.class);
        NodeBuilderCustomizableContext nbcc = Mockito.mock(NodeBuilderCustomizableContext.class);
        Mockito.when(cvc.buildConstraintViolationWithTemplate(Mockito.anyString())).thenReturn(builder);
        Mockito.when(builder.addPropertyNode(Mockito.anyString())).thenReturn(nbcc);

        UserData data = new UserData(Set.of(), null, "salt", "");
        HasPasswordValidator hv = new HasPasswordValidator();
        assertFalse(hv.isValid(data, cvc));
    }

    @Test
    void testHashedValidatorNone() {
        ConstraintValidatorContext cvc = Mockito.mock(ConstraintValidatorContext.class);
        ConstraintViolationBuilder builder = Mockito.mock(ConstraintViolationBuilder.class);
        NodeBuilderCustomizableContext nbcc = Mockito.mock(NodeBuilderCustomizableContext.class);
        Mockito.when(cvc.buildConstraintViolationWithTemplate(Mockito.anyString())).thenReturn(builder);
        Mockito.when(builder.addPropertyNode(Mockito.anyString())).thenReturn(nbcc);

        UserData data = new UserData(Set.of(), null, null, null);
        HasPasswordValidator hv = new HasPasswordValidator();
        assertFalse(hv.isValid(data, cvc));
    }

    @Test
    void testHashedValidatorNoHashOrSaltButPassword() {
        ConstraintValidatorContext cvc = Mockito.mock(ConstraintValidatorContext.class);
        UserData data = new UserData(Set.of(), "pass", null, null);
        HasPasswordValidator hv = new HasPasswordValidator();
        assertTrue(hv.isValid(data, cvc));
    }

    @Test
    void testHashedValidatorNoHashOrSaltButEmptyPassword() {
        ConstraintValidatorContext cvc = Mockito.mock(ConstraintValidatorContext.class);
        ConstraintViolationBuilder builder = Mockito.mock(ConstraintViolationBuilder.class);
        NodeBuilderCustomizableContext nbcc = Mockito.mock(NodeBuilderCustomizableContext.class);
        Mockito.when(cvc.buildConstraintViolationWithTemplate(Mockito.anyString())).thenReturn(builder);
        Mockito.when(builder.addPropertyNode(Mockito.anyString())).thenReturn(nbcc);
        UserData data = new UserData(Set.of(), "", null, null);
        HasPasswordValidator hv = new HasPasswordValidator();
        assertFalse(hv.isValid(data, cvc));
    }

}
