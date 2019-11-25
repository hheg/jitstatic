package io.jitstatic.api;

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

import static io.jitstatic.JitStaticConstants.JITSTATIC_GIT_REALM;
import static io.jitstatic.JitStaticConstants.JITSTATIC_KEYADMIN_REALM;
import static io.jitstatic.JitStaticConstants.JITSTATIC_KEYUSER_REALM;
import static io.jitstatic.JitStaticConstants.GIT_SECRETS;
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
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;

import javax.ws.rs.client.Entity;
import javax.ws.rs.core.EntityTag;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;

import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.glassfish.jersey.test.grizzly.GrizzlyWebTestContainerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import com.spencerwi.either.Either;

import io.dropwizard.auth.AuthDynamicFeature;
import io.dropwizard.auth.AuthValueFactoryProvider;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.dropwizard.testing.junit5.ResourceExtension;
import io.jitstatic.JitStaticConstants;
import io.jitstatic.Role;
import io.jitstatic.auth.UrlAwareBasicCredentialAuthFilter;
import io.jitstatic.auth.User;
import io.jitstatic.auth.UserData;
import io.jitstatic.hosted.FailedToLock;
import io.jitstatic.storage.HashService;
import io.jitstatic.storage.Storage;
import io.jitstatic.utils.Pair;

@ExtendWith(DropwizardExtensionsSupport.class)
public class UsersResourceTest {

    private static final Set<Role> ROOTROLES = JitStaticConstants.GIT_ROLES.stream().map(Role::new).collect(Collectors.toSet());
    private static final Charset UTF_8 = StandardCharsets.UTF_8;
    private Storage storage = mock(Storage.class);

    @SuppressWarnings("unchecked")
    BiPredicate<String, String> authen = Mockito.mock(BiPredicate.class);
    private static final String PUSER = "puser";
    private static final String PSECRET = "psecret";

    private static final String BASIC_AUTH_CRED = createCreds(PUSER, PSECRET);
    private static final String REFS_HEADS_MASTER = "refs/heads/master";

    private HashService hashService = new HashService();

    public ResourceExtension RESOURCES = ResourceExtension.builder().setTestContainerFactory(new GrizzlyWebTestContainerFactory())
            .addProvider(new AuthDynamicFeature(new UrlAwareBasicCredentialAuthFilter(storage, hashService, authen)))
            .addProvider(new AuthValueFactoryProvider.Binder<>(User.class))
            .addResource(new UsersResource(storage, REFS_HEADS_MASTER, hashService))
            .build();

