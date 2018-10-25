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
import java.util.Set;
import java.util.stream.Collectors;

import javax.validation.ConstraintViolation;

public class StorageParseException extends IOException {

    private static final long serialVersionUID = 1774575933983877566L;

    public StorageParseException(final String message, final IOException e) {
        super(message, e);
    }

    public <T> StorageParseException(Set<ConstraintViolation<T>> violations) {
        super(compile(violations));
    }

    private static <T> String compile(Set<ConstraintViolation<T>> violations) {
        return violations.stream().map(v -> String.format("Property=%s, message=%s, invalidValue=%s", v.getPropertyPath(), v.getMessage(), v.getInvalidValue()))
                .collect(Collectors.joining(System.lineSeparator()));
    }

    @Override
    public Throwable fillInStackTrace() {
        return this;
    }
}
