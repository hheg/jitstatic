package io.jitstatic.auth;

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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.function.BiPredicate;

import javax.annotation.Nullable;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.SecurityContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.dropwizard.auth.basic.BasicCredentials;
import io.jitstatic.storage.HashService;
import io.jitstatic.storage.Storage;

public class UrlAwareBasicCredentialAuthFilter extends ContextAwareAuthFilter<BasicCredentials> {

    private static final Logger LOG = LoggerFactory.getLogger(UrlAwareBasicCredentialAuthFilter.class);
    private final HashService hashService;
    private final BiPredicate<String, String> rootAuthenticator;

    public UrlAwareBasicCredentialAuthFilter(final Storage storage, final HashService hashService, final BiPredicate<String, String> rootAuthenticator) {
        super(storage, "Basic");
        this.hashService = hashService;
        this.rootAuthenticator = rootAuthenticator;
    }

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        final BasicCredentials credentials = getCredentials(requestContext.getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
        final Verdict verdict = authenticate(requestContext, credentials, SecurityContext.BASIC_AUTH);
        if (!verdict.isAllowed) {
            throw new WebApplicationException(unauthorizedHandler.buildResponse(prefix, verdict.realm.getRealmName()));
        }
    }

    @Override
    protected String getUserName(BasicCredentials credentials) {
        return credentials.getUsername();
    }

    @Override
    protected boolean validate(UserData userData, BasicCredentials credentials) {
        return userData != null && hashService.hasSamePassword(userData, credentials.getPassword());
    }

    @Override
    protected boolean isRoot(BasicCredentials credentials) {
        return rootAuthenticator.test(credentials.getUsername(), credentials.getPassword());
    }

    @Nullable
    private BasicCredentials getCredentials(String header) {
        if (header == null) {
            return null;
        }

        final int space = header.indexOf(' ');
        if (space <= 0) {
            return null;
        }

        final String method = header.substring(0, space);
        if (!prefix.equalsIgnoreCase(method)) {
            return null;
        }

        final String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(header.substring(space + 1)), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            LOG.warn("Error decoding credentials", e);
            return null;
        }

        // Decoded credentials is 'username:password'
        final int i = decoded.indexOf(':');
        if (i <= 0) {
            return null;
        }

        final String username = decoded.substring(0, i);
        final String password = decoded.substring(i + 1);
        return new BasicCredentials(username, password);
    }

}
