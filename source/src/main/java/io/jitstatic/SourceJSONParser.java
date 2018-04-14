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

import com.fasterxml.jackson.core.JsonParser.Feature;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.jitstatic.auth.User;

public class SourceJSONParser {

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(Feature.ALLOW_COMMENTS).enable(Feature.STRICT_DUPLICATE_DETECTION);

    public String parse(final InputStream bc) throws IOException {
        final StorageData metaData = parseStream(bc);

        final Set<User> usersNode = metaData.getUsers();
        if (usersNode == null) {
            throw new StorageParseException("metadata is missing users node");
        }
        checkUsers(usersNode);
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

    private void checkUsers(final Set<User> usersNode) throws StorageParseException {
        for (User userNode : usersNode) {
            checkUser(userNode);
        }
    }

    private void checkUser(final User userNode) throws StorageParseException {
        final String userName = userNode.getName();
        if (userName == null) {
            throw new StorageParseException("metadata is missing user name");
        }
        final String password = userNode.getPassword();
        if (password == null) {
            throw new StorageParseException("metadata user " + userName + " is missing password ");
        }
    }

    private static class StorageParseException extends IOException {

        private static final long serialVersionUID = 1774575933983877566L;

        public StorageParseException(final String message) {
            super(message);
        }

        public StorageParseException(final String message, final IOException e) {
            super(message, e);
        }
    }

    public void parseJson(final InputStream is) throws IOException {
        MAPPER.readTree(is);
    }
}
