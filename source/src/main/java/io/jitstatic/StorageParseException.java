package io.jitstatic;

/*-
 * #%L
 * jitstatic
 * %%
 * Copyright (C) 2017 - 2018 H.Hegardt
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

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.validation.ConstraintViolation;

import io.jitstatic.auth.constraints.Warning;

public class StorageParseException extends IOException {

    private static final long serialVersionUID = 1774575933983877566L;
    private final List<String> warnings;
    private final List<String> errors;

    public StorageParseException(final String message, final IOException e) {
        super(e);
        errors = List.of(message);
        warnings = List.of();
    }

    public <T> StorageParseException(final Set<ConstraintViolation<T>> violations) {
        warnings = compile(violations.stream()
                .filter(cv -> cv.getConstraintDescriptor().getPayload().stream().anyMatch(Warning.class::isAssignableFrom))
                .collect(Collectors.toSet()));
        errors = compile(violations.stream().filter(cv -> cv.getConstraintDescriptor().getPayload().isEmpty()).collect(Collectors.toSet()));
    }

    private static <T> List<String> compile(final Set<ConstraintViolation<T>> violations) {
        return violations.stream()
                .map(v -> String.format("Property=%s, message=%s, invalidValue=%s", v.getPropertyPath(), v.getMessage(), v.getInvalidValue()))
                .collect(Collectors.toList());
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public List<String> getErrors() {
        return errors;
    }

    @Override
    public Throwable fillInStackTrace() {
        return this;
    }

    @Override
    public String getMessage() {
        return Stream.concat(errors.stream(), warnings.stream()).collect(Collectors.joining(System.lineSeparator()));
    }
}
