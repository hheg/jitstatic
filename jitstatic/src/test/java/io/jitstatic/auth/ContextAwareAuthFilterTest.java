package io.jitstatic.auth;

import static io.jitstatic.JitStaticConstants.REFS_HEADS_SECRETS;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.SecurityContext;

import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.glassfish.jersey.server.ContainerRequest;
import org.glassfish.jersey.server.ExtendedUriInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import io.jitstatic.auth.ContextAwareAuthFilter.Realm;
import io.jitstatic.auth.ContextAwareAuthFilter.Realm.Domain;
import io.jitstatic.auth.ContextAwareAuthFilter.Verdict;
import io.jitstatic.storage.HashService;
import io.jitstatic.storage.Storage;
import io.jitstatic.utils.WrappingAPIException;

class ContextAwareAuthFilterTest {

    @ParameterizedTest
    @MethodSource
    void testLoginRealmStorageMethod(String method, Domain in, Realm of, String key, String api, Object creds, boolean allowed, String branch)
            throws RefNotFoundException {
        Storage storage = mock(Storage.class);
        HashService hashService = mock(HashService.class);
        ContainerRequest crc = mock(ContainerRequest.class);
        ExtendedUriInfo info = mock(ExtendedUriInfo.class);
        @SuppressWarnings("unchecked")
        MultivaluedMap<String, String> mvm = mock(MultivaluedMap.class);

        String user = "user";
        String pass = "pass";
        UserData data = new UserData(Set.of(), pass, null, null);
        when(crc.getUriInfo()).thenReturn(info);
        ArgumentCaptor<SecurityContext> captor = ArgumentCaptor.forClass(SecurityContext.class);

        when(info.getQueryParameters(anyBoolean())).thenReturn(mvm);
        when(mvm.get(eq("ref"))).thenReturn(branch == null ? null : List.of(branch));
        when(info.getMatchedURIs(anyBoolean())).thenReturn(List.of(key, api));
        when(crc.getMethod()).thenReturn(method);
        when(storage.getUser(Mockito.anyString(), Mockito.any(), Mockito.anyString())).thenReturn(CompletableFuture.completedFuture(null));
        if (in == Domain.GIT) {
            when(storage.getUser(eq(user), eq(REFS_HEADS_SECRETS), eq(in.getDomainName()))).thenReturn(CompletableFuture.completedFuture(data));
        } else {
            when(storage.getUser(eq(user), eq(branch), eq(in.getDomainName()))).thenReturn(CompletableFuture.completedFuture(data));
        }
        when(hashService.validatePassword(user, data, pass)).thenReturn(true);

        ContextAwareAuthFilter<Object> caaf = getUnit(storage, hashService, user, pass);
        Verdict authenticate = caaf.authenticate(crc, creds, "scheme");
        assertEquals(allowed, authenticate.isAllowed);
        assertEquals(of, authenticate.realm);
        if (allowed) {
            Mockito.verify(crc).setSecurityContext(captor.capture());
            assertNotNull(captor.getValue());
            // TODO Do more validation here
        }
    }

