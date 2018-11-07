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

import static io.jitstatic.JitStaticConstants.GIT_REALM;
import static io.jitstatic.JitStaticConstants.JITSTATIC_KEYADMIN_REALM;
import static io.jitstatic.JitStaticConstants.JITSTATIC_KEYUSER_REALM;
import static io.jitstatic.JitStaticConstants.SECRETS;
import static javax.ws.rs.core.HttpHeaders.AUTHORIZATION;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static org.eclipse.jetty.http.HttpStatus.CONFLICT_409;
import static org.eclipse.jetty.http.HttpStatus.FORBIDDEN_403;
import static org.eclipse.jetty.http.HttpStatus.NOT_FOUND_404;
import static org.eclipse.jetty.http.HttpStatus.OK_200;
import static org.eclipse.jetty.http.HttpStatus.PRECONDITION_FAILED_412;
import static org.eclipse.jetty.http.HttpStatus.UNAUTHORIZED_401;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.ws.rs.client.Entity;
import javax.ws.rs.core.EntityTag;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;

import org.eclipse.jetty.server.UserIdentity;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.glassfish.jersey.server.filter.RolesAllowedDynamicFeature;
import org.glassfish.jersey.test.grizzly.GrizzlyWebTestContainerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import com.fasterxml.jackson.databind.JsonNode;
import com.spencerwi.either.Either;

import io.dropwizard.auth.AuthDynamicFeature;
import io.dropwizard.auth.AuthValueFactoryProvider;
import io.dropwizard.auth.basic.BasicCredentialAuthFilter;
import io.dropwizard.testing.ConfigOverride;
import io.dropwizard.testing.ResourceHelpers;
import io.dropwizard.testing.junit5.DropwizardAppExtension;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.dropwizard.testing.junit5.ResourceExtension;
import io.jitstatic.api.UsersResource;
import io.jitstatic.auth.ConfiguratedAuthenticator;
import io.jitstatic.auth.KeyAdminAuthenticator;
import io.jitstatic.auth.User;
import io.jitstatic.auth.UserData;
import io.jitstatic.client.JitStaticClient;
import io.jitstatic.client.JitStaticClientBuilder;
import io.jitstatic.client.TriFunction;
import io.jitstatic.hosted.FailedToLock;
import io.jitstatic.hosted.LoginService;
import io.jitstatic.storage.Storage;
import io.jitstatic.test.TemporaryFolder;
import io.jitstatic.test.TemporaryFolderExtension;
import io.jitstatic.utils.Pair;

