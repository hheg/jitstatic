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
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import javax.ws.rs.BadRequestException;
import javax.ws.rs.ForbiddenException;
import javax.ws.rs.NotAuthorizedException;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.http.HttpHeaders;
import org.apache.http.HttpStatus;
import org.eclipse.jgit.api.errors.RefNotFoundException;
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
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.dropwizard.testing.junit5.ResourceExtension;
import io.jitstatic.JitStaticConstants;
import io.jitstatic.MetaData;
import io.jitstatic.Role;
import io.jitstatic.auth.UrlAwareBasicCredentialAuthFilter;
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
            .addProvider(new AuthDynamicFeature(new UrlAwareBasicCredentialAuthFilter<>(storage, hashService, (u, p) -> u.equals(PUSER) && p.equals(PSECRET))))
            .addProvider(new AuthValueFactoryProvider.Binder<>(User.class))
            .addResource(new MetaKeyResource(storage, REFS_HEADS_MASTER))
            .build();

    @AfterEach
    public void tearDown() {
        Mockito.reset(storage);
    }

    @Test
    public void testUserKeyWithoutUser() {
        assertEquals("HTTP 401 Unauthorized", assertThrows(NotAuthorizedException.class, () -> RESOURCES.target("/metakey/dog").request().get(JsonNode.class))
                .getLocalizedMessage());
    }

    @Test
    public void testUserKeyWithUser() throws RefNotFoundException {
        io.jitstatic.auth.UserData userData = Mockito.mock(io.jitstatic.auth.UserData.class);
        Mockito.when(storage.getUser(Mockito.eq("not"),Mockito.any(),Mockito.eq(JitStaticConstants.JITSTATIC_KEYUSER_REALM))).thenReturn(userData);
        Mockito.when(storage.getMetaKey(Mockito.eq("dog"), Mockito.eq(REFS_HEADS_MASTER)))
                .thenReturn(CompletableFuture
                        .completedFuture(Pair.of(new MetaData(null, false, false, List.of(), Set.of(new Role("read")), Set.of(new Role("write"))), "1")));
        Mockito.when(userData.getBasicPassword()).thenReturn("right");
        Mockito.when(userData.getRoles()).thenReturn(Set.of(new Role("other")));
        assertEquals("HTTP 403 Forbidden", assertThrows(ForbiddenException.class, () -> RESOURCES.target("/metakey/dog")
                .request()
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED_2)
                .get(JsonNode.class))
                        .getLocalizedMessage());
    }

    @Test
    public void testGetUserKeyWithWrongRef() {
        assertEquals("HTTP 400 Bad Request", assertThrows(BadRequestException.class, () -> RESOURCES.target("/metakey/dog")
                .queryParam("ref", "master")
                .request()
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED)
                .get(JsonNode.class)).getLocalizedMessage());
    }

    @Test
    public void testGetAKey() throws RefNotFoundException {
        MetaData storageData = new MetaData(null, false, false, List.of(), Set.of(new Role("read")), Set.of(new Role("write")));
        Mockito.when(storage.getMetaKey("dog", REFS_HEADS_MASTER)).thenReturn(CompletableFuture.completedFuture(Pair.of(storageData, "metadataversion")));
        Response response = RESOURCES.target("/metakey/dog")
                .request()
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED)
                .get();
        assertThat("metadataversion", Matchers.is(response.getEntityTag().getValue()));
        assertThat(HttpStatus.SC_OK, Matchers.is(response.getStatus()));
        assertThat(MediaType.APPLICATION_JSON_TYPE, Matchers.is(response.getMediaType()));
        assertThat(storageData, Matchers.is(response.readEntity(MetaData.class)));
        response.close();
    }

    @Test
    public void testModifyAKeyWithoutuser() {
        MetaData storageData = new MetaData(null, false, false, List.of(), Set.of(), Set.of());
        ModifyMetaKeyData mukd = new ModifyMetaKeyData();
        mukd.setMessage("message");
        mukd.setUserInfo("userinfo");
        mukd.setUserMail("usermail");
        mukd.setMetaData(storageData);
        Response put = RESOURCES.target("/metakey/dog")
                .request()
                .put(Entity.json(mukd));
        assertThat(put.getStatus(), Matchers.is(HttpStatus.SC_UNAUTHORIZED));
        put.close();
    }

    @Test
    public void testModifyAKeyWithWrongUser() throws RefNotFoundException {
        MetaData storageData = new MetaData(null, false, false, List.of(), Set.of(), Set.of());
        ModifyMetaKeyData mukd = new ModifyMetaKeyData();
        mukd.setMessage("message");
        mukd.setUserInfo("userinfo");
        mukd.setUserMail("usermail");
        mukd.setMetaData(storageData);
        Mockito.when(storage.getMetaKey("dog", REFS_HEADS_MASTER)).thenReturn(CompletableFuture.completedFuture(Pair.of(storageData, "metadataversion")));
        Response put = RESOURCES.target("/metakey/dog").request()
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED_2)
                .header(HttpHeaders.IF_MATCH, "\"version\"")
                .put(Entity.json(mukd));
        assertThat(put.getStatus(), Matchers.is(HttpStatus.SC_UNAUTHORIZED));
        put.close();
    }

    @Test
    public void testModifyAKeyWithWrongVersion() throws RefNotFoundException {
        MetaData storageData = new MetaData(null, false, false, List.of(), Set.of(), Set.of());
        ModifyMetaKeyData mukd = new ModifyMetaKeyData();
        mukd.setMessage("message");
        mukd.setUserInfo("userinfo");
        mukd.setUserMail("usermail");
        mukd.setMetaData(storageData);
        Mockito.when(storage.getMetaKey("dog", REFS_HEADS_MASTER)).thenReturn(CompletableFuture.completedFuture(Pair.of(storageData, "metadataversion")));
        Response put = RESOURCES.target("/metakey/dog")
                .request()
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED)
                .header(HttpHeaders.IF_MATCH, "\"2\"")
                .put(Entity.json(mukd));
        assertThat(put.getStatus(), Matchers.is(HttpStatus.SC_PRECONDITION_FAILED));
        put.close();
    }

    @Test
    public void testModifyAKey() throws RefNotFoundException {
        MetaData storageData = new MetaData(null, false, false, List.of(), Set.of(new Role("read")), Set.of(new Role("write")));
        ModifyMetaKeyData mukd = new ModifyMetaKeyData();
        mukd.setMessage("message");
        mukd.setUserInfo("userinfo");
        mukd.setUserMail("usermail");
        mukd.setMetaData(storageData);
        Mockito.when(storage.getMetaKey("dog", REFS_HEADS_MASTER)).thenReturn(CompletableFuture.completedFuture(Pair.of(storageData, "2")));
        Mockito.when(storage.putMetaData(Mockito.eq("dog"), Mockito.eq(REFS_HEADS_MASTER), Mockito.isA(MetaData.class), Mockito.eq("2"), Mockito.any()))
                .thenReturn(CompletableFuture.completedFuture(Either.left("3")));
        Response put = RESOURCES.target("/metakey/dog")
                .request()
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED)
                .header(HttpHeaders.IF_MATCH, "\"2\"")
                .put(Entity.json(mukd));
        assertThat(put.getStatus(), Matchers.is(HttpStatus.SC_OK));
        assertThat(put.getEntityTag().getValue(), Matchers.equalTo("3"));
        put.close();
    }

    @Test
    public void testModifyAKeyWithMalformedKeyData() throws RefNotFoundException {
        MetaData storageData = new MetaData(null, false, false, List.of(), Set.of(new Role("read")), Set.of(new Role("write")));
        ModifyMetaKeyData mukd = new ModifyMetaKeyData();
        mukd.setUserInfo("userinfo");
        mukd.setUserMail("usermail");
        mukd.setMetaData(storageData);
        Mockito.when(storage.getMetaKey("dog", REFS_HEADS_MASTER)).thenReturn(CompletableFuture.completedFuture(Pair.of(storageData, "metadataversion")));
        Response put = RESOURCES.target("/metakey/dog")
                .request()
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED)
                .header(HttpHeaders.IF_MATCH, "\"2\"")
                .put(Entity.json(mukd));
        assertThat(put.getStatus(), Matchers.is(422));
        put.close();
    }

    @Test
    public void testGetMasterMetaData() throws RefNotFoundException {
        MetaData storageData = new MetaData(null, false, false, List.of(), Set.of(new Role("read")), Set.of(new Role("write")));
        Mockito.when(storage.getMetaKey("dog/", REFS_HEADS_MASTER)).thenReturn(CompletableFuture.completedFuture(Pair.of(storageData, "metadataversion")));
        Response response = RESOURCES.target("/metakey/dog/")
                .request()
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED)
                .get();
        assertThat("metadataversion", Matchers.is(response.getEntityTag().getValue()));
        assertThat(HttpStatus.SC_OK, Matchers.is(response.getStatus()));
        assertThat(MediaType.APPLICATION_JSON_TYPE, Matchers.is(response.getMediaType()));
        assertThat(storageData, Matchers.is(response.readEntity(MetaData.class)));
        response.close();
    }

    @Test
    public void testModifyAMasterMetaData() throws RefNotFoundException {
        MetaData storageData = new MetaData(null, false, false, List.of(), Set.of(new Role("read")), Set.of(new Role("write")));
        ModifyMetaKeyData mukd = new ModifyMetaKeyData();
        mukd.setMessage("message");
        mukd.setUserInfo("userinfo");
        mukd.setUserMail("usermail");
        mukd.setMetaData(storageData);
        Mockito.when(storage.getMetaKey("dog/", REFS_HEADS_MASTER)).thenReturn(CompletableFuture.completedFuture(Pair.of(storageData, "2")));
        Mockito.when(storage.putMetaData(Mockito.eq("dog/"), Mockito.eq(REFS_HEADS_MASTER), Mockito.isA(MetaData.class), Mockito.eq("2"), Mockito.any()))
                .thenReturn(CompletableFuture.completedFuture(Either.left("3")));
        Response put = RESOURCES.target("/metakey/dog/")
                .request()
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED)
                .header(HttpHeaders.IF_MATCH, "\"2\"")
                .put(Entity.json(mukd));
        assertThat(put.getStatus(), Matchers.is(HttpStatus.SC_OK));
        assertThat(put.getEntityTag().getValue(), Matchers.equalTo("3"));
        put.close();
    }

    @Test
    public void testGetMetaKeyUnsupported() throws RefNotFoundException {
        Mockito.when(storage.getMetaKey("dog", REFS_HEADS_MASTER)).thenThrow(new WrappingAPIException(new UnsupportedOperationException("Test")));
        Response response = RESOURCES.target("/metakey/dog")
                .request()
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED)
                .get();
        assertEquals(HttpStatus.SC_METHOD_NOT_ALLOWED, response.getStatus());
    }

    @Test
    public void testGetMetaKeyUnknownAPIException() throws RefNotFoundException {
        Mockito.when(storage.getMetaKey("dog", REFS_HEADS_MASTER)).thenThrow(new WrappingAPIException(new RuntimeException("Test")));
        Response response = RESOURCES.target("/metakey/dog")
                .request()
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED)
                .get();
        assertEquals(HttpStatus.SC_INTERNAL_SERVER_ERROR, response.getStatus());
    }

    @Test
    public void testGetMetaKeyUnknown() throws RefNotFoundException {
        Mockito.when(storage.getMetaKey("dog", REFS_HEADS_MASTER)).thenThrow(new RuntimeException("Test"));
        Response response = RESOURCES.target("/metakey/dog")
                .request()
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED)
                .get();
        assertEquals(HttpStatus.SC_INTERNAL_SERVER_ERROR, response.getStatus());
    }

    private static String createCreds(String user, String secret) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + secret).getBytes(UTF_8));
    }
}