    static Stream<Arguments> testLoginRealmStorageMethod() {
        //@formatter:off
        Object creds = new Object();
        List<Arguments> arguments = List.of(
                Arguments.of("GET", Domain.NONE, Realm.NONE_USER_ADMIN_GIT, "storage/key", "storage", null, true, null),
                Arguments.of("GET", Domain.KEYUSER, Realm.NONE_USER_ADMIN_GIT, "storage/key", "storage", creds, true, null),
                Arguments.of("GET", Domain.KEYADMIN, Realm.NONE_USER_ADMIN_GIT, "storage/key", "storage", creds, true, null),
                Arguments.of("GET", Domain.GIT, Realm.NONE_USER_ADMIN_GIT, "storage/key", "storage", creds, true, null),
                Arguments.of("PUT", Domain.NONE, Realm.USER_ADMIN_GIT, "storage/key", "storage", null, false, null),
                Arguments.of("PUT", Domain.KEYUSER, Realm.USER_ADMIN_GIT, "storage/key", "storage", creds, true, null),
                Arguments.of("PUT", Domain.KEYADMIN, Realm.USER_ADMIN_GIT, "storage/key", "storage", creds, true, null),
                Arguments.of("PUT", Domain.GIT, Realm.USER_ADMIN_GIT, "storage/key", "storage", creds, true, null),
                Arguments.of("POST", Domain.NONE, Realm.USER_ADMIN_GIT, "storage/key", "storage", null, false, null),
                Arguments.of("POST", Domain.KEYUSER, Realm.USER_ADMIN_GIT, "storage/key", "storage", creds, true, null),
                Arguments.of("POST", Domain.KEYADMIN, Realm.USER_ADMIN_GIT, "storage/key", "storage", creds, true, null),
                Arguments.of("POST", Domain.GIT, Realm.USER_ADMIN_GIT, "storage/key", "storage", creds, true, null),
                Arguments.of("DELETE", Domain.NONE, Realm.USER_ADMIN_GIT, "storage/key", "storage", null, false, null),
                Arguments.of("DELETE", Domain.KEYUSER, Realm.USER_ADMIN_GIT, "storage/key", "storage", creds, true, null),
                Arguments.of("DELETE", Domain.KEYADMIN, Realm.USER_ADMIN_GIT, "storage/key", "storage", creds, true, null),
                Arguments.of("DELETE", Domain.GIT, Realm.USER_ADMIN_GIT, "storage/key", "storage", creds, true, null),
                Arguments.of("OPTIONS", Domain.NONE, Realm.NONE, "storage/key", "storage", null, true, null),
                Arguments.of("OPTIONS", Domain.KEYUSER, Realm.NONE, "storage/key", "storage", creds, true, null),
                Arguments.of("OPTIONS", Domain.KEYADMIN, Realm.NONE, "storage/key", "storage", creds, true, null),
                Arguments.of("OPTIONS", Domain.GIT, Realm.NONE, "storage/key", "storage", creds, true, null),
                Arguments.of("POST", Domain.NONE, Realm.NONE_USER_ADMIN_GIT, "bulk/fetch", "bulk", null, true, null),
                Arguments.of("POST", Domain.KEYUSER, Realm.NONE_USER_ADMIN_GIT, "bulk/fetch", "bulk", creds, true, null),
                Arguments.of("POST", Domain.KEYADMIN, Realm.NONE_USER_ADMIN_GIT, "bulk/fetch", "bulk", creds, true, null),
                Arguments.of("POST", Domain.GIT, Realm.NONE_USER_ADMIN_GIT, "bulk/fetch", "bulk", creds, true, null),
                Arguments.of("GET", Domain.NONE, Realm.USER_ADMIN_GIT, "metakey/key", "metakey", null, false, null),
                Arguments.of("GET", Domain.KEYUSER, Realm.USER_ADMIN_GIT, "metakey/key", "metakey", creds, true, null),
                Arguments.of("GET", Domain.KEYADMIN, Realm.USER_ADMIN_GIT, "metakey/key", "metakey", creds, true, null),
                Arguments.of("GET", Domain.GIT, Realm.USER_ADMIN_GIT, "metakey/key", "metakey", creds, true, null),
                Arguments.of("PUT", Domain.NONE, Realm.USER_ADMIN_GIT, "metakey/key", "metakey", null, false, null),
                Arguments.of("PUT", Domain.KEYUSER, Realm.USER_ADMIN_GIT, "metakey/key", "metakey", creds, true, null),
                Arguments.of("PUT", Domain.KEYADMIN, Realm.USER_ADMIN_GIT, "metakey/key", "metakey", creds, true, null),
                Arguments.of("PUT", Domain.GIT, Realm.USER_ADMIN_GIT, "metakey/key", "metakey", creds, true, null),
                Arguments.of("GET", Domain.NONE, Realm.NONE, "info/commitid", "info", null, true, null),
                Arguments.of("GET", Domain.KEYUSER, Realm.NONE, "info/commitid", "info", creds, true, null),
                Arguments.of("GET", Domain.KEYADMIN, Realm.NONE, "info/commitid", "info", creds, true, null),
                Arguments.of("GET", Domain.GIT, Realm.NONE, "info/commitid", "info", creds, true, null),
                Arguments.of("GET", Domain.NONE, Realm.USERS_GIT, "users/git/user", "users", null, false, null),
                Arguments.of("GET", Domain.KEYUSER, Realm.USERS_GIT, "users/git/user", "users", creds, false, null),
                Arguments.of("GET", Domain.KEYADMIN, Realm.USERS_GIT, "users/git/user", "users", creds, false, null),
                Arguments.of("GET", Domain.GIT, Realm.USERS_GIT, "users/git/user", "users", creds, true, null),
                Arguments.of("PUT", Domain.NONE, Realm.USERS_GIT, "users/git/user", "users", null, false, null),
                Arguments.of("PUT", Domain.KEYUSER, Realm.USERS_GIT, "users/git/user", "users", creds, false, null),
                Arguments.of("PUT", Domain.KEYADMIN, Realm.USERS_GIT, "users/git/user", "users", creds, false, null),
                Arguments.of("PUT", Domain.GIT, Realm.USERS_GIT, "users/git/user", "users", creds, true, null),
                Arguments.of("POST", Domain.NONE, Realm.USERS_GIT, "users/git/user", "users", null, false, null),
                Arguments.of("POST", Domain.KEYUSER, Realm.USERS_GIT, "users/git/user", "users", creds, false, null),
                Arguments.of("POST", Domain.KEYADMIN, Realm.USERS_GIT, "users/git/user", "users", creds, false, null),
                Arguments.of("POST", Domain.GIT, Realm.USERS_GIT, "users/git/user", "users", creds, true, null),
                Arguments.of("DELETE", Domain.NONE, Realm.USERS_GIT, "users/git/user", "users", null, false, null),
                Arguments.of("DELETE", Domain.KEYUSER, Realm.USERS_GIT, "users/git/user", "users", creds, false, null),
                Arguments.of("DELETE", Domain.KEYADMIN, Realm.USERS_GIT, "users/git/user", "users", creds, false, null),
                Arguments.of("DELETE", Domain.GIT, Realm.USERS_GIT, "users/git/user", "users", creds, true, null),
                Arguments.of("GET", Domain.NONE, Realm.USERS_ADMIN_GIT, "users/keyadmin/user", "users", null, false, null),
                Arguments.of("GET", Domain.KEYUSER, Realm.USERS_ADMIN_GIT, "users/keyadmin/user", "users", creds, false, null),
                Arguments.of("GET", Domain.KEYADMIN, Realm.USERS_ADMIN_GIT, "users/keyadmin/user", "users", creds, true, null),
                Arguments.of("GET", Domain.GIT, Realm.USERS_ADMIN_GIT, "users/keyadmin/user", "users", creds, true, null),
                Arguments.of("PUT", Domain.NONE, Realm.USERS_ADMIN_GIT, "users/keyadmin/user", "users", null, false, null),
                Arguments.of("PUT", Domain.KEYUSER, Realm.USERS_ADMIN_GIT, "users/keyadmin/user", "users", creds, false, null),
                Arguments.of("PUT", Domain.KEYADMIN, Realm.USERS_ADMIN_GIT, "users/keyadmin/user", "users", creds, true, null),
                Arguments.of("PUT", Domain.GIT, Realm.USERS_ADMIN_GIT, "users/keyadmin/user", "users", creds, true, null),
                Arguments.of("POST", Domain.NONE, Realm.USERS_ADMIN_GIT, "users/keyadmin/user", "users", null, false, null),
                Arguments.of("POST", Domain.KEYUSER, Realm.USERS_ADMIN_GIT, "users/keyadmin/user", "users", creds, false, null),
                Arguments.of("POST", Domain.KEYADMIN, Realm.USERS_ADMIN_GIT, "users/keyadmin/user", "users", creds, true, null),
                Arguments.of("POST", Domain.GIT, Realm.USERS_ADMIN_GIT, "users/keyadmin/user", "users", creds, true, null),
                Arguments.of("DELETE", Domain.NONE, Realm.USERS_ADMIN_GIT, "users/keyadmin/user", "users", null, false, null),
                Arguments.of("DELETE", Domain.KEYUSER, Realm.USERS_ADMIN_GIT, "users/keyadmin/user", "users", creds, false, null),
                Arguments.of("DELETE", Domain.KEYADMIN, Realm.USERS_ADMIN_GIT, "users/keyadmin/user", "users", creds, true, null),
                Arguments.of("DELETE", Domain.GIT, Realm.USERS_ADMIN_GIT, "users/keyadmin/user", "users", creds, true, null),
                Arguments.of("GET", Domain.NONE, Realm.USERS_USER_ADMIN_GIT, "users/keyuser/user", "users", null, false, null),
                Arguments.of("GET", Domain.KEYUSER, Realm.USERS_USER_ADMIN_GIT, "users/keyuser/user", "users", creds, true, null),
                Arguments.of("GET", Domain.KEYADMIN, Realm.USERS_USER_ADMIN_GIT, "users/keyuser/user", "users", creds, true, null),
                Arguments.of("GET", Domain.GIT, Realm.USERS_USER_ADMIN_GIT, "users/keyuser/user", "users", creds, true, null),
                Arguments.of("PUT", Domain.NONE, Realm.USERS_USER_ADMIN_GIT, "users/keyuser/user", "users", null, false, null),
                Arguments.of("PUT", Domain.KEYUSER, Realm.USERS_USER_ADMIN_GIT, "users/keyuser/user", "users", creds, true, null),
                Arguments.of("PUT", Domain.KEYADMIN, Realm.USERS_USER_ADMIN_GIT, "users/keyuser/user", "users", creds, true, null),
                Arguments.of("PUT", Domain.GIT, Realm.USERS_USER_ADMIN_GIT, "users/keyuser/user", "users", creds, true, null),
                Arguments.of("POST", Domain.NONE, Realm.USERS_USER_ADMIN_GIT, "users/keyuser/user", "users", null, false, null),
                Arguments.of("POST", Domain.KEYUSER, Realm.USERS_USER_ADMIN_GIT, "users/keyuser/user", "users", creds, true, null),
                Arguments.of("POST", Domain.KEYADMIN, Realm.USERS_USER_ADMIN_GIT, "users/keyuser/user", "users", creds, true, null),
                Arguments.of("POST", Domain.GIT, Realm.USERS_USER_ADMIN_GIT, "users/keyuser/user", "users", creds, true, null),
                Arguments.of("DELETE", Domain.NONE, Realm.USERS_USER_ADMIN_GIT, "users/keyuser/user", "users", null, false, null),
                Arguments.of("DELETE", Domain.KEYUSER, Realm.USERS_USER_ADMIN_GIT, "users/keyuser/user", "users", creds, true, null),
                Arguments.of("DELETE", Domain.KEYADMIN, Realm.USERS_USER_ADMIN_GIT, "users/keyuser/user", "users", creds, true, null),
                Arguments.of("DELETE", Domain.GIT, Realm.USERS_USER_ADMIN_GIT, "users/keyuser/user", "users", creds, true, null)
                );
      //@formatter:on
        List<Arguments> doubled = new ArrayList<>(arguments);
        for (Arguments arg : arguments) {
            Object[] args = arg.get();
            Object[] cloned = args.clone();
            cloned[cloned.length - 1] = "refs/heads/master";
            doubled.add(Arguments.of(cloned));
        }
        return doubled.stream();
    }