@ExtendWith({ DropwizardExtensionsSupport.class, TemporaryFolderExtension.class })
public class UserAPITest {

//    private static final Set<Role> ROOTROLES = JitStaticConstants.ROLES.stream().map(Role::new).collect(Collectors.toSet());
//    private static final Charset UTF_8 = StandardCharsets.UTF_8;
//    private static final String PUSER = "puser";
//    private static final String PSECRET = "psecret";
//    private TemporaryFolder tmpfolder;
//
//    private static final String BASIC_AUTH_CRED = createCreds(PUSER, PSECRET);
//
//    private DropwizardAppExtension<JitstaticConfiguration> DW = new DropwizardAppExtension<>(JitstaticApplication.class,
//            ResourceHelpers.resourceFilePath("simpleserver.yaml"), ConfigOverride.config("hosted.basePath", getFolder()));
//
//    @AfterEach
//    public void after() {
//
//    }
//
//    @Test
//    public void testGetKeyAdminUser() throws RefNotFoundException {
//        UserIdentity ui = mock(UserIdentity.class);
//
//        Response data = RESOURCES.target("users/" + JITSTATIC_KEYADMIN_REALM + "/keyadmin").request().header(AUTHORIZATION, BASIC_AUTH_CRED).get();
//
//        assertNotNull(data);
//        assertEquals(OK_200, data.getStatus());
//        UserData entity = data.readEntity(UserData.class);
//        assertTrue(entity.getRoles().equals(Set.of(new Role("role"))));
//        assertEquals("22", entity.getBasicPassword());
//        EntityTag entityTag = data.getEntityTag();
//        assertNotNull(entityTag);
//        assertEquals("1", entityTag.getValue());
//        data.close();
//    }
//
//    @Test
//    public void testGetUserWithNoUser() {
//        Response data = RESOURCES.target("users/" + JITSTATIC_KEYADMIN_REALM + "/keyadmin").request().get();
//        assertEquals(UNAUTHORIZED_401, data.getStatus());
//        data.close();
//    }
//
//    @Test
//    public void testGetUserWithWrongUser() throws RefNotFoundException {
//        when(loginService.login(eq(PUSER), any(), eq(null))).thenReturn(null);
//        when(storage.getUser(eq(PUSER), eq("refs/heads/" + SECRETS), eq(GIT_REALM))).thenReturn(new UserData(ROOTROLES, PSECRET));
//        when(storage.getUserData(eq("keyadmin"), eq(null), eq(JITSTATIC_KEYADMIN_REALM)))
//                .thenReturn(Pair.of("1", new UserData(Set.of(new Role("role")), "22")));
//
//        Response data = RESOURCES.target("users/" + JITSTATIC_KEYADMIN_REALM + "/keyadmin").request().header(AUTHORIZATION, BASIC_AUTH_CRED).get();
//        assertEquals(FORBIDDEN_403, data.getStatus());
//        data.close();
//    }
//
//    @Test
//    public void testGetUserNotChanged() throws RefNotFoundException {
//        UserIdentity ui = mock(UserIdentity.class);
//        when(loginService.login(eq(PUSER), any(), eq(null))).thenReturn(ui);
//        when(storage.getUser(eq(PUSER), eq("refs/heads/" + SECRETS), eq(GIT_REALM))).thenReturn(new UserData(ROOTROLES, PSECRET));
//        when(storage.getUserData(eq("keyadmin"), eq(null), eq(JITSTATIC_KEYADMIN_REALM)))
//                .thenReturn(Pair.of("1", new UserData(Set.of(new Role("role")), "22")));
//
//        Response data = RESOURCES.target("users/" + JITSTATIC_KEYADMIN_REALM + "/keyadmin").request().header(AUTHORIZATION, BASIC_AUTH_CRED).get();
//        EntityTag entityTag = data.getEntityTag();
//        assertNotNull(entityTag);
//        data.close();
//        data = RESOURCES.target("users/" + JITSTATIC_KEYADMIN_REALM + "/keyadmin").request().header("if-match", "\"" + entityTag.getValue() + "\"")
//                .header(AUTHORIZATION, BASIC_AUTH_CRED).get();
//        assertEquals(entityTag.getValue(), data.getEntityTag().getValue());
//        data.close();
//    }
//
//    @Test
//    public void testPutUserWithNoUser() {
//        Response data = RESOURCES.target("users/" + JITSTATIC_KEYADMIN_REALM + "/keyadmin").request()
//                .put(Entity.entity(new UserData(Set.of(new Role("role")), "22"), APPLICATION_JSON));
//        assertEquals(UNAUTHORIZED_401, data.getStatus());
//        data.close();
//    }
//
//    @Test
//    public void testPutUserWithUserButNotValid() {
//        Response data = RESOURCES.target("users/" + JITSTATIC_KEYADMIN_REALM + "/keyadmin").request().header(AUTHORIZATION, BASIC_AUTH_CRED)
//                .header(HttpHeaders.IF_MATCH, "\"" + 2 + "\"").put(Entity.entity(new UserData(Set.of(new Role("role")), "22"), APPLICATION_JSON));
//        assertEquals(FORBIDDEN_403, data.getStatus());
//        data.close();
//    }
//
//    @Test
//    public void testPutUserWithUserButNotFound() {
//        UserIdentity ui = mock(UserIdentity.class);
//        when(loginService.login(eq(PUSER), any(), eq(null))).thenReturn(ui);
//        Response data = RESOURCES.target("users/" + JITSTATIC_KEYADMIN_REALM + "/keyadmin").request().header(AUTHORIZATION, BASIC_AUTH_CRED)
//                .header(HttpHeaders.IF_MATCH, "\"" + 2 + "\"").put(Entity.entity(new UserData(Set.of(new Role("role")), "22"), APPLICATION_JSON));
//        assertEquals(NOT_FOUND_404, data.getStatus());
//        data.close();
//    }
//
//    @Test
//    public void testPutUserWithUserButFoundWrongEtag() throws RefNotFoundException {
//        UserIdentity ui = mock(UserIdentity.class);
//        when(loginService.login(eq(PUSER), any(), eq(null))).thenReturn(ui);
//        when(storage.getUserData(eq("keyadmin"), eq(null), eq(JITSTATIC_KEYADMIN_REALM)))
//                .thenReturn(Pair.of("1", new UserData(Set.of(new Role("role")), "22")));
//
//        Response data = RESOURCES.target("users/" + JITSTATIC_KEYADMIN_REALM + "/keyadmin").request().header(AUTHORIZATION, BASIC_AUTH_CRED)
//                .header(HttpHeaders.IF_MATCH, "\"" + 2 + "\"").put(Entity.entity(new UserData(Set.of(new Role("role")), "20"), APPLICATION_JSON));
//        assertEquals(PRECONDITION_FAILED_412, data.getStatus());
//        data.close();
//    }
//
//    @Test
//    public void testPutUserWithUserButFailedToLock() throws RefNotFoundException {
//        UserIdentity ui = mock(UserIdentity.class);
//        UserData userData = new UserData(Set.of(new Role("role")), "22");
//        when(loginService.login(eq(PUSER), any(), eq(null))).thenReturn(ui);
//        when(storage.getUserData(eq("keyadmin"), eq(null), eq(JITSTATIC_KEYADMIN_REALM)))
//                .thenReturn(Pair.of("1", new UserData(Set.of(new Role("role")), "22")));
//        when(storage.update(eq("keyadmin"), eq(null), eq(JITSTATIC_KEYADMIN_REALM), eq(PUSER), eq(userData), null))
//                .thenReturn(Either.right(new FailedToLock("ref")));
//        Response data = RESOURCES.target("users/" + JITSTATIC_KEYADMIN_REALM + "/keyadmin").request().header(AUTHORIZATION, BASIC_AUTH_CRED)
//                .header(HttpHeaders.IF_MATCH, "\"" + 1 + "\"").put(Entity.entity(userData, APPLICATION_JSON));
//        assertEquals(PRECONDITION_FAILED_412, data.getStatus());
//        data.close();
//        verify(storage, times(1)).update(eq("keyadmin"), eq(null), eq(JITSTATIC_KEYADMIN_REALM), eq(PUSER),
//                eq(userData), null);
//    }
//
//    @Test
//    public void testPutUserWithUserButRemoved() throws RefNotFoundException {
//        UserIdentity ui = mock(UserIdentity.class);
//        UserData userData = new UserData(Set.of(new Role("role")), "22");
//        when(loginService.login(eq(PUSER), any(), eq(null))).thenReturn(ui);
//        when(storage.getUserData(eq("keyadmin"), eq(null), eq(JITSTATIC_KEYADMIN_REALM)))
//                .thenReturn(Pair.of("1", new UserData(Set.of(new Role("role")), "22")));
//        when(storage.update(eq("keyadmin"), eq(null), eq(JITSTATIC_KEYADMIN_REALM), eq(PUSER), eq(userData), null))
//                .thenReturn(Either.left(null));
//        Response data = RESOURCES.target("users/" + JITSTATIC_KEYADMIN_REALM + "/keyadmin").request().header(AUTHORIZATION, BASIC_AUTH_CRED)
//                .header(HttpHeaders.IF_MATCH, "\"" + 1 + "\"").put(Entity.entity(userData, APPLICATION_JSON));
//        assertEquals(NOT_FOUND_404, data.getStatus());
//        data.close();
//        verify(storage, times(1)).update(eq("keyadmin"), eq(null), eq(JITSTATIC_KEYADMIN_REALM), eq(PUSER),
//                eq(userData), null);
//    }
//
//    @Test
//    public void testPutUserWithUser() throws RefNotFoundException {
//        UserIdentity ui = mock(UserIdentity.class);
//        UserData userData = new UserData(Set.of(new Role("role")), "22");
//        when(loginService.login(eq(PUSER), any(), eq(null))).thenReturn(ui);
//        when(storage.getUserData(eq("keyadmin"), eq(null), eq(JITSTATIC_KEYADMIN_REALM)))
//                .thenReturn(Pair.of("1", new UserData(Set.of(new Role("role")), "22")));
//        when(storage.update(eq("keyadmin"), eq(null), eq(JITSTATIC_KEYADMIN_REALM), eq(PUSER), eq(userData), null))
//                .thenReturn(Either.left("2"));
//        Response data = RESOURCES.target("users/" + JITSTATIC_KEYADMIN_REALM + "/keyadmin").request().header(AUTHORIZATION, BASIC_AUTH_CRED)
//                .header(HttpHeaders.IF_MATCH, "\"" + 1 + "\"").put(Entity.entity(userData, APPLICATION_JSON));
//        assertEquals("2", data.getEntityTag().getValue());
//        data.close();
//        verify(storage, times(1)).update(eq("keyadmin"), eq(null), eq(JITSTATIC_KEYADMIN_REALM), eq(PUSER),
//                eq(userData), null);
//    }
//
//    @Test
//    public void testPostUserWithNoUser() {
//        UserData userData = new UserData(Set.of(new Role("role")), "22");
//        Response response = RESOURCES.target("users/" + JITSTATIC_KEYADMIN_REALM + "/keyadmin").request()
//                .post(Entity.entity(userData, APPLICATION_JSON));
//        assertEquals(UNAUTHORIZED_401, response.getStatus());
//        response.close();
//    }
//
//    @Test
//    public void testPostUserWithWrongUser() {
//        UserData userData = new UserData(Set.of(new Role("role")), "22");
//        Response response = RESOURCES.target("users/" + JITSTATIC_KEYADMIN_REALM + "/keyadmin").request().header(AUTHORIZATION, BASIC_AUTH_CRED)
//                .post(Entity.entity(userData, APPLICATION_JSON));
//        assertEquals(FORBIDDEN_403, response.getStatus());
//        response.close();
//    }
//
//    @Test
//    public void testPostUserWithUserButExist() throws RefNotFoundException {
//        UserIdentity ui = mock(UserIdentity.class);
//        UserData userData = new UserData(Set.of(new Role("role")), "22");
//        when(loginService.login(eq(PUSER), any(), eq(null))).thenReturn(ui);
//        when(storage.getUserData(eq("keyadmin"), eq(null), eq(JITSTATIC_KEYADMIN_REALM)))
//                .thenReturn(Pair.of("1", new UserData(Set.of(new Role("role")), "22")));
//        Response response = RESOURCES.target("users/" + JITSTATIC_KEYADMIN_REALM + "/keyadmin").request().header(AUTHORIZATION, BASIC_AUTH_CRED)
//                .post(Entity.entity(userData, APPLICATION_JSON));
//        assertEquals(CONFLICT_409, response.getStatus());
//        response.close();
//    }
//
//    @Test
//    public void testPostUserWithUser() throws RefNotFoundException {
//        UserIdentity ui = mock(UserIdentity.class);
//        UserData userData = new UserData(Set.of(new Role("role")), "22");
//        when(loginService.login(eq(PUSER), any(), eq(null))).thenReturn(ui);
//        when(storage.postUser(eq("keyadmin"), eq(null), eq(JITSTATIC_KEYADMIN_REALM), eq(PUSER), eq(userData))).thenReturn("22");
//        Response response = RESOURCES.target("users/" + JITSTATIC_KEYADMIN_REALM + "/keyadmin").request().header(AUTHORIZATION, BASIC_AUTH_CRED)
//                .post(Entity.entity(userData, APPLICATION_JSON));
//        assertEquals(OK_200, response.getStatus());
//        assertEquals("22", response.getEntityTag().getValue());
//        response.close();
//    }
//
//    @Test
//    public void testDeleteUserWithoutUser() {
//        Response response = RESOURCES.target("users/" + JITSTATIC_KEYADMIN_REALM + "/keyadmin").request().delete();
//        assertEquals(UNAUTHORIZED_401, response.getStatus());
//        response.close();
//    }
//
//    @Test
//    public void testDeleteUserWithWrongUser() {
//        Response response = RESOURCES.target("users/" + JITSTATIC_KEYADMIN_REALM + "/keyadmin").request().header(AUTHORIZATION, BASIC_AUTH_CRED).delete();
//        assertEquals(FORBIDDEN_403, response.getStatus());
//        response.close();
//    }
//
//    @Test
//    public void testDeleteUserNotFound() {
//        UserIdentity ui = mock(UserIdentity.class);
//        when(loginService.login(eq(PUSER), any(), eq(null))).thenReturn(ui);
//        Response response = RESOURCES.target("users/" + JITSTATIC_KEYADMIN_REALM + "/keyadmin").request().header(AUTHORIZATION, BASIC_AUTH_CRED).delete();
//        assertEquals(NOT_FOUND_404, response.getStatus());
//        response.close();
//    }
//
//    @Test
//    public void testDeleteUser() throws RefNotFoundException {
//        UserIdentity ui = mock(UserIdentity.class);
//        when(loginService.login(eq(PUSER), any(), eq(null))).thenReturn(ui);
//        when(storage.getUserData(eq("keyadmin"), eq(null), eq(JITSTATIC_KEYADMIN_REALM)))
//                .thenReturn(Pair.of("1", new UserData(Set.of(new Role("role")), "22")));
//        Response response = RESOURCES.target("users/" + JITSTATIC_KEYADMIN_REALM + "/keyadmin").request().header(AUTHORIZATION, BASIC_AUTH_CRED).delete();
//        assertEquals(OK_200, response.getStatus());
//        verify(storage, times(1)).deleteUser(Mockito.eq("keyadmin"), eq(null), eq(JITSTATIC_KEYADMIN_REALM), eq(PUSER));
//        response.close();
//    }
//
//    @Test
//    public void testGetKeyUser() throws RefNotFoundException {
//        when(authen.authenticate(eq(new User(PUSER, PSECRET)), eq(null))).thenReturn(true);
//        when(storage.getUser(eq(PUSER), eq(null), eq(JITSTATIC_KEYADMIN_REALM))).thenReturn(new UserData(ROOTROLES, PSECRET));
//        when(storage.getUserData(eq("keyadmin"), eq(null), eq(JITSTATIC_KEYUSER_REALM)))
//                .thenReturn(Pair.of("1", new UserData(Set.of(new Role("role")), "22")));
//
//        Response data = RESOURCES.target("users/" + JITSTATIC_KEYUSER_REALM + "/keyadmin").request().header(AUTHORIZATION, BASIC_AUTH_CRED).get();
//
//        assertNotNull(data);
//        assertEquals(OK_200, data.getStatus());
//        UserData entity = data.readEntity(UserData.class);
//        assertTrue(entity.getRoles().equals(Set.of(new Role("role"))));
//        assertEquals("22", entity.getBasicPassword());
//        EntityTag entityTag = data.getEntityTag();
//        assertNotNull(entityTag);
//        assertEquals("1", entityTag.getValue());
//        data.close();
//    }
//
//    @Test
//    public void testGetKeyUserWithNoUser() {
//        Response data = RESOURCES.target("users/" + JITSTATIC_KEYUSER_REALM + "/keyadmin").request().get();
//        assertEquals(UNAUTHORIZED_401, data.getStatus());
//        data.close();
//    }
//
//    @Test
//    public void testGetKeyUserWithWrongUser() throws RefNotFoundException {
//        when(authen.authenticate(eq(new User(PUSER, PSECRET)), eq(null))).thenReturn(false);
//        when(storage.getUser(eq(PUSER), eq(null), eq(JITSTATIC_KEYADMIN_REALM))).thenReturn(new UserData(ROOTROLES, PSECRET));
//        when(storage.getUserData(eq("keyadmin"), eq(null), eq(JITSTATIC_KEYUSER_REALM)))
//                .thenReturn(Pair.of("1", new UserData(Set.of(new Role("role")), "22")));
//
//        Response data = RESOURCES.target("users/" + JITSTATIC_KEYUSER_REALM + "/keyadmin").request().header(AUTHORIZATION, BASIC_AUTH_CRED).get();
//        assertEquals(FORBIDDEN_403, data.getStatus());
//        data.close();
//    }
//
//    @Test
//    public void testGetKeyUserNotChanged() throws RefNotFoundException {
//        when(authen.authenticate(eq(new User(PUSER, PSECRET)), eq(null))).thenReturn(true);
//        when(storage.getUser(eq(PUSER), eq("refs/heads/" + SECRETS), eq(JITSTATIC_KEYADMIN_REALM))).thenReturn(new UserData(ROOTROLES, PSECRET));
//        when(storage.getUserData(eq("keyadmin"), eq(null), eq(JITSTATIC_KEYUSER_REALM)))
//                .thenReturn(Pair.of("1", new UserData(Set.of(new Role("role")), "22")));
//
//        Response data = RESOURCES.target("users/" + JITSTATIC_KEYUSER_REALM + "/keyadmin").request().header(AUTHORIZATION, BASIC_AUTH_CRED).get();
//        EntityTag entityTag = data.getEntityTag();
//        assertNotNull(entityTag);
//        data.close();
//        data = RESOURCES.target("users/" + JITSTATIC_KEYUSER_REALM + "/keyadmin").request().header("if-match", "\"" + entityTag.getValue() + "\"")
//                .header(AUTHORIZATION, BASIC_AUTH_CRED).get();
//        assertEquals(entityTag.getValue(), data.getEntityTag().getValue());
//        data.close();
//    }
//
//    @Test
//    public void testPutKeyUserWithNoUser() {
//        Response data = RESOURCES.target("users/" + JITSTATIC_KEYUSER_REALM + "/keyadmin").request()
//                .put(Entity.entity(new UserData(Set.of(new Role("role")), "22"), APPLICATION_JSON));
//        assertEquals(UNAUTHORIZED_401, data.getStatus());
//        data.close();
//    }
//
//    @Test
//    public void testPutKeyUserWithUserButNotValid() {
//        when(authen.authenticate(eq(new User(PUSER, PSECRET)), eq(null))).thenReturn(false);
//        Response data = RESOURCES.target("users/" + JITSTATIC_KEYUSER_REALM + "/keyadmin").request().header(AUTHORIZATION, BASIC_AUTH_CRED)
//                .header(HttpHeaders.IF_MATCH, "\"" + 2 + "\"").put(Entity.entity(new UserData(Set.of(new Role("role")), "22"), APPLICATION_JSON));
//        assertEquals(FORBIDDEN_403, data.getStatus());
//        data.close();
//    }
//
//    @Test
//    public void testPutKeyUserWithUserButNotFound() {
//        when(authen.authenticate(eq(new User(PUSER, PSECRET)), eq(null))).thenReturn(true);
//        Response data = RESOURCES.target("users/" + JITSTATIC_KEYUSER_REALM + "/keyadmin").request().header(AUTHORIZATION, BASIC_AUTH_CRED)
//                .header(HttpHeaders.IF_MATCH, "\"" + 2 + "\"").put(Entity.entity(new UserData(Set.of(new Role("role")), "22"), APPLICATION_JSON));
//        assertEquals(NOT_FOUND_404, data.getStatus());
//        data.close();
//    }
//
//    @Test
//    public void testPutKeyUserWithUserButFoundWrongEtag() throws RefNotFoundException {
//        when(authen.authenticate(eq(new User(PUSER, PSECRET)), eq(null))).thenReturn(true);
//        when(storage.getUserData(eq("keyadmin"), eq(null), eq(JITSTATIC_KEYUSER_REALM)))
//                .thenReturn(Pair.of("1", new UserData(Set.of(new Role("role")), "22")));
//
//        Response data = RESOURCES.target("users/" + JITSTATIC_KEYUSER_REALM + "/keyadmin").request().header(AUTHORIZATION, BASIC_AUTH_CRED)
//                .header(HttpHeaders.IF_MATCH, "\"" + 2 + "\"").put(Entity.entity(new UserData(Set.of(new Role("role")), "20"), APPLICATION_JSON));
//        assertEquals(PRECONDITION_FAILED_412, data.getStatus());
//        data.close();
//    }
//
//    @Test
//    public void testPutKeyUserWithUserButFailedToLock() throws RefNotFoundException {
//        when(authen.authenticate(eq(new User(PUSER, PSECRET)), eq(null))).thenReturn(true);
//        UserData userData = new UserData(Set.of(new Role("role")), "22");
//        when(storage.getUserData(eq("keyadmin"), eq(null), eq(JITSTATIC_KEYUSER_REALM)))
//                .thenReturn(Pair.of("1", new UserData(Set.of(new Role("role")), "22")));
//        when(storage.update(eq("keyadmin"), eq(null), eq(JITSTATIC_KEYUSER_REALM), eq(PUSER), eq(userData), null))
//                .thenReturn(Either.right(new FailedToLock("ref")));
//        Response data = RESOURCES.target("users/" + JITSTATIC_KEYUSER_REALM + "/keyadmin").request().header(AUTHORIZATION, BASIC_AUTH_CRED)
//                .header(HttpHeaders.IF_MATCH, "\"" + 1 + "\"").put(Entity.entity(userData, APPLICATION_JSON));
//        assertEquals(PRECONDITION_FAILED_412, data.getStatus());
//        data.close();
//        verify(storage, times(1)).update(eq("keyadmin"), eq(null), eq(JITSTATIC_KEYUSER_REALM), eq(PUSER),
//                eq(userData), null);
//    }
//
//    @Test
//    public void testPutKeyUserWithUserButRemoved() throws RefNotFoundException {
//        when(authen.authenticate(eq(new User(PUSER, PSECRET)), eq(null))).thenReturn(true);
//        UserData userData = new UserData(Set.of(new Role("role")), "22");
//        when(storage.getUserData(eq("keyadmin"), eq(null), eq(JITSTATIC_KEYUSER_REALM)))
//                .thenReturn(Pair.of("1", new UserData(Set.of(new Role("role")), "22")));
//        when(storage.update(eq("keyadmin"), eq(null), eq(JITSTATIC_KEYUSER_REALM), eq(PUSER), eq(userData), null))
//                .thenReturn(Either.left(null));
//        Response data = RESOURCES.target("users/" + JITSTATIC_KEYUSER_REALM + "/keyadmin").request().header(AUTHORIZATION, BASIC_AUTH_CRED)
//                .header(HttpHeaders.IF_MATCH, "\"" + 1 + "\"").put(Entity.entity(userData, APPLICATION_JSON));
//        assertEquals(NOT_FOUND_404, data.getStatus());
//        data.close();
//        verify(storage, times(1)).update(eq("keyadmin"), eq(null), eq(JITSTATIC_KEYUSER_REALM), eq(PUSER),
//                eq(userData), null);
//    }
//
//    @Test
//    public void testPutKeyUserWithUser() throws RefNotFoundException {
//        when(authen.authenticate(eq(new User(PUSER, PSECRET)), eq(null))).thenReturn(true);
//        UserData userData = new UserData(Set.of(new Role("role")), "22");
//        when(storage.getUserData(eq("keyadmin"), eq(null), eq(JITSTATIC_KEYUSER_REALM)))
//                .thenReturn(Pair.of("1", new UserData(Set.of(new Role("role")), "22")));
//        when(storage.update(eq("keyadmin"), eq(null), eq(JITSTATIC_KEYUSER_REALM), eq(PUSER), eq(userData), null))
//                .thenReturn(Either.left("2"));
//        Response data = RESOURCES.target("users/" + JITSTATIC_KEYUSER_REALM + "/keyadmin").request().header(AUTHORIZATION, BASIC_AUTH_CRED)
//                .header(HttpHeaders.IF_MATCH, "\"" + 1 + "\"").put(Entity.entity(userData, APPLICATION_JSON));
//        assertEquals("2", data.getEntityTag().getValue());
//        data.close();
//        verify(storage, times(1)).update(eq("keyadmin"), eq(null), eq(JITSTATIC_KEYUSER_REALM), eq(PUSER),
//                eq(userData), null);
//    }
//
//    @Test
//    public void testPostKeyUserWithNoUser() {
//        UserData userData = new UserData(Set.of(new Role("role")), "22");
//        Response response = RESOURCES.target("users/" + JITSTATIC_KEYUSER_REALM + "/keyadmin").request()
//                .post(Entity.entity(userData, APPLICATION_JSON));
//        assertEquals(UNAUTHORIZED_401, response.getStatus());
//        response.close();
//    }
//
//    @Test
//    public void testPostKeyUserWithWrongUser() {
//        when(authen.authenticate(eq(new User(PUSER, PSECRET)), eq(null))).thenReturn(false);
//        UserData userData = new UserData(Set.of(new Role("role")), "22");
//        Response response = RESOURCES.target("users/" + JITSTATIC_KEYUSER_REALM + "/keyadmin").request().header(AUTHORIZATION, BASIC_AUTH_CRED)
//                .post(Entity.entity(userData, APPLICATION_JSON));
//        assertEquals(FORBIDDEN_403, response.getStatus());
//        response.close();
//    }
//
//    @Test
//    public void testPostKeyUserWithUserButExist() throws RefNotFoundException {
//        when(authen.authenticate(eq(new User(PUSER, PSECRET)), eq(null))).thenReturn(true);
//        UserData userData = new UserData(Set.of(new Role("role")), "22");
//        when(storage.getUserData(eq("keyadmin"), eq(null), eq(JITSTATIC_KEYUSER_REALM)))
//                .thenReturn(Pair.of("1", new UserData(Set.of(new Role("role")), "22")));
//        Response response = RESOURCES.target("users/" + JITSTATIC_KEYUSER_REALM + "/keyadmin").request().header(AUTHORIZATION, BASIC_AUTH_CRED)
//                .post(Entity.entity(userData, APPLICATION_JSON));
//        assertEquals(CONFLICT_409, response.getStatus());
//        response.close();
//    }
//
//    @Test
//    public void testPostKeyUserWithUser() throws RefNotFoundException {
//        when(authen.authenticate(eq(new User(PUSER, PSECRET)), eq(null))).thenReturn(true);
//        UserData userData = new UserData(Set.of(new Role("role")), "22");
//        when(storage.postUser(eq("keyadmin"), eq(null), eq(JITSTATIC_KEYUSER_REALM), eq(PUSER), eq(userData))).thenReturn("22");
//        Response response = RESOURCES.target("users/" + JITSTATIC_KEYUSER_REALM + "/keyadmin").request().header(AUTHORIZATION, BASIC_AUTH_CRED)
//                .post(Entity.entity(userData, APPLICATION_JSON));
//        assertEquals(OK_200, response.getStatus());
//        assertEquals("22", response.getEntityTag().getValue());
//        response.close();
//    }
//
//    @Test
//    public void testDeleteKeyUserWithoutUser() {
//        Response response = RESOURCES.target("users/" + JITSTATIC_KEYUSER_REALM + "/keyadmin").request().delete();
//        assertEquals(UNAUTHORIZED_401, response.getStatus());
//        response.close();
//    }
//
//    @Test
//    public void testDeleteKeyUserWithWrongUser() {
//        Response response = RESOURCES.target("users/" + JITSTATIC_KEYUSER_REALM + "/keyadmin").request().header(AUTHORIZATION, BASIC_AUTH_CRED).delete();
//        assertEquals(FORBIDDEN_403, response.getStatus());
//        response.close();
//    }
//
//    @Test
//    public void testDeleteKeyUserNotFound() {
//        when(authen.authenticate(eq(new User(PUSER, PSECRET)), eq(null))).thenReturn(true);
//        Response response = RESOURCES.target("users/" + JITSTATIC_KEYUSER_REALM + "/keyadmin").request().header(AUTHORIZATION, BASIC_AUTH_CRED).delete();
//        assertEquals(NOT_FOUND_404, response.getStatus());
//        response.close();
//    }
//
//    @Test
//    public void testDeleteKeyUser() throws RefNotFoundException {
//        when(authen.authenticate(eq(new User(PUSER, PSECRET)), eq(null))).thenReturn(true);
//        when(storage.getUserData(eq("keyadmin"), eq(null), eq(JITSTATIC_KEYUSER_REALM)))
//                .thenReturn(Pair.of("1", new UserData(Set.of(new Role("role")), "22")));
//        Response response = RESOURCES.target("users/" + JITSTATIC_KEYUSER_REALM + "/keyadmin").request().header(AUTHORIZATION, BASIC_AUTH_CRED).delete();
//        assertEquals(OK_200, response.getStatus());
//        verify(storage, times(1)).deleteUser(Mockito.eq("keyadmin"), eq(null), eq(JITSTATIC_KEYUSER_REALM), eq(PUSER));
//        response.close();
//    }
//
//    private static String createCreds(String user, String secret) {
//        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + secret).getBytes(UTF_8));
//    }
//
//    private Supplier<String> getFolder() {
//        return () -> {
//            try {
//                return getFolderFile().getAbsolutePath();
//            } catch (IOException e) {
//                throw new RuntimeException(e);
//            }
//        };
//    }
//
//    private File getFolderFile() throws IOException {
//        return tmpfolder.createTemporaryDirectory();
//    }
//
//    private JitStaticClientBuilder buildClient() {
//        return JitStaticClient.create().setHost("localhost").setPort(DW.getLocalPort()).setAppContext("/application/");
//    }
//
//    static class Entity<T> {
//
//        final T data;
//        private final String tag;
//        private final String contentType;
//
//        public Entity(String tag, String contentType, T data) {
//            this.tag = tag;
//            this.contentType = contentType;
//            this.data = data;
//        }
//
//        public String getTag() {
//            return tag;
//        }
//
//        public String getContentType() {
//            return contentType;
//        }
//    }
//
//    private TriFunction<InputStream, String, String, Entity<JsonNode>> tf = (is, v, t) -> {
//        if (is != null) {
//            try {
//                return new Entity<>(v, t, MAPPER.readValue(is, JsonNode.class));
//            } catch (IOException e) {
//                throw new UncheckedIOException(e);
//            }
//        }
//        return new Entity<>(v, t, null);
//    };

}
