package io.jitstatic;

/*-
 * #%L
 * jitstatic
 * %%
 * Copyright (C) 2017 H.Hegardt
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
import java.io.InputStream;
import java.util.Set;
import java.util.stream.Collectors;

import javax.validation.ConstraintViolation;
import javax.validation.Validation;
import javax.validation.Validator;
import javax.validation.ValidatorFactory;

import com.fasterxml.jackson.core.JsonParser.Feature;
import com.fasterxml.jackson.databind.ObjectMapper;

public class SourceJSONParser {

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(Feature.ALLOW_COMMENTS).enable(Feature.STRICT_DUPLICATE_DETECTION);
    private static final ValidatorFactory VALIDATIONFACTORY = Validation.buildDefaultValidatorFactory();
    private final Validator validator;

    public SourceJSONParser() {
        this.validator = VALIDATIONFACTORY.getValidator();
    }

    public String parseMetaData(final InputStream bc) throws IOException {
        final StorageData metaData = parseStream(bc);

        final Set<ConstraintViolation<StorageData>> violations = validator.validate(metaData);
        if (violations.size() > 0) {
            throw new StorageParseException(violations);
        }
        return metaData.getContentType();
    }

    private StorageData parseStream(final InputStream bc) throws StorageParseException {
        try {
            return MAPPER.readValue(bc, StorageData.class);
        } catch (final IOException e) {
            final Throwable cause = e.getCause();
            throw new StorageParseException((cause != null ? cause.getMessage() : "Unknown error"), e);
        }
    }

    private static class StorageParseException extends IOException {

        private static final long serialVersionUID = 1774575933983877566L;

        public StorageParseException(final String message, final IOException e) {
            super(message, e);
        }

        public StorageParseException(Set<ConstraintViolation<StorageData>> violations) {
            super(compile(violations));
        }

        private static String compile(Set<ConstraintViolation<StorageData>> violations) {
            return violations.stream()
                    .map(v -> String.format("Property=%s, message=%s, invalidValue=%s", v.getPropertyPath(), v.getMessage(), v.getInvalidValue()))
                    .collect(Collectors.joining(System.lineSeparator()));

        }

        @Override
        public Throwable fillInStackTrace() {
            return this;
        }
    }

    public void parseJson(final InputStream is) throws IOException {
        MAPPER.readTree(is);
    }
}