    @Test
    void testLoginAgainstUnknownUrl() {
        Storage storage = mock(Storage.class);
        HashService hashService = mock(HashService.class);
        ContainerRequest crc = mock(ContainerRequest.class);
        ExtendedUriInfo info = mock(ExtendedUriInfo.class);
        @SuppressWarnings("unchecked")
        MultivaluedMap<String, String> mvm = mock(MultivaluedMap.class);

        String user = "user";
        String pass = "pass";
        when(crc.getUriInfo()).thenReturn(info);
        when(info.getQueryParameters(anyBoolean())).thenReturn(mvm);
        when(mvm.get(eq("ref"))).thenReturn(List.of("refs/heads/master"));
        when(info.getMatchedURIs(anyBoolean())).thenReturn(List.of("unknown/unknown", "unknown"));
        when(crc.getMethod()).thenReturn("UNKNOWN");

        ContextAwareAuthFilter<Object> caaf = getUnit(storage, hashService, user, pass);
        assertEquals(403, assertThrows(WebApplicationException.class, () -> caaf.authenticate(crc, new Object(), "scheme")).getResponse().getStatus());
    }

    @Test
    void testLoginWithNoFoundRef() throws URISyntaxException, RefNotFoundException {
        Storage storage = mock(Storage.class);
        HashService hashService = mock(HashService.class);
        ContainerRequest crc = mock(ContainerRequest.class);
        ExtendedUriInfo info = mock(ExtendedUriInfo.class);
        @SuppressWarnings("unchecked")
        MultivaluedMap<String, String> mvm = mock(MultivaluedMap.class);

        String user = "user";
        String pass = "pass";
        UserData data = new UserData(Set.of(), pass, null, null);
        when(crc.getUriInfo()).thenReturn(info);
        when(info.getQueryParameters(anyBoolean())).thenReturn(mvm);
        when(mvm.get(eq("ref"))).thenReturn(List.of("refs/notref/other"));
        when(info.getMatchedURIs(anyBoolean())).thenReturn(List.of("storage/key", "storage"));
        when(crc.getMethod()).thenReturn("GET");
        when(storage.getUser(Mockito.anyString(), Mockito.any(), Mockito.anyString())).thenReturn(CompletableFuture.completedFuture(null));
        when(storage.getUser(eq(user), eq("refs/notref/other"), eq(Domain.KEYADMIN.getDomainName())))
                .thenReturn(CompletableFuture.failedFuture(new WrappingAPIException(new RefNotFoundException("refs/notref/other"))));
        when(hashService.validatePassword(user, data, pass)).thenReturn(true);

        ContextAwareAuthFilter<Object> caaf = getUnit(storage, hashService, user, pass);
        assertEquals(Status.BAD_REQUEST.getStatusCode(), assertThrows(WebApplicationException.class, () -> caaf.authenticate(crc, new Object(), "scheme"))
                .getResponse()
                .getStatus());
    }

