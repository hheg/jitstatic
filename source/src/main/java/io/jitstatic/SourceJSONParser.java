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
        final MetaData metaData = parseStream(bc);

        final Set<ConstraintViolation<MetaData>> violations = validator.validate(metaData);
        if (!violations.isEmpty()) {
            throw new StorageParseException(violations);
        }
        return metaData.getContentType();
    }

    private MetaData parseStream(final InputStream bc) throws StorageParseException {
        try {
            return MAPPER.readValue(bc, MetaData.class);
        } catch (final IOException e) {
            throw new StorageParseException(e.getLocalizedMessage(), e);
        }
    }

    public void parseJson(final InputStream is) throws IOException {
        MAPPER.readTree(is);
    }
}
