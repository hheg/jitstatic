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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import javax.ws.rs.ForbiddenException;
import javax.ws.rs.NotAuthorizedException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.http.HttpHeaders;
import org.apache.http.HttpStatus;
import org.glassfish.jersey.server.filter.RolesAllowedDynamicFeature;
import org.glassfish.jersey.test.grizzly.GrizzlyWebTestContainerFactory;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import com.fasterxml.jackson.databind.JsonNode;
import com.spencerwi.either.Either;

import io.dropwizard.auth.AuthDynamicFeature;
import io.dropwizard.auth.AuthValueFactoryProvider;
import io.dropwizard.auth.basic.BasicCredentialAuthFilter;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.dropwizard.testing.junit5.ResourceExtension;
import io.jitstatic.JitStaticConstants;
import io.jitstatic.MetaData;
import io.jitstatic.auth.ConfiguratedAuthenticator;
import io.jitstatic.auth.KeyAdminAuthenticatorImpl;
import io.jitstatic.auth.User;
import io.jitstatic.storage.HashService;
import io.jitstatic.storage.Storage;
import io.jitstatic.utils.Pair;
import io.jitstatic.utils.WrappingAPIException;

@ExtendWith(DropwizardExtensionsSupport.class)
public class MetaKeyResourceTest {

    private static final String REFS_HEADS_MASTER = "refs/heads/master";
    private static final Charset UTF_8 = StandardCharsets.UTF_8;
    private static final String PUSER = "puser";
    private static final String PSECRET = "psecret";

    private static final String BASIC_AUTH_CRED = createCreds(PUSER, PSECRET);
    private static final String BASIC_AUTH_CRED_2 = createCreds("not", "right");

    private Storage storage = mock(Storage.class);
    private HashService hashService = new HashService();

    public ResourceExtension RESOURCES = ResourceExtension.builder().setTestContainerFactory(new GrizzlyWebTestContainerFactory())
            .addProvider(new AuthDynamicFeature(new BasicCredentialAuthFilter.Builder<User>()
                    .setAuthenticator(new ConfiguratedAuthenticator())
                    .setRealm(JitStaticConstants.JITSTATIC_KEYUSER_REALM)
                    .setAuthorizer((User u, String r) -> true)
                    .buildAuthFilter()))
            .addProvider(RolesAllowedDynamicFeature.class).addProvider(new AuthValueFactoryProvider.Binder<>(User.class))
            .addResource(new MetaKeyResource(storage,
                    new KeyAdminAuthenticatorImpl(storage, (user, ref) -> new User(PUSER, PSECRET).equals(user), REFS_HEADS_MASTER, hashService),
                    REFS_HEADS_MASTER, hashService))
            .build();

    @AfterEach
    public void tearDown() {
        Mockito.reset(storage);
    }

    @Test
    public void testUserKeyWithoutUser() {
        assertEquals("HTTP 401 Unauthorized",
                assertThrows(NotAuthorizedException.class, () -> RESOURCES.target("/metakey/dog").request().get(JsonNode.class)).getLocalizedMessage());
    }