    @Test
    void testLoginWithMultipleRefs() throws RefNotFoundException, URISyntaxException {
        Storage storage = mock(Storage.class);
        HashService hashService = mock(HashService.class);
        ContainerRequest crc = mock(ContainerRequest.class);
        ExtendedUriInfo info = mock(ExtendedUriInfo.class);
        @SuppressWarnings("unchecked")
        MultivaluedMap<String, String> mvm = mock(MultivaluedMap.class);

        String user = "user";
        String pass = "pass";
        UserData data = new UserData(Set.of(), pass, null, null);
        when(crc.getUriInfo()).thenReturn(info);
        when(info.getQueryParameters(anyBoolean())).thenReturn(mvm);
        when(mvm.get(eq("ref"))).thenReturn(List.of("refs/heads/master", "refs/heads/other", "refs/tags/blah"));
        when(info.getMatchedURIs(anyBoolean())).thenReturn(List.of("storage/key", "storage"));
        when(crc.getMethod()).thenReturn("GET");
        when(storage.getUser(Mockito.anyString(), Mockito.any(), Mockito.anyString())).thenReturn(CompletableFuture.completedFuture(null));
        URI uri = new URI("http://localhost:8080/application/storage/key?ref=refs%2Fheads%2Fother&?ref=refs%2Fheads%2Fmaster&?ref=refs%2Ftags%2Fblah");
        when(info.getRequestUri()).thenReturn(uri);
        when(storage.getUser(eq(user), eq("refs/heads/other"), eq(Domain.KEYADMIN.getDomainName()))).thenReturn(CompletableFuture.completedFuture(data));
        when(hashService.validatePassword(user, data, pass)).thenReturn(true);

        ContextAwareAuthFilter<Object> caaf = getUnit(storage, hashService, user, pass);
        Verdict authenticate = caaf.authenticate(crc, new Object(), "scheme");
        assertEquals(true, authenticate.isAllowed);
        assertEquals(Realm.NONE_USER_ADMIN_GIT, authenticate.realm);
    }

    private ContextAwareAuthFilter<Object> getUnit(Storage storage, HashService hashService, String user, String pass) {
        return new ContextAwareAuthFilter<>(storage, "none") {

            @Override
            public void filter(ContainerRequestContext requestContext) throws IOException {
                // Ignore
            }

            @Override
            protected String getUserName(Object credentials) {
                return user;
            }

            protected boolean validate(UserData userData, Object credentials) {
                return userData != null && hashService.validatePassword(user, userData, pass);
            };

            protected boolean isRoot(Object credentials) {
                return false;
            };

        };
    }
}
