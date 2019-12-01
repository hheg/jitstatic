package io.jitstatic.injection.configuration.hosted;

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