    @Test
    public void testUserKeyWithUser() {
        Mockito.when(storage.getMetaKey(Mockito.eq("dog"), Mockito.eq(REFS_HEADS_MASTER))).thenReturn(CompletableFuture.completedFuture(Pair.of(new MetaData(Set.of(new User("name", "pass"))), "1")));
        assertEquals("HTTP 403 Forbidden",
                assertThrows(ForbiddenException.class,
                        () -> RESOURCES.target("/metakey/dog").request().header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED_2).get(JsonNode.class))
                                .getLocalizedMessage());
    }

    @Test
    public void testGetUserKeyWithWrongRef() {
        assertEquals("HTTP 404 Not Found", assertThrows(NotFoundException.class, () -> RESOURCES.target("/metakey/dog").queryParam("ref", "master").request()
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED).get(JsonNode.class)).getLocalizedMessage());
    }

    @Test
    public void testGetAKey() {
        MetaData storageData = new MetaData(new HashSet<>(), null, false, false, List.of(), null, null);
        Mockito.when(storage.getMetaKey("dog", REFS_HEADS_MASTER)).thenReturn(CompletableFuture.completedFuture(Pair.of(storageData, "metadataversion")));
        Response response = RESOURCES.target("/metakey/dog").request().header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED).get();
        assertThat("metadataversion", Matchers.is(response.getEntityTag().getValue()));
        assertThat(HttpStatus.SC_OK, Matchers.is(response.getStatus()));
        assertThat(MediaType.APPLICATION_JSON_TYPE, Matchers.is(response.getMediaType()));
        assertThat(storageData, Matchers.is(response.readEntity(MetaData.class)));
        response.close();
    }

    @Test
    public void testModifyAKeyWithoutuser() {
        MetaData storageData = new MetaData(new HashSet<>(), null, false, false, List.of(), Set.of(), Set.of());
        ModifyMetaKeyData mukd = new ModifyMetaKeyData();
        mukd.setMessage("message");
        mukd.setUserInfo("userinfo");
        mukd.setUserMail("usermail");
        mukd.setMetaData(storageData);
        Response put = RESOURCES.target("/metakey/dog").request().put(Entity.json(mukd));
        assertThat(put.getStatus(), Matchers.is(HttpStatus.SC_UNAUTHORIZED));
        put.close();
    }

    @Test
    public void testModifyAKeyWithWrongUser() {
        MetaData storageData = new MetaData(Set.of(), null, false, false, List.of(), Set.of(), Set.of());
        ModifyMetaKeyData mukd = new ModifyMetaKeyData();
        mukd.setMessage("message");
        mukd.setUserInfo("userinfo");
        mukd.setUserMail("usermail");
        mukd.setMetaData(storageData);
        Mockito.when(storage.getMetaKey("dog", REFS_HEADS_MASTER)).thenReturn(CompletableFuture.completedFuture(Pair.of(storageData, "metadataversion")));
        Response put = RESOURCES.target("/metakey/dog").request().header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED_2)
                .header(HttpHeaders.IF_MATCH, "\"version\"").put(Entity.json(mukd));
        assertThat(put.getStatus(), Matchers.is(HttpStatus.SC_FORBIDDEN));
        put.close();
    }

    @Test
    public void testModifyAKeyWithWrongVersion() {
        MetaData storageData = new MetaData(Set.of(), null, false, false, List.of(), Set.of(), Set.of());
        ModifyMetaKeyData mukd = new ModifyMetaKeyData();
        mukd.setMessage("message");
        mukd.setUserInfo("userinfo");
        mukd.setUserMail("usermail");
        mukd.setMetaData(storageData);
        Mockito.when(storage.getMetaKey("dog", REFS_HEADS_MASTER)).thenReturn(CompletableFuture.completedFuture(Pair.of(storageData, "metadataversion")));
        Response put = RESOURCES.target("/metakey/dog").request().header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED).header(HttpHeaders.IF_MATCH, "\"2\"")
                .put(Entity.json(mukd));
        assertThat(put.getStatus(), Matchers.is(HttpStatus.SC_PRECONDITION_FAILED));
        put.close();
    }

    @Test
    public void testModifyAKey() {
        MetaData storageData = new MetaData(Set.of(), null, false, false, List.of(), Set.of(), Set.of());
        ModifyMetaKeyData mukd = new ModifyMetaKeyData();
        mukd.setMessage("message");
        mukd.setUserInfo("userinfo");
        mukd.setUserMail("usermail");
        mukd.setMetaData(storageData);
        Mockito.when(storage.getMetaKey("dog", REFS_HEADS_MASTER)).thenReturn(CompletableFuture.completedFuture(Pair.of(storageData, "2")));
        Mockito.when(storage.putMetaData(Mockito.eq("dog"), Mockito.eq(REFS_HEADS_MASTER), Mockito.isA(MetaData.class), Mockito.eq("2"), Mockito.any()))
                .thenReturn(CompletableFuture.completedFuture(Either.left("3")));
        Response put = RESOURCES.target("/metakey/dog").request().header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED).header(HttpHeaders.IF_MATCH, "\"2\"")
                .put(Entity.json(mukd));
        assertThat(put.getStatus(), Matchers.is(HttpStatus.SC_OK));
        assertThat(put.getEntityTag().getValue(), Matchers.equalTo("3"));
        put.close();
    }

    @Test
    public void testModifyAKeyWithMalformedKeyData() {
        MetaData storageData = new MetaData(new HashSet<>(), null, false, false, List.of(), null, null);
        ModifyMetaKeyData mukd = new ModifyMetaKeyData();
        mukd.setUserInfo("userinfo");
        mukd.setUserMail("usermail");
        mukd.setMetaData(storageData);
        Mockito.when(storage.getMetaKey("dog", REFS_HEADS_MASTER)).thenReturn(CompletableFuture.completedFuture(Pair.of(storageData, "metadataversion")));
        Response put = RESOURCES.target("/metakey/dog").request().header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED).header(HttpHeaders.IF_MATCH, "\"2\"")
                .put(Entity.json(mukd));
        assertThat(put.getStatus(), Matchers.is(422));
        put.close();
    }

    @Test
    public void testGetMasterMetaData() {
        MetaData storageData = new MetaData(new HashSet<>(), null, false, false, List.of(), null, null);
        Mockito.when(storage.getMetaKey("dog/", REFS_HEADS_MASTER)).thenReturn(CompletableFuture.completedFuture(Pair.of(storageData, "metadataversion")));
        Response response = RESOURCES.target("/metakey/dog/").request().header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED).get();
        assertThat("metadataversion", Matchers.is(response.getEntityTag().getValue()));
        assertThat(HttpStatus.SC_OK, Matchers.is(response.getStatus()));
        assertThat(MediaType.APPLICATION_JSON_TYPE, Matchers.is(response.getMediaType()));
        assertThat(storageData, Matchers.is(response.readEntity(MetaData.class)));
        response.close();
    }

    @Test
    public void testModifyAMasterMetaData() {
        MetaData storageData = new MetaData(Set.of(), null, false, false, List.of(), Set.of(), Set.of());
        ModifyMetaKeyData mukd = new ModifyMetaKeyData();
        mukd.setMessage("message");
        mukd.setUserInfo("userinfo");
        mukd.setUserMail("usermail");
        mukd.setMetaData(storageData);
        Mockito.when(storage.getMetaKey("dog/", REFS_HEADS_MASTER)).thenReturn(CompletableFuture.completedFuture(Pair.of(storageData, "2")));
        Mockito.when(storage.putMetaData(Mockito.eq("dog/"), Mockito.eq(REFS_HEADS_MASTER), Mockito.isA(MetaData.class), Mockito.eq("2"), Mockito.any()))
                .thenReturn(CompletableFuture.completedFuture(Either.left("3")));
        Response put = RESOURCES.target("/metakey/dog/").request().header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED).header(HttpHeaders.IF_MATCH, "\"2\"")
                .put(Entity.json(mukd));
        assertThat(put.getStatus(), Matchers.is(HttpStatus.SC_OK));
        assertThat(put.getEntityTag().getValue(), Matchers.equalTo("3"));
        put.close();
    }

    @Test
    public void testGetMetaKeyUnsupported() {
        Mockito.when(storage.getMetaKey("dog", REFS_HEADS_MASTER)).thenThrow(new WrappingAPIException(new UnsupportedOperationException("Test")));
        Response response = RESOURCES.target("/metakey/dog").request().header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED).get();
        assertEquals(HttpStatus.SC_METHOD_NOT_ALLOWED, response.getStatus());
    }

    @Test
    public void testGetMetaKeyUnknownAPIException() {
        Mockito.when(storage.getMetaKey("dog", REFS_HEADS_MASTER)).thenThrow(new WrappingAPIException(new RuntimeException("Test")));
        Response response = RESOURCES.target("/metakey/dog").request().header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED).get();
        assertEquals(HttpStatus.SC_INTERNAL_SERVER_ERROR, response.getStatus());
    }

    @Test
    public void testGetMetaKeyUnknown() {
        Mockito.when(storage.getMetaKey("dog", REFS_HEADS_MASTER)).thenThrow(new RuntimeException("Test"));
        Response response = RESOURCES.target("/metakey/dog").request().header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED).get();
        assertEquals(HttpStatus.SC_INTERNAL_SERVER_ERROR, response.getStatus());
    }

    private static String createCreds(String user, String secret) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + secret).getBytes(UTF_8));
    }
}
