package io.jitstatic.injection.configuration.hosted;

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

import java.nio.file.Paths;
import java.util.Set;

import javax.validation.ConstraintViolation;
import javax.validation.Validator;
import javax.validation.ValidatorFactory;

import org.junit.jupiter.api.Test;

import io.dropwizard.jersey.validation.Validators;

class HostedFactoryTest {
    ValidatorFactory validatorFactory = Validators.newValidatorFactory();
    Validator validator = validatorFactory.getValidator();

    @Test
    void testRegexpPattern() {
        HostedFactory hf = new HostedFactory();
        hf.setBranch("refs/heads/branch");
        hf.setUserName("user");
        hf.setSecret("secret");
        hf.setBasePath(Paths.get("/tmp"));
        Set<ConstraintViolation<HostedFactory>> validate = validator.validate(hf);
        assertTrue(validate.isEmpty(), "" + validate);
    }

    @Test
    void testRegexpPatternBranchIsWrongFormat() {
        HostedFactory hf = new HostedFactory();
        String expected = "refs/heas/branch";
        hf.setBranch(expected);
        hf.setUserName("user");
        hf.setSecret("secret");
        hf.setBasePath(Paths.get("/tmp"));
        Set<ConstraintViolation<HostedFactory>> validate = validator.validate(hf);
        assertFalse(validate.isEmpty(), "" + validate);
        assertTrue(validate.size() == 1);
        ConstraintViolation<HostedFactory> next = validate.iterator().next();
        assertEquals(expected, next.getInvalidValue());
    }

}