    @BeforeEach
    public void before() throws RefNotFoundException {
        when(authen.test(PUSER, PSECRET)).thenReturn(true);
        when(storage.getUser(anyString(), any(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
    }

    @AfterEach
    public void after() {
        Mockito.reset(storage);
        Mockito.reset(authen);
    }

    @Test
    public void testGetKeyAdminUser() throws RefNotFoundException {
        when(storage.getUser(eq(PUSER), eq("refs/heads/" + GIT_SECRETS), eq(JITSTATIC_GIT_REALM)))
                .thenReturn(CompletableFuture.completedFuture(new UserData(ROOTROLES, PSECRET, null, null)));
        when(storage.getUserData(eq("keyadmin"), eq(REFS_HEADS_MASTER), eq(JITSTATIC_KEYADMIN_REALM)))
                .thenReturn(CompletableFuture.completedFuture(Pair.of("1", new UserData(Set.of(new Role("role")), "22", null, null))));

        Response data = RESOURCES.target("users/" + JITSTATIC_KEYADMIN_REALM + "/keyadmin").request().header(AUTHORIZATION, BASIC_AUTH_CRED).get();

        assertNotNull(data);
        assertEquals(OK_200, data.getStatus());
        UserData entity = data.readEntity(UserData.class);
        assertTrue(entity.getRoles().equals(Set.of(new Role("role"))));
        assertEquals("22", entity.getBasicPassword());
        EntityTag entityTag = data.getEntityTag();
        assertNotNull(entityTag);
        assertEquals("1", entityTag.getValue());
        data.close();
    }

    @Test
    public void testGetUserWithNoUser() {
        Response data = RESOURCES.target("users/" + JITSTATIC_KEYADMIN_REALM + "/keyadmin").request().get();
        assertEquals(UNAUTHORIZED_401, data.getStatus());
        data.close();
    }

    @Test
    public void testGetUserWithWrongUser() throws RefNotFoundException {
        String user = "user";
        String pass = "pass";
        when(storage.getUser(eq(user), eq(null), eq(JITSTATIC_KEYADMIN_REALM)))
                .thenReturn(CompletableFuture.completedFuture(new UserData(ROOTROLES, pass, null, null)));
        Response data = RESOURCES.target("users/" + JITSTATIC_KEYADMIN_REALM + "/keyadmin").request().header(AUTHORIZATION, createCreds(user, pass)).get();
        assertEquals(FORBIDDEN_403, data.getStatus());
        data.close();
    }

    @Test
    public void testGetUserNotChanged() throws RefNotFoundException {
        when(storage.getUser(eq(PUSER), eq("refs/heads/" + GIT_SECRETS), eq(JITSTATIC_GIT_REALM)))
                .thenReturn(CompletableFuture.completedFuture(new UserData(ROOTROLES, PSECRET, null, null)));
        when(storage.getUserData(eq("keyadmin"), eq(REFS_HEADS_MASTER), eq(JITSTATIC_KEYADMIN_REALM)))
                .thenReturn(CompletableFuture.completedFuture(Pair.of("1", new UserData(Set.of(new Role("role")), "22", null, null))));

        Response data = RESOURCES.target("users/" + JITSTATIC_KEYADMIN_REALM + "/keyadmin").request().header(AUTHORIZATION, BASIC_AUTH_CRED).get();
        EntityTag entityTag = data.getEntityTag();
        assertNotNull(entityTag);
        data.close();
        data = RESOURCES.target("users/" + JITSTATIC_KEYADMIN_REALM + "/keyadmin").request().header("if-match", "\"" + entityTag.getValue() + "\"")
                .header(AUTHORIZATION, BASIC_AUTH_CRED).get();
        assertEquals(entityTag.getValue(), data.getEntityTag().getValue());
        data.close();
    }

    @Test
    public void testPutUserWithNoUser() {
        Response data = RESOURCES.target("users/" + JITSTATIC_KEYADMIN_REALM + "/keyadmin").request()
                .put(Entity.entity(new io.jitstatic.api.UserData(Set.of(new Role("role")), "22", null), APPLICATION_JSON));
        assertEquals(UNAUTHORIZED_401, data.getStatus());
        data.close();
    }

    @Test
    public void testPutUserWithUserButNotValid() throws RefNotFoundException {
        String user = "user";
        String pass = "pass";
        when(storage.getUser(eq(user), eq(null), eq(JITSTATIC_KEYADMIN_REALM)))
                .thenReturn(CompletableFuture.completedFuture(new UserData(ROOTROLES, pass, null, null)));

        Response data = RESOURCES.target("users/" + JITSTATIC_KEYADMIN_REALM + "/keyadmin").request()
                .header(AUTHORIZATION, createCreds(user, pass))
                .header(HttpHeaders.IF_MATCH, "\"" + 2 + "\"")
                .put(Entity.entity(new io.jitstatic.api.UserData(Set.of(new Role("role")), "22", null), APPLICATION_JSON));
        assertEquals(FORBIDDEN_403, data.getStatus());
        data.close();
    }

    @Test
    public void testPutUserWithUserButNotFound() throws RefNotFoundException {
        when(storage.getUserData(anyString(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        Response data = RESOURCES.target("users/" + JITSTATIC_KEYADMIN_REALM + "/keyadmin").request()
                .header(AUTHORIZATION, BASIC_AUTH_CRED)
                .header(HttpHeaders.IF_MATCH, "\"" + 2 + "\"")
                .put(Entity.entity(new io.jitstatic.api.UserData(Set.of(new Role("role")), "22", null), APPLICATION_JSON));
        assertEquals(NOT_FOUND_404, data.getStatus());
        data.close();
    }

    @Test
    public void testPutUserWithUserButFoundWrongEtag() throws RefNotFoundException {
        when(storage.getUserData(eq("keyadmin"), eq(REFS_HEADS_MASTER), eq(JITSTATIC_KEYADMIN_REALM)))
                .thenReturn(CompletableFuture.completedFuture(Pair.of("1", new UserData(Set.of(new Role("role")), "22", null, null))));

        Response data = RESOURCES.target("users/" + JITSTATIC_KEYADMIN_REALM + "/keyadmin").request()
                .header(AUTHORIZATION, BASIC_AUTH_CRED)
                .header(HttpHeaders.IF_MATCH, "\"" + 2 + "\"")
                .put(Entity.entity(new io.jitstatic.api.UserData(Set.of(new Role("role")), "22", null), APPLICATION_JSON));
        assertEquals(PRECONDITION_FAILED_412, data.getStatus());
        data.close();
    }

    @Test
    public void testPutUserWithUserButFailedToLock() throws RefNotFoundException {
        UserData userData = new UserData(Set.of(new Role("role")), "22", null, null);
        when(storage.getUserData(eq("keyadmin"), eq(REFS_HEADS_MASTER), eq(JITSTATIC_KEYADMIN_REALM)))
                .thenReturn(CompletableFuture.completedFuture(Pair.of("1", new UserData(Set.of(new Role("role")), "22", null, null))));
        when(storage.updateUser(eq("keyadmin"), eq(REFS_HEADS_MASTER), eq(JITSTATIC_KEYADMIN_REALM), eq(PUSER), eq(userData), eq("1")))
                .thenReturn(CompletableFuture.completedFuture(Either.right(new FailedToLock("ref"))));
        Response data = RESOURCES.target("users/" + JITSTATIC_KEYADMIN_REALM + "/keyadmin").request()
                .header(AUTHORIZATION, BASIC_AUTH_CRED)
                .header(HttpHeaders.IF_MATCH, "\"" + 1 + "\"")
                .put(Entity.entity(new io.jitstatic.api.UserData(Set.of(new Role("role")), "22", null), APPLICATION_JSON));
        assertEquals(PRECONDITION_FAILED_412, data.getStatus());
        data.close();
        verify(storage, times(1)).updateUser(eq("keyadmin"), eq(REFS_HEADS_MASTER), eq(JITSTATIC_KEYADMIN_REALM), eq(PUSER), eq(userData), eq("1"));
    }

    @Test
    public void testPutUserWithUserButRemoved() throws RefNotFoundException {
        UserData userData = new UserData(Set.of(new Role("role")), "22", null, null);
        when(storage.getUserData(eq("keyadmin"), eq(REFS_HEADS_MASTER), eq(JITSTATIC_KEYADMIN_REALM)))
                .thenReturn(CompletableFuture.completedFuture(Pair.of("1", new UserData(Set.of(new Role("role")), "22", null, null))));
        when(storage.updateUser(eq("keyadmin"), eq(REFS_HEADS_MASTER), eq(JITSTATIC_KEYADMIN_REALM), eq(PUSER), any(), eq("1")))
                .thenReturn(CompletableFuture.completedFuture(Either.left(null)));
        Response data = RESOURCES.target("users/" + JITSTATIC_KEYADMIN_REALM + "/keyadmin").request()
                .header(AUTHORIZATION, BASIC_AUTH_CRED)
                .header(HttpHeaders.IF_MATCH, "\"" + 1 + "\"")
                .put(Entity.entity(new io.jitstatic.api.UserData(Set.of(new Role("role")), "22", null), APPLICATION_JSON));
        assertEquals(NOT_FOUND_404, data.getStatus());
        data.close();
        verify(storage, times(1)).updateUser(eq("keyadmin"), eq(REFS_HEADS_MASTER), eq(JITSTATIC_KEYADMIN_REALM), eq(PUSER), eq(userData), eq("1"));
    }

    @Test
    public void testPutUserWithUser() throws RefNotFoundException {
        try {
            UserData userData = new UserData(Set.of(new Role("role")), "22", null, null);
            when(storage.getUserData(eq("keyadmin"), eq(REFS_HEADS_MASTER), eq(JITSTATIC_KEYADMIN_REALM)))
                    .thenReturn(CompletableFuture.completedFuture(Pair.of("1", new UserData(Set.of(new Role("role")), "22", null, null))));
            when(storage.updateUser(eq("keyadmin"), eq(REFS_HEADS_MASTER), eq(JITSTATIC_KEYADMIN_REALM), eq(PUSER), eq(userData), eq("1")))
                    .thenReturn(CompletableFuture.completedFuture(Either.left("2")));
            Response data = RESOURCES.target("users/" + JITSTATIC_KEYADMIN_REALM + "/keyadmin").request()
                    .header(AUTHORIZATION, BASIC_AUTH_CRED)
                    .header(HttpHeaders.IF_MATCH, "\"" + 1 + "\"")
                    .put(Entity.entity(new io.jitstatic.api.UserData(Set.of(new Role("role")), "22", null), APPLICATION_JSON));
            assertEquals("2", data.getEntityTag().getValue());
            data.close();
            verify(storage, times(1)).updateUser(eq("keyadmin"), eq(REFS_HEADS_MASTER), eq(JITSTATIC_KEYADMIN_REALM), eq(PUSER), eq(userData), eq("1"));
        } catch (Exception e) {
            e.printStackTrace();
            fail();
        }
    }

    @Test
    public void testPostUserWithNoUser() {
        Response response = RESOURCES.target("users/" + JITSTATIC_KEYADMIN_REALM + "/keyadmin").request()
                .post(Entity.entity(new io.jitstatic.api.UserData(Set.of(new Role("role")), "22", null), APPLICATION_JSON));
        assertEquals(UNAUTHORIZED_401, response.getStatus());
        response.close();
    }

    @Test
    public void testPostUserWithWrongUser() throws RefNotFoundException {
        String user = "user";
        String pass = "pass";
        when(storage.getUser(user, null, "keyadmin")).thenReturn(CompletableFuture.completedFuture(new UserData(Set.of(new Role("some")), pass, null, null)));
        Response response = RESOURCES.target("users/" + JITSTATIC_KEYADMIN_REALM + "/keyadmin").request()
                .header(AUTHORIZATION, createCreds(user, pass))
                .post(Entity.entity(new io.jitstatic.api.UserData(Set.of(new Role("role")), "22", null), APPLICATION_JSON));
        assertEquals(FORBIDDEN_403, response.getStatus());
        response.close();
    }

    @Test
    public void testPostUserWithUserButExist() throws RefNotFoundException {
        when(storage.getUserData(eq("keyadmin"), eq(REFS_HEADS_MASTER), eq(JITSTATIC_KEYADMIN_REALM)))
                .thenReturn(CompletableFuture.completedFuture(Pair.of("1", new UserData(Set.of(new Role("role")), "22", null, null))));
        Response response = RESOURCES.target("users/" + JITSTATIC_KEYADMIN_REALM + "/keyadmin").request()
                .header(AUTHORIZATION, BASIC_AUTH_CRED)
                .post(Entity.entity(new io.jitstatic.api.UserData(Set.of(new Role("role")), "22", null), APPLICATION_JSON));
        assertEquals(CONFLICT_409, response.getStatus());
        response.close();
    }

    @Test
    public void testPostUserWithUser() throws RefNotFoundException {
        when(storage.getUserData(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        when(storage.addUser(eq("keyadmin"), eq(REFS_HEADS_MASTER), eq(JITSTATIC_KEYADMIN_REALM), eq(PUSER), any()))
                .thenReturn(CompletableFuture.completedFuture("22"));
        Response response = RESOURCES.target("users/" + JITSTATIC_KEYADMIN_REALM + "/keyadmin").request()
                .header(AUTHORIZATION, BASIC_AUTH_CRED)
                .post(Entity.entity(new io.jitstatic.api.UserData(Set.of(new Role("role")), "22", null), APPLICATION_JSON));
        assertEquals(OK_200, response.getStatus());
        assertEquals("22", response.getEntityTag().getValue());
        response.close();
    }

    @Test
    public void testDeleteUserWithoutUser() {
        Response response = RESOURCES.target("users/" + JITSTATIC_KEYADMIN_REALM + "/keyadmin").request().delete();
        assertEquals(UNAUTHORIZED_401, response.getStatus());
        response.close();
    }

    @Test
    public void testDeleteUserWithWrongUser() throws RefNotFoundException {
        String user = "user";
        String pass = "pass";
        when(storage.getUser(user, null, "keyadmin")).thenReturn(CompletableFuture.completedFuture(new UserData(Set.of(new Role("some")), pass, null, null)));
        Response response = RESOURCES.target("users/" + JITSTATIC_KEYADMIN_REALM + "/keyadmin").request().header(AUTHORIZATION, createCreds(user, pass))
                .delete();
        assertEquals(FORBIDDEN_403, response.getStatus());
        response.close();
    }

    @Test
    public void testDeleteUserNotFound() throws RefNotFoundException {
        when(storage.getUserData(anyString(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        Response response = RESOURCES.target("users/" + JITSTATIC_KEYADMIN_REALM + "/keyadmin")
                .request()
                .header(AUTHORIZATION, BASIC_AUTH_CRED)
                .delete();
        assertEquals(NOT_FOUND_404, response.getStatus());
        response.close();
    }

    @Test
    public void testDeleteUser() throws RefNotFoundException {
        when(storage.getUserData(eq("keyadmin"), eq(REFS_HEADS_MASTER), eq(JITSTATIC_KEYADMIN_REALM)))
                .thenReturn(CompletableFuture.completedFuture(Pair.of("1", new UserData(Set.of(new Role("role")), "22", null, null))));
        Response response = RESOURCES.target("users/" + JITSTATIC_KEYADMIN_REALM + "/keyadmin").request().header(AUTHORIZATION, BASIC_AUTH_CRED).delete();
        assertEquals(OK_200, response.getStatus());
        verify(storage, times(1)).deleteUser(Mockito.eq("keyadmin"), eq(REFS_HEADS_MASTER), eq(JITSTATIC_KEYADMIN_REALM), eq(PUSER));
        response.close();
    }

    @Test
    public void testGetKeyUser() throws RefNotFoundException {
        when(storage.getUser(eq(PUSER), eq(REFS_HEADS_MASTER), eq(JITSTATIC_KEYADMIN_REALM)))
                .thenReturn(CompletableFuture.completedFuture(new UserData(ROOTROLES, PSECRET, null, null)));
        when(storage.getUserData(eq("keyadmin"), eq(REFS_HEADS_MASTER), eq(JITSTATIC_KEYUSER_REALM)))
                .thenReturn(CompletableFuture.completedFuture(Pair.of("1", new UserData(Set.of(new Role("role")), "22", null, null))));

        Response data = RESOURCES.target("users/" + JITSTATIC_KEYUSER_REALM + "/keyadmin")
                .request()
                .header(AUTHORIZATION, BASIC_AUTH_CRED)
                .get();

        assertNotNull(data);
        assertEquals(OK_200, data.getStatus());
        UserData entity = data.readEntity(UserData.class);
        assertTrue(entity.getRoles().equals(Set.of(new Role("role"))));
        assertEquals("22", entity.getBasicPassword());
        EntityTag entityTag = data.getEntityTag();
        assertNotNull(entityTag);
        assertEquals("1", entityTag.getValue());
        data.close();
    }

    @Test
    public void testGetKeyUserWithNoUser() {
        Response data = RESOURCES.target("users/" + JITSTATIC_KEYUSER_REALM + "/keyadmin").request().get();
        assertEquals(UNAUTHORIZED_401, data.getStatus());
        data.close();
    }

    @Test
    public void testGetKeyUserWithWrongUser() throws RefNotFoundException {
        String user = "user";
        String pass = "pass";
        when(storage.getUser(eq(user), eq(REFS_HEADS_MASTER), eq(JITSTATIC_KEYUSER_REALM)))
                .thenReturn(CompletableFuture.completedFuture(new UserData(ROOTROLES, "pass", null, null)));
        when(storage.getUser(eq(user), eq(null), eq(JITSTATIC_KEYUSER_REALM)))
                .thenReturn(CompletableFuture.completedFuture(new UserData(ROOTROLES, pass, null, null)));
        Response data = RESOURCES.target("users/" + JITSTATIC_KEYUSER_REALM + "/keyadmin")
                .request()
                .header(AUTHORIZATION, createCreds(user, pass))
                .get();
        assertEquals(FORBIDDEN_403, data.getStatus());
        data.close();
    }

    @Test
    public void testGetKeyUserNotChanged() throws RefNotFoundException {
        when(storage.getUser(eq(PUSER), eq("refs/heads/" + GIT_SECRETS), eq(JITSTATIC_KEYADMIN_REALM)))
                .thenReturn(CompletableFuture.completedFuture(new UserData(ROOTROLES, PSECRET, null, null)));
        when(storage.getUserData(eq("keyadmin"), eq(REFS_HEADS_MASTER), eq(JITSTATIC_KEYUSER_REALM)))
                .thenReturn(CompletableFuture.completedFuture(Pair.of("1", new UserData(Set.of(new Role("role")), "22", null, null))));

        Response data = RESOURCES.target("users/" + JITSTATIC_KEYUSER_REALM + "/keyadmin").request()
                .header(AUTHORIZATION, BASIC_AUTH_CRED).get();
        EntityTag entityTag = data.getEntityTag();
        assertNotNull(entityTag);
        data.close();
        data = RESOURCES.target("users/" + JITSTATIC_KEYUSER_REALM + "/keyadmin").request().header("if-match", "\"" + entityTag.getValue() + "\"")
                .header(AUTHORIZATION, BASIC_AUTH_CRED).get();
        assertEquals(entityTag.getValue(), data.getEntityTag().getValue());
        data.close();
    }

    @Test
    public void testPutKeyUserWithNoUser() {
        Response data = RESOURCES.target("users/" + JITSTATIC_KEYUSER_REALM + "/keyadmin").request()
                .put(Entity.entity(new io.jitstatic.api.UserData(Set.of(new Role("role")), "22", null), APPLICATION_JSON));
        assertEquals(UNAUTHORIZED_401, data.getStatus());
        data.close();
    }

    @Test
    public void testPutKeyUserWithUserButNotValid() throws RefNotFoundException {
        when(authen.test(PUSER, PSECRET)).thenReturn(false);
        when(storage.getUser(eq(PUSER), eq(null), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        when(storage.getUser(eq(PUSER), eq(JitStaticConstants.REFS_HEADS_SECRETS), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        Response data = RESOURCES.target("users/" + JITSTATIC_KEYUSER_REALM + "/keyadmin")
                .request()
                .header(AUTHORIZATION, BASIC_AUTH_CRED)
                .header(HttpHeaders.IF_MATCH, "\"" + 2 + "\"")
                .put(Entity.entity(new io.jitstatic.api.UserData(Set.of(new Role("role")), "22", null), APPLICATION_JSON));
        assertEquals(UNAUTHORIZED_401, data.getStatus());
        data.close();
    }

    @Test
    public void testPutKeyUserWithUserButNotFound() throws RefNotFoundException {
        when(storage.getUserData(eq("keyadmin"), eq(REFS_HEADS_MASTER), eq(JITSTATIC_KEYUSER_REALM))).thenReturn(CompletableFuture.completedFuture(null));

        Response data = RESOURCES.target("users/" + JITSTATIC_KEYUSER_REALM + "/keyadmin").request()
                .header(AUTHORIZATION, BASIC_AUTH_CRED)
                .header(HttpHeaders.IF_MATCH, "\"" + 2 + "\"")
                .put(Entity.entity(new io.jitstatic.api.UserData(Set.of(new Role("role")), "22", null), APPLICATION_JSON));
        assertEquals(NOT_FOUND_404, data.getStatus());
        data.close();
    }

    @Test
    public void testPutKeyUserWithUserButFoundWrongEtag() throws RefNotFoundException {
        when(storage.getUserData(eq("keyadmin"), eq(REFS_HEADS_MASTER), eq(JITSTATIC_KEYUSER_REALM)))
                .thenReturn(CompletableFuture.completedFuture(Pair.of("1", new UserData(Set.of(new Role("role")), "22", null, null))));

        Response data = RESOURCES.target("users/" + JITSTATIC_KEYUSER_REALM + "/keyadmin").request().header(AUTHORIZATION, BASIC_AUTH_CRED)
                .header(HttpHeaders.IF_MATCH, "\"" + 2 + "\"")
                .put(Entity.entity(new io.jitstatic.api.UserData(Set.of(new Role("role")), "20", null), APPLICATION_JSON));
        assertEquals(PRECONDITION_FAILED_412, data.getStatus());
        data.close();
    }

    @Test
    public void testPutKeyUserWithUserButFailedToLock() throws RefNotFoundException {
        UserData userData = new UserData(Set.of(new Role("role")), "22", null, null);
        when(storage.getUserData(eq("keyadmin"), eq(REFS_HEADS_MASTER), eq(JITSTATIC_KEYUSER_REALM)))
                .thenReturn(CompletableFuture.completedFuture(Pair.of("1", new UserData(Set.of(new Role("role")), "22", null, null))));
        when(storage.updateUser(eq("keyadmin"), eq(REFS_HEADS_MASTER), eq(JITSTATIC_KEYUSER_REALM), eq(PUSER), eq(userData), eq("1")))
                .thenReturn(CompletableFuture.completedFuture(Either.right(new FailedToLock("ref"))));
        Response data = RESOURCES.target("users/" + JITSTATIC_KEYUSER_REALM + "/keyadmin").request()
                .header(AUTHORIZATION, BASIC_AUTH_CRED)
                .header(HttpHeaders.IF_MATCH, "\"" + 1 + "\"")
                .put(Entity.entity(new io.jitstatic.api.UserData(Set.of(new Role("role")), "22", null), APPLICATION_JSON));
        assertEquals(PRECONDITION_FAILED_412, data.getStatus());
        data.close();
        verify(storage, times(1)).updateUser(eq("keyadmin"), eq(REFS_HEADS_MASTER), eq(JITSTATIC_KEYUSER_REALM), eq(PUSER), eq(userData), eq("1"));
    }

    @Test
    public void testPutKeyUserWithUserButRemoved() throws RefNotFoundException {
        UserData userData = new UserData(Set.of(new Role("role")), "22", null, null);
        when(storage.getUserData(eq("keyadmin"), eq(REFS_HEADS_MASTER), eq(JITSTATIC_KEYUSER_REALM)))
                .thenReturn(CompletableFuture.completedFuture(Pair.of("1", new UserData(Set.of(new Role("role")), "22", null, null))));
        when(storage.updateUser(eq("keyadmin"), eq(REFS_HEADS_MASTER), eq(JITSTATIC_KEYUSER_REALM), eq(PUSER), eq(userData), eq("1")))
                .thenReturn(CompletableFuture.completedFuture(Either.left(null)));
        Response data = RESOURCES.target("users/" + JITSTATIC_KEYUSER_REALM + "/keyadmin").request()
                .header(AUTHORIZATION, BASIC_AUTH_CRED)
                .header(HttpHeaders.IF_MATCH, "\"" + 1 + "\"")
                .put(Entity.entity(new io.jitstatic.api.UserData(Set.of(new Role("role")), "22", null), APPLICATION_JSON));
        assertEquals(NOT_FOUND_404, data.getStatus());
        data.close();
        verify(storage, times(1)).updateUser(eq("keyadmin"), eq(REFS_HEADS_MASTER), eq(JITSTATIC_KEYUSER_REALM), eq(PUSER), eq(userData), eq("1"));
    }

    @Test
    public void testPutKeyUserWithUser() throws RefNotFoundException {
        UserData userData = new UserData(Set.of(new Role("role")), "22", null, null);
        when(storage.getUserData(eq("keyadmin"), eq(REFS_HEADS_MASTER), eq(JITSTATIC_KEYUSER_REALM)))
                .thenReturn(CompletableFuture.completedFuture(Pair.of("1", new UserData(Set.of(new Role("role")), "22", null, null))));
        when(storage.updateUser(eq("keyadmin"), eq(REFS_HEADS_MASTER), eq(JITSTATIC_KEYUSER_REALM), eq(PUSER), eq(userData), eq("1")))
                .thenReturn(CompletableFuture.completedFuture(Either.left("2")));
        Response data = RESOURCES.target("users/" + JITSTATIC_KEYUSER_REALM + "/keyadmin").request()
                .header(AUTHORIZATION, BASIC_AUTH_CRED)
                .header(HttpHeaders.IF_MATCH, "\"" + 1 + "\"")
                .put(Entity.entity(new io.jitstatic.api.UserData(Set.of(new Role("role")), "22", null), APPLICATION_JSON));
        assertEquals("2", data.getEntityTag().getValue());
        data.close();
        verify(storage, times(1)).updateUser(eq("keyadmin"), eq(REFS_HEADS_MASTER), eq(JITSTATIC_KEYUSER_REALM), eq(PUSER), eq(userData), eq("1"));
    }

    @Test
    public void testPostKeyUserWithNoUser() {
        Response response = RESOURCES.target("users/" + JITSTATIC_KEYUSER_REALM + "/keyadmin")
                .request()
                .post(Entity.entity(new io.jitstatic.api.UserData(Set.of(new Role("role")), "22", null), APPLICATION_JSON));
        assertEquals(UNAUTHORIZED_401, response.getStatus());
        response.close();
    }

    @Test
    public void testPostKeyUserWithWrongUser() throws RefNotFoundException {
        when(authen.test(PUSER, PSECRET)).thenReturn(false);
        when(storage.getUser(eq(PUSER), eq(null), eq(JITSTATIC_KEYUSER_REALM)))
                .thenReturn(CompletableFuture.completedFuture(new UserData(Set.of(new Role("role")), PSECRET, null, null)));
        Response response = RESOURCES.target("users/" + JITSTATIC_KEYUSER_REALM + "/keyadmin")
                .request()
                .header(AUTHORIZATION, BASIC_AUTH_CRED)
                .post(Entity.entity(new io.jitstatic.api.UserData(Set.of(new Role("role")), "22", null), APPLICATION_JSON));
        assertEquals(FORBIDDEN_403, response.getStatus());
        response.close();
    }

    @Test
    public void testPostKeyUserWithUserButExist() throws RefNotFoundException {
        when(storage.getUserData(eq("keyadmin"), eq(REFS_HEADS_MASTER), eq(JITSTATIC_KEYUSER_REALM)))
                .thenReturn(CompletableFuture.completedFuture(Pair.of("1", new UserData(Set.of(new Role("role")), "22", null, null))));
        Response response = RESOURCES.target("users/" + JITSTATIC_KEYUSER_REALM + "/keyadmin")
                .request()
                .header(AUTHORIZATION, BASIC_AUTH_CRED)
                .post(Entity.entity(new io.jitstatic.api.UserData(Set.of(new Role("role")), "22", null), APPLICATION_JSON));
        assertEquals(CONFLICT_409, response.getStatus());
        response.close();
    }

    @Test
    public void testPostKeyUserWithUser() throws RefNotFoundException {
        when(storage.getUserData(anyString(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(storage.addUser(eq("keyadmin"), eq(REFS_HEADS_MASTER), eq(JITSTATIC_KEYUSER_REALM), eq(PUSER), any()))
                .thenReturn(CompletableFuture.completedFuture("22"));
        Response response = RESOURCES.target("users/" + JITSTATIC_KEYUSER_REALM + "/keyadmin").request().header(AUTHORIZATION, BASIC_AUTH_CRED)
                .post(Entity.entity(new io.jitstatic.api.UserData(Set.of(new Role("role")), "22", null), APPLICATION_JSON));
        assertEquals(OK_200, response.getStatus());
        assertEquals("22", response.getEntityTag().getValue());
        response.close();
    }

    @Test
    public void testDeleteKeyUserWithoutUser() {
        Response response = RESOURCES.target("users/" + JITSTATIC_KEYUSER_REALM + "/keyadmin").request().delete();
        assertEquals(UNAUTHORIZED_401, response.getStatus());
        response.close();
    }

    @Test
    public void testDeleteKeyUserWithWrongUser() throws RefNotFoundException {
        String user = "user";
        String pass = "pass";
        when(storage.getUser(eq(user), eq(null), eq(JITSTATIC_KEYUSER_REALM)))
                .thenReturn(CompletableFuture.completedFuture(new UserData(ROOTROLES, pass, null, null)));
        Response response = RESOURCES.target("users/" + JITSTATIC_KEYUSER_REALM + "/keyadmin").request().header(AUTHORIZATION, createCreds(user, pass))
                .delete();
        assertEquals(FORBIDDEN_403, response.getStatus());
        response.close();
    }

    @Test
    public void testDeleteKeyUserNotFound() throws RefNotFoundException {
        UserData userData = mock(UserData.class);
        when(storage.getUser(eq(PUSER), any(), eq(JITSTATIC_KEYUSER_REALM))).thenReturn(CompletableFuture.completedFuture(userData));
        when(userData.getBasicPassword()).thenReturn(PSECRET);
        when(authen.test(PUSER, PSECRET)).thenReturn(false);
        Response response = RESOURCES.target("users/" + JITSTATIC_KEYUSER_REALM + "/keyadmin")
                .request()
                .header(AUTHORIZATION, BASIC_AUTH_CRED)
                .delete();
        assertEquals(FORBIDDEN_403, response.getStatus());
        response.close();
    }

    @Test
    public void testDeleteKeyUserAsAdmin() throws RefNotFoundException {
        when(storage.getUserData(eq("keyadmin"), eq(REFS_HEADS_MASTER), eq(JITSTATIC_KEYUSER_REALM)))
                .thenReturn(CompletableFuture.completedFuture(Pair.of("1", new UserData(Set.of(new Role("role")), "22", null, null))));
        Response response = RESOURCES.target("users/" + JITSTATIC_KEYUSER_REALM + "/keyadmin")
                .request()
                .header(AUTHORIZATION, BASIC_AUTH_CRED)
                .delete();
        assertEquals(OK_200, response.getStatus());
        response.close();
        verify(storage, times(1)).deleteUser(Mockito.eq("keyadmin"), eq(REFS_HEADS_MASTER), eq(JITSTATIC_KEYUSER_REALM), eq(PUSER));
    }

    @Test
    public void testDeleteKeyUser() throws RefNotFoundException {
        UserData userData = mock(UserData.class);
        when(storage.getUserData(eq("keyadmin"), eq(REFS_HEADS_MASTER), eq(JITSTATIC_KEYUSER_REALM)))
                .thenReturn(CompletableFuture.completedFuture(Pair.of("1", new UserData(Set.of(new Role("role")), "22", null, null))));
        when(userData.getBasicPassword()).thenReturn("22");
        when(authen.test(PUSER, PSECRET)).thenReturn(false);
        when(storage.getUser(eq("keyadmin"), eq(null), eq(JITSTATIC_KEYUSER_REALM))).thenReturn(CompletableFuture.completedFuture(userData));
        Response response = RESOURCES.target("users/" + JITSTATIC_KEYUSER_REALM + "/keyadmin")
                .request()
                .header(AUTHORIZATION, createCreds("keyadmin", "22"))
                .delete();
        assertEquals(OK_200, response.getStatus());
        response.close();
        verify(storage, times(1)).deleteUser(Mockito.eq("keyadmin"), eq(REFS_HEADS_MASTER), eq(JITSTATIC_KEYUSER_REALM), eq("keyadmin"));
    }

    private static String createCreds(String user, String secret) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + secret).getBytes(UTF_8));
    }

}
