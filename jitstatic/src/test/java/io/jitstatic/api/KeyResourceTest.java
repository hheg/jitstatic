package io.jitstatic.api;

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

import static io.jitstatic.JitStaticConstants.JITSTATIC_KEYADMIN_REALM;
import static io.jitstatic.source.ObjectStreamProvider.toProvider;
import static io.jitstatic.source.ObjectStreamProvider.toByte;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.EntityTag;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.glassfish.jersey.server.filter.RolesAllowedDynamicFeature;
import org.glassfish.jersey.test.grizzly.GrizzlyWebTestContainerFactory;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.spencerwi.either.Either;

import io.dropwizard.auth.AuthDynamicFeature;
import io.dropwizard.auth.AuthValueFactoryProvider;
import io.dropwizard.auth.basic.BasicCredentialAuthFilter;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.dropwizard.testing.junit5.ResourceExtension;
import io.jitstatic.MetaData;
import io.jitstatic.Role;
import io.jitstatic.auth.ConfiguratedAuthenticator;
import io.jitstatic.auth.KeyAdminAuthenticatorImpl;
import io.jitstatic.auth.User;
import io.jitstatic.auth.UserData;
import io.jitstatic.hosted.FailedToLock;
import io.jitstatic.hosted.StoreInfo;
import io.jitstatic.storage.HashService;
import io.jitstatic.storage.KeyAlreadyExist;
import io.jitstatic.storage.Storage;
import io.jitstatic.utils.Pair;
import io.jitstatic.utils.VersionIsNotSame;
import io.jitstatic.utils.WrappingAPIException;

@ExtendWith(DropwizardExtensionsSupport.class)
public class KeyResourceTest {
    private static final String REFS_HEADS_MASTER = "refs/heads/master";
    private static final String APPLICATION_JSON = "application/json";
    private static final Charset UTF_8 = StandardCharsets.UTF_8;
    private static final String USER = "user";
    private static final String SECRET = "secret";
    private static final String PUSER = "puser";
    private static final String PSECRET = "psecret";

    private static final String BASIC_AUTH_CRED = createCreds(USER, SECRET);
    private static final String BASIC_AUTH_CRED_POST = createCreds(PUSER, PSECRET);

    private static final Map<String, Optional<StoreInfo>> DATA = new HashMap<>();
    private static String returnedDog;
    private static String returnedHorse;
    private HashService hashService = new HashService();
    private Storage storage = mock(Storage.class);

    public ResourceExtension RESOURCES = ResourceExtension.builder().setTestContainerFactory(new GrizzlyWebTestContainerFactory())
            .addProvider(new AuthDynamicFeature(new BasicCredentialAuthFilter.Builder<User>().setAuthenticator(new ConfiguratedAuthenticator())
                    .setRealm(JITSTATIC_KEYADMIN_REALM).setAuthorizer((User u,
                            String r) -> true)
                    .buildAuthFilter()))
            .addProvider(RolesAllowedDynamicFeature.class).addProvider(new AuthValueFactoryProvider.Binder<>(User.class))
            .addResource(new KeyResource(storage, new KeyAdminAuthenticatorImpl(storage, (user,
                    ref) -> new User(PUSER, PSECRET)
                            .equals(user), REFS_HEADS_MASTER, hashService), false, REFS_HEADS_MASTER, new HashService()))
            .build();

    @BeforeAll
    public static void setupClass() throws JsonProcessingException, IOException {
        byte[] dog = "{\"food\":[\"bone\",\"meat\"]}".getBytes(UTF_8);
        Set<User> users = new HashSet<>(Arrays.asList(new User(USER, SECRET)));
        byte[] b = new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 };
        StoreInfo bookData = new StoreInfo(toProvider(b), new MetaData(users, "application/octet-stream", false, false, List.of(), null, null), "1", "1");
        DATA.put("book", Optional.of(bookData));
        StoreInfo dogData = new StoreInfo(toProvider(dog), new MetaData(users, null, false, false, List.of(), null, null), "1", "1");
        returnedDog = new String(toByte(dogData.getStreamProvider()));
        DATA.put("dog", Optional.of(dogData));
        byte[] horse = "{\"food\":[\"wheat\",\"grass\"]}".getBytes(UTF_8);
        StoreInfo horseData = new StoreInfo(toProvider(horse), new MetaData(new HashSet<>(), null, false, false, List.of(), null, null), "1", "1");
        returnedHorse = new String(toByte(horseData.getStreamProvider()));
        DATA.put("horse", Optional.of(horseData));
        byte[] cat = "{\"food\":[\"fish\",\"bird\"]}".getBytes(UTF_8);
        users = new HashSet<>(Arrays.asList(new User("auser", "apass")));
        StoreInfo catData = new StoreInfo(toProvider(cat), new MetaData(users, null, false, false, List.of(), null, null), "1", "1");
        DATA.put("cat", Optional.of(catData));
    }

    @AfterEach
    public void tearDown() {
        Mockito.reset(storage);
    }

    @Test
    public void testGettingKeyFromResource() throws InterruptedException {
        Optional<StoreInfo> expected = DATA.get("dog");
        when(storage.getKey("dog", REFS_HEADS_MASTER)).thenReturn(CompletableFuture.completedFuture(expected));
        JsonNode response = RESOURCES.target("/storage/dog").request()
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED).get(JsonNode.class);
        assertEquals(returnedDog, response.toString());
    }

    @Test
    public void testGettingKeyFromResourceWithNoAuthentication() {
        Optional<StoreInfo> expected = DATA.get("dog");
        when(storage.getKey("dog", REFS_HEADS_MASTER)).thenReturn(CompletableFuture.completedFuture(expected));
        assertThat(assertThrows(WebApplicationException.class, () -> RESOURCES.target("/storage/dog").request().get(JsonNode.class))
                .getLocalizedMessage(), Matchers.containsString(Status.UNAUTHORIZED.toString()));
    }

    @Test
    public void testKeyNotFound() {
        when(storage.getKey(eq("cat"), any())).thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        assertEquals(compileMsg(Status.NOT_FOUND), assertThrows(WebApplicationException.class, () -> RESOURCES.target("/storage/cat").request()
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED).get(MetaData.class))
                        .getLocalizedMessage());
    }

    @Test
    public void testNoAuthorizationOnPermittedResource() throws InterruptedException {
        Optional<StoreInfo> expected = DATA.get("horse");
        when(storage.getKey("horse", REFS_HEADS_MASTER)).thenReturn(CompletableFuture.completedFuture(expected));
        JsonNode response = RESOURCES.target("/storage/horse").request().get(JsonNode.class);
        assertEquals(returnedHorse, response.toString());
    }

    @Test
    public void testKeyIsFoundButWrongUser() {
        Optional<StoreInfo> expected = DATA.get("dog");
        when(storage.getKey("dog", REFS_HEADS_MASTER)).thenReturn(CompletableFuture.completedFuture(expected));
        final String bac = "Basic " + Base64.getEncoder().encodeToString(("anotheruser:" + SECRET).getBytes(UTF_8));
        assertEquals("HTTP 403 Forbidden", assertThrows(WebApplicationException.class, () -> {
            RESOURCES.target("/storage/dog").request()
                    .header(HttpHeaders.AUTHORIZATION, bac).get(JsonNode.class);
        }).getLocalizedMessage());
    }

    @Test
    public void testKeyIsFoundWithBranch() throws InterruptedException {
        Optional<StoreInfo> expected = DATA.get("horse");
        when(storage.getKey(Mockito.matches("horse"), Mockito.matches("refs/heads/branch"))).thenReturn(CompletableFuture.completedFuture(expected));
        JsonNode response = RESOURCES.target("/storage/horse")
                .queryParam("ref", "refs/heads/branch").request().get(JsonNode.class);
        assertEquals(returnedHorse, response.toString());
    }

    @Test
    public void testKeyIsNotFoundWithMalformedBranch() throws InterruptedException {
        assertEquals(compileMsg(Status.NOT_FOUND), assertThrows(WebApplicationException.class, () -> RESOURCES.target("/storage/horse")
                .queryParam("ref", "refs/beads/branch").request().get(JsonNode.class)).getLocalizedMessage());
    }

    @Test
    public void testKeyIsNotFoundWithMalformedTag() throws InterruptedException {
        assertEquals(compileMsg(Status.NOT_FOUND), assertThrows(WebApplicationException.class, () -> RESOURCES.target("/storage/horse")
                .queryParam("ref", "refs/bads/branch").request().get(JsonNode.class)).getLocalizedMessage());
    }

    @Test
    public void testKeyIsFoundWithTags() throws InterruptedException {
        Optional<StoreInfo> expected = DATA.get("horse");
        when(storage.getKey(Mockito.matches("horse"), Mockito.matches("refs/tags/branch"))).thenReturn(CompletableFuture.completedFuture(expected));
        JsonNode response = RESOURCES.target("/storage/horse")
                .queryParam("ref", "refs/tags/branch").request().get(JsonNode.class);
        assertEquals(returnedHorse, response.toString());
    }

    @Test
    public void testDoubleKeyIsFoundWithTags() throws InterruptedException {
        Optional<StoreInfo> expected = DATA.get("horse");
        when(storage.getKey(Mockito.matches("horse/horse"), Mockito.matches("refs/tags/branch"))).thenReturn(CompletableFuture.completedFuture(expected));
        JsonNode response = RESOURCES.target("/storage/horse/horse")
                .queryParam("ref", "refs/tags/branch").request().get(JsonNode.class);
        assertEquals(returnedHorse, response.toString());
    }

    @Test
    public void testGetAKeyWithETag() {
        Optional<StoreInfo> optional = DATA.get("horse");
        when(storage.getKey(Mockito.matches("horse/horse"), Mockito.matches("refs/tags/branch"))).thenReturn(CompletableFuture.completedFuture(optional));
        Response response = RESOURCES.target("/storage/horse/horse")
                .queryParam("ref", "refs/tags/branch").request()
                .header(HttpHeaders.IF_MATCH, "\"" + optional.get().getVersion() + "\"").get();
        assertEquals(HttpStatus.NOT_MODIFIED_304, response.getStatus());
        response.close();
    }

    @Test
    public void testFaultyRef() {
        assertEquals(compileMsg(Status.NOT_FOUND), assertThrows(WebApplicationException.class, () -> RESOURCES.target("/storage/horse")
                .queryParam("ref", "refs/beads/branch").request().get(JsonNode.class)).getLocalizedMessage());
    }

    private String compileMsg(Status notFound) {
        return String.format("HTTP %d %s", notFound.getStatusCode(), notFound.getReasonPhrase());
    }

    @Test
    public void testEmptyRef() {
        assertEquals(compileMsg(Status.NOT_FOUND), assertThrows(WebApplicationException.class, () -> RESOURCES.target("/storage/horse").queryParam("ref", "")
                .request().get(JsonNode.class))
                        .getLocalizedMessage());
    }

    @Test
    public void testPutAKeyWithNoUser() throws IOException {
        WebTarget target = RESOURCES.target("/storage/horse");
        byte[] readTree = "{\"food\" : [\"wheat\",\"carrots\"]}".getBytes(UTF_8);
        ModifyKeyData data = new ModifyKeyData(toProvider(readTree), "message", "user", "mail");
        Response response = target.request()
                .header(HttpHeaders.IF_MATCH, "\"1\"")
                .buildPut(Entity.entity(data, MediaType.APPLICATION_JSON)).invoke();
        assertEquals(Status.UNAUTHORIZED.getStatusCode(), response.getStatus());
        assertEquals("Basic realm=\"" + JITSTATIC_KEYADMIN_REALM + "\", charset=\"UTF-8\"", response.getHeaderString(HttpHeaders.WWW_AUTHENTICATE));
        response.close();
    }

    @Test
    public void testPutADeletedKey() throws IOException {
        WebTarget target = RESOURCES.target("/storage/dog");
        when(storage.getKey(eq("dog"), eq(REFS_HEADS_MASTER))).thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        byte[] readTree = "{\"food\" : [\"treats\",\"meat\"]}".getBytes(UTF_8);
        ModifyKeyData data = new ModifyKeyData(toProvider(readTree), "message", "user", "mail");
        Response response = target.request()
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED)
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON)
                .header(HttpHeaders.IF_MATCH, "1")
                .buildPut(Entity.entity(data, MediaType.APPLICATION_JSON)).invoke();

        assertEquals(Status.NOT_FOUND.getStatusCode(), response.getStatus());
        response.close();
    }

    @Test
    public void testPutAKey() throws IOException, RefNotFoundException {
        WebTarget target = RESOURCES.target("/storage/dog");
        Optional<StoreInfo> storeInfo = DATA.get("dog");
        Either<String, FailedToLock> expected = Either.left("2");
        when(storage.getKey(eq("dog"), eq(REFS_HEADS_MASTER))).thenReturn(CompletableFuture.completedFuture(storeInfo));
        when(storage.putKey(eq("dog"), eq(REFS_HEADS_MASTER), any(), eq("1"), any()))
                .thenReturn(CompletableFuture.completedFuture(expected));
        byte[] readTree = "{\"food\" : [\"treats\",\"steak\"]}".getBytes(UTF_8);
        ModifyKeyData data = new ModifyKeyData(toProvider(readTree), "message", "user", "mail");
        Response response = target.request()
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED)
                .header(HttpHeaders.IF_MATCH, "\"1\"")
                .buildPut(Entity.entity(data, MediaType.APPLICATION_JSON)).invoke();
        assertEquals(Status.OK.getStatusCode(), response.getStatus());
        EntityTag entityTag = response.getEntityTag();
        assertEquals(expected.getLeft(), entityTag.getValue());
        response.close();
    }

    @Test
    public void testPutAKeyOtherVersion() throws RefNotFoundException, IOException {
        WebTarget target = RESOURCES.target("/storage/dog");
        Optional<StoreInfo> storeInfo = DATA.get("dog");
        Either<String, FailedToLock> expected = Either.left("2");
        when(storage.getKey(eq("dog"), eq(REFS_HEADS_MASTER))).thenReturn(CompletableFuture.completedFuture(storeInfo));
        when(storage.putKey(eq("dog"), eq(REFS_HEADS_MASTER), any(), eq("1"), any()))
                .thenReturn(CompletableFuture.completedFuture(expected));
        byte[] readTree = "{\"food\" : [\"treats\",\"steak\"]}".getBytes(UTF_8);
        ModifyKeyData data = new ModifyKeyData(toProvider(readTree), "message", "user", "mail");
        Response response = target.request()
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED)
                .header(HttpHeaders.IF_MATCH, "\"2\"")
                .buildPut(Entity.entity(data, MediaType.APPLICATION_JSON)).invoke();

        assertEquals(Status.PRECONDITION_FAILED.getStatusCode(), response.getStatus());
        response.close();
    }

    @Test
    public void testPutAKeyOtherVersionMissingHeader() throws RefNotFoundException, IOException {
        WebTarget target = RESOURCES.target("/storage/dog");
        Optional<StoreInfo> storeInfo = DATA.get("dog");
        Either<String, FailedToLock> expected = Either.left("2");
        when(storage.getKey(eq("dog"), eq(null))).thenReturn(CompletableFuture.completedFuture(storeInfo));
        when(storage.putKey(eq("dog"), eq(null), any(), eq("1"), any()))
                .thenReturn(CompletableFuture.completedFuture(expected));
        byte[] readTree = "{\"food\" : [\"treats\",\"steak\"]}".getBytes(UTF_8);
        ModifyKeyData data = new ModifyKeyData(toProvider(readTree), "message", "user", "mail");
        Response response = target.request()
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED)
                .buildPut(Entity.entity(data, MediaType.APPLICATION_JSON)).invoke();
        assertEquals(Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        response.close();
    }

    @Test
    public void testPutAMissingKey() throws IOException {
        WebTarget target = RESOURCES.target("/storage/horse");
        when(storage.getKey(eq("horse"), eq(REFS_HEADS_MASTER))).thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        byte[] readTree = "{\"food\" : [\"wheat\",\"carrots\"]}".getBytes(UTF_8);
        ModifyKeyData data = new ModifyKeyData(toProvider(readTree), "message", "user", "mail");
        Response response = target.request()
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED)
                .header(HttpHeaders.IF_MATCH, "\"1\"")
                .buildPut(Entity.entity(data, MediaType.APPLICATION_JSON)).invoke();
        assertEquals(Status.NOT_FOUND.getStatusCode(), response.getStatus());
        response.close();
    }

    @Test
    public void testPutAKeyWithNoUsers() throws IOException {
        WebTarget target = RESOURCES.target("/storage/horse");
        Optional<StoreInfo> storeInfo = DATA.get("horse");
        when(storage.getKey(eq("horse"), eq(REFS_HEADS_MASTER))).thenReturn(CompletableFuture.completedFuture(storeInfo));
        byte[] readTree = "{\"food\" : [\"wheat\",\"carrots\"]}".getBytes(UTF_8);
        ModifyKeyData data = new ModifyKeyData(toProvider(readTree), "message", "user", "mail");
        Response response = target.request()
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED)
                .header(HttpHeaders.IF_MATCH, "\"1\"")
                .buildPut(Entity.entity(data, MediaType.APPLICATION_JSON)).invoke();
        assertEquals(Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        response.close();
    }

    @Test
    public void testPutAKeyWithWrongUser() throws IOException {
        WebTarget target = RESOURCES.target("/storage/cat");
        Optional<StoreInfo> storeInfo = DATA.get("cat");
        when(storage.getKey(eq("cat"), eq(REFS_HEADS_MASTER))).thenReturn(CompletableFuture.completedFuture(storeInfo));
        byte[] readTree = "{\"food\" : [\"wheat\",\"carrots\"]}".getBytes(UTF_8);
        ModifyKeyData data = new ModifyKeyData(toProvider(readTree), "message", "user", "mail");
        Response response = target.request()
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED)
                .header(HttpHeaders.IF_MATCH, "\"1\"")
                .buildPut(Entity.entity(data, MediaType.APPLICATION_JSON)).invoke();
        assertEquals(Status.FORBIDDEN.getStatusCode(), response.getStatus());
        response.close();
    }

    @Test
    public void testPutKeyIsFoundButNotFoundWhenModifying() throws RefNotFoundException, IOException {
        WebTarget target = RESOURCES.target("/storage/dog");
        Optional<StoreInfo> storeInfo = DATA.get("dog");
        when(storage.getKey(eq("dog"), any())).thenReturn(CompletableFuture.completedFuture(storeInfo));
        when(storage.putKey(eq("dog"), any(), any(), eq("1"), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        byte[] readTree = "{\"food\" : [\"treats\",\"steak\"]}".getBytes(UTF_8);
        ModifyKeyData data = new ModifyKeyData(toProvider(readTree), "message", "user", "mail");
        Response response = target.request()
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED)
                .header(HttpHeaders.IF_MATCH, "\"1\"")
                .buildPut(Entity.entity(data, MediaType.APPLICATION_JSON)).invoke();
        assertEquals(Status.NOT_FOUND.getStatusCode(), response.getStatus());
        response.close();
    }

    @Test
    public void testPutKeyButRefIsDeletedWhilst() throws IOException {
        WebTarget target = RESOURCES.target("/storage/dog");
        Optional<StoreInfo> storeInfo = DATA.get("dog");
        when(storage.getKey(eq("dog"), eq(REFS_HEADS_MASTER))).thenReturn(CompletableFuture.completedFuture(storeInfo));
        when(storage.putKey(eq("dog"), eq(REFS_HEADS_MASTER), any(), eq("1"), any()))
                .thenThrow(new WrappingAPIException(new RefNotFoundException("Test ref not found")));
        byte[] readTree = "{\"food\" : [\"treats\",\"steak\"]}".getBytes(UTF_8);
        ModifyKeyData data = new ModifyKeyData(toProvider(readTree), "message", "user", "mail");
        Response response = target.request()
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED)
                .header(HttpHeaders.IF_MATCH, "\"1\"")
                .buildPut(Entity.entity(data, MediaType.APPLICATION_JSON)).invoke();
        assertEquals(Status.NOT_FOUND.getStatusCode(), response.getStatus());
        response.close();
    }

    @Test
    public void testPutKeyButKeyIsDeletedWhilst() throws IOException {
        WebTarget target = RESOURCES.target("/storage/dog");
        Optional<StoreInfo> storeInfo = DATA.get("dog");
        when(storage.getKey(eq("dog"), eq(REFS_HEADS_MASTER))).thenReturn(CompletableFuture.completedFuture(storeInfo));
        when(storage.putKey(eq("dog"), eq(REFS_HEADS_MASTER), any(), eq("1"), any()))
                .thenThrow(new WrappingAPIException(new UnsupportedOperationException("Test operation")));
        byte[] readTree = "{\"food\" : [\"treats\",\"steak\"]}".getBytes(UTF_8);
        ModifyKeyData data = new ModifyKeyData(toProvider(readTree), "message", "user", "mail");
        Response response = target.request()
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED)
                .header(HttpHeaders.IF_MATCH, "\"1\"")
                .buildPut(Entity.entity(data, MediaType.APPLICATION_JSON)).invoke();
        assertEquals(Status.NOT_FOUND.getStatusCode(), response.getStatus());
        response.close();
    }

    @Test
    public void testPutKeyButVersionIsChangedWhilst() throws IOException {
        WebTarget target = RESOURCES.target("/storage/dog");
        Optional<StoreInfo> storeInfo = DATA.get("dog");
        when(storage.getKey(eq("dog"), eq(REFS_HEADS_MASTER))).thenReturn(CompletableFuture.completedFuture(storeInfo));
        when(storage.putKey(eq("dog"), eq(REFS_HEADS_MASTER), any(), eq("1"), any()))
                .thenThrow((new WrappingAPIException(new VersionIsNotSame("", ""))));
        byte[] readTree = "{\"food\" : [\"treats\",\"steak\"]}".getBytes(UTF_8);
        ModifyKeyData data = new ModifyKeyData(toProvider(readTree), "message", "user", "mail");
        Response response = target.request()
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON)
                .header(HttpHeaders.ACCEPT, "application/json")
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED)
                .header(HttpHeaders.IF_MATCH, "\"1\"")
                .buildPut(Entity.entity(data, MediaType.APPLICATION_JSON)).invoke();
        assertEquals(Status.PRECONDITION_FAILED.getStatusCode(), response.getStatus());
        response.close();
    }

    @Test
    public void testPutKeyGeneralError() throws IOException {
        WebTarget target = RESOURCES.target("/storage/dog");
        Optional<StoreInfo> storeInfo = DATA.get("dog");
        when(storage.getKey(eq("dog"), eq(REFS_HEADS_MASTER))).thenReturn(CompletableFuture.completedFuture(storeInfo));
        when(storage.putKey(eq("dog"), eq(REFS_HEADS_MASTER), any(), eq("1"), any()))
                .thenThrow(new WrappingAPIException(new Exception("Test exception")));
        byte[] readTree = "{\"food\" : [\"treats\",\"steak\"]}".getBytes(UTF_8);
        ModifyKeyData data = new ModifyKeyData(toProvider(readTree), "message", "user", "mail");
        Response response = target.request()
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED)
                .header(HttpHeaders.IF_MATCH, "\"1\"")
                .buildPut(Entity.entity(data, MediaType.APPLICATION_JSON)).invoke();
        assertEquals(Status.INTERNAL_SERVER_ERROR.getStatusCode(), response.getStatus());
    }

    @Test
    public void testPutKeyOnTag() throws IOException {
        WebTarget target = RESOURCES.target("/storage/dog").queryParam("ref", "refs/tags/tag");
        byte[] readTree = "{\"food\" : [\"treats\",\"steak\"]}".getBytes(UTF_8);
        ModifyKeyData data = new ModifyKeyData(toProvider(readTree), "message", "user", "mail");
        Response response = target.request()
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED)
                .header(HttpHeaders.IF_MATCH, "\"1\"")
                .buildPut(Entity.entity(data, MediaType.APPLICATION_JSON)).invoke();
        assertEquals(Status.FORBIDDEN.getStatusCode(), response.getStatus());
        response.close();
    }

    @Test
    public void testNotModified() {
        Optional<StoreInfo> expected = DATA.get("dog");
        when(storage.getKey("dog", REFS_HEADS_MASTER)).thenReturn(CompletableFuture.completedFuture(expected));
        Response response = RESOURCES.target("/storage/dog").request().header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED).get();
        assertEquals(returnedDog, response.readEntity(JsonNode.class).toString());
        assertEquals(DATA.get("dog").get().getVersion(), response.getEntityTag().getValue());
        response = RESOURCES.target("/storage/dog").request()
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED)
                .header(HttpHeaders.IF_MATCH, "\"" + DATA.get("dog").get().getVersion() + "\"").get();
        assertEquals(Status.NOT_MODIFIED.getStatusCode(), response.getStatus());
    }

    @Test
    public void testGetapplicatiOnoctetstream() {
        Optional<StoreInfo> expected = DATA.get("book");
        when(storage.getKey("book", REFS_HEADS_MASTER)).thenReturn(CompletableFuture.completedFuture(expected));
        Response response = RESOURCES
                .target("/storage/book")
                .request()
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED).get();
        assertEquals(Status.OK.getStatusCode(), response.getStatus());
        assertArrayEquals(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 }, response.readEntity(byte[].class));
        response.close();
    }

    @Test
    public void testModifyApplicatiOnoctetStream() {
        Optional<StoreInfo> expected = DATA.get("book");
        when(storage.getKey(eq("book"), eq(REFS_HEADS_MASTER))).thenReturn(CompletableFuture.completedFuture(expected));
        when(storage.putKey(eq("book"), eq(REFS_HEADS_MASTER), any(), eq("1"), any()))
                .thenReturn(CompletableFuture.completedFuture(Either.left("2")));
        Response response = RESOURCES.target("/storage/book").request().header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED).get();
        assertEquals(Status.OK.getStatusCode(), response.getStatus());
        assertArrayEquals(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 }, response.readEntity(byte[].class));
        EntityTag entityTag = response.getEntityTag();
        response.close();
        byte[] byteData = new byte[] { 8, 7, 6, 5, 4, 3, 2, 1 };
        ModifyKeyData data = new ModifyKeyData(toProvider(byteData), "message", "user", "mail");
        response = RESOURCES.target("/storage/book")
                .request()
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON)
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED)
                .header(HttpHeaders.IF_MATCH, "\"" + entityTag.getValue() + "\"")
                .buildPut(Entity.entity(data, MediaType.APPLICATION_JSON)).invoke();
        assertEquals(Status.OK.getStatusCode(), response.getStatus());
        response.close();
    }

    @Test
    public void testAddKey() throws IOException {
        StoreInfo si = new StoreInfo(toProvider(new byte[] { 1 }), new MetaData(new HashSet<>(), APPLICATION_JSON, false, false, List
                .of(), null, null), "1", "1");
        when(storage.getKey(eq("test"), eq(REFS_HEADS_MASTER))).thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        when(storage.addKey(eq("test"), any(), any(), any(), any())).thenReturn(CompletableFuture.completedFuture("1"));
        AddKeyData addKeyData = new AddKeyData(toProvider(new byte[] { 1 }), new MetaData(new HashSet<>(), APPLICATION_JSON, false, false, List
                .of(), null, null), "testmessage", "user", "test@test.com");
        Response response = RESOURCES.target("/storage/test")
                .request()
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED_POST)
                .post(Entity.json(addKeyData));
        assertEquals(Status.OK.getStatusCode(), response.getStatus());
        assertEquals("1", response.getEntityTag().getValue());
        assertArrayEquals(new byte[] { 1 }, toByte(si.getStreamProvider()));
        response.close();
    }

    @Test
    public void testAddRootKey() {
        Mockito.when(storage.getKey(anyString(), anyString())).thenThrow(new WrappingAPIException(new UnsupportedOperationException("test/")));
        AddKeyData addKeyData = new AddKeyData(toProvider(new byte[] { 1 }), new MetaData(new HashSet<>(), APPLICATION_JSON, false, false, List
                .of(), null, null), "testmessage", "user", "test@test.com");
        Response response = RESOURCES.target("/storage/test/")
                .request()
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED_POST)
                .post(Entity.json(addKeyData));
        assertEquals(Status.METHOD_NOT_ALLOWED.getStatusCode(), response.getStatus());
        response.close();
    }

    @Test
    public void testAddKeyNoUser() {
        Response response = RESOURCES.target("/storage/test").request()
                .post(Entity.json(new AddKeyData(toProvider(new byte[] { 1 }), new MetaData(new HashSet<>(), APPLICATION_JSON, false, false, List
                        .of(), null, null), "testmessage", "user", "test@test.com")));
        assertEquals(Status.UNAUTHORIZED.getStatusCode(), response.getStatus());
        assertEquals("Basic realm=\"" + JITSTATIC_KEYADMIN_REALM + "\", charset=\"UTF-8\"", response.getHeaderString(HttpHeaders.WWW_AUTHENTICATE));
        response.close();
    }

    @Test
    public void testAddKeyWrongUser() {
        byte[] data = "{\"food\" : [\"treats\",\"steak\"]}".getBytes(UTF_8);
        when(storage.addKey(any(), any(), any(), any(), any())).thenReturn(CompletableFuture.completedFuture("1"));
        Response response = RESOURCES.target("/storage/test")
                .request()
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED)
                .post(Entity.json(new AddKeyData(toProvider(data), new MetaData(new HashSet<>(), APPLICATION_JSON, false, false, List
                        .of(), null, null), "test", "user", "test@test.com")));
        assertEquals(Status.FORBIDDEN.getStatusCode(), response.getStatus());
        response.close();
    }

    @Test
    public void testAddKeyNoBranch() throws JsonProcessingException {
        Response response = RESOURCES.target("/storage/test")
                .queryParam("ref", "refs/tags/master")
                .request()
                .accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED_POST)
                .post(Entity.json(new AddKeyData(toProvider(new byte[] { 1 }), new MetaData(new HashSet<>(), APPLICATION_JSON, false, false, List
                        .of(), null, null), "test", "user", "test@test.com")));
        assertEquals(403, response.getStatus());
        response.close();
    }

    @Test
    public void testAddKeyKeyAlreadyExist() {
        byte[] data = "{\"food\" : [\"treats\",\"steak\"]}".getBytes(UTF_8);
        when(storage.getKey(eq("test"), eq(REFS_HEADS_MASTER))).thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        when(storage.addKey(any(), any(), any(), any(), any()))
                .thenThrow(new WrappingAPIException(new KeyAlreadyExist("test", REFS_HEADS_MASTER)));
        Response response = RESOURCES.target("/storage/test")
                .request()
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED_POST)
                .post(Entity.json(new AddKeyData(toProvider(data), new MetaData(new HashSet<>(), APPLICATION_JSON, false, false, List
                        .of(), null, null), "test", "user", "test@test.com")));
        assertEquals(Status.CONFLICT.getStatusCode(), response.getStatus());
        response.close();
    }

    @Test
    public void testAddKeyBranchNotFound() {
        byte[] data = "{\"food\" : [\"treats\",\"steak\"]}".getBytes(UTF_8);
        when(storage.getKey(eq("test"), eq(REFS_HEADS_MASTER))).thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        when(storage.addKey(any(), any(), any(), any(), any()))
                .thenThrow(new WrappingAPIException(new RefNotFoundException(REFS_HEADS_MASTER)));
        Response response = RESOURCES.target("/storage/test")
                .request()
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED_POST)
                .post(Entity.json(new AddKeyData(toProvider(data), new MetaData(new HashSet<>(), APPLICATION_JSON, false, false, List
                        .of(), null, null), "test", "user", "test@test.com")));
        assertEquals(Status.NOT_FOUND.getStatusCode(), response.getStatus());
        response.close();
    }

    @Test
    public void testAddKeyDataIsMalformed() {
        byte[] data = new byte[] { 1 };
        when(storage.getKey(eq("test"), eq(REFS_HEADS_MASTER))).thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        when(storage.addKey(any(), any(), any(), any(), any()))
                .thenThrow(new WrappingAPIException(new IOException("Data is malformed")));
        Response response = RESOURCES.target("/storage/test")
                .request()
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED_POST)
                .post(Entity.json(new AddKeyData(toProvider(data), new MetaData(new HashSet<>(), APPLICATION_JSON, false, false, List
                        .of(), null, null), "test", "user", "test@test.com")));
        assertEquals(422, response.getStatus());
        response.close();
    }

    @Test
    public void testAddKeyDataWithNodata() {
        when(storage.getKey(anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        when(storage.addKey(anyString(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture("1"));
        Response response = RESOURCES.target("/storage/test")
                .request()
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED_POST)
                .accept(MediaType.APPLICATION_JSON)
                .post(Entity.json(new AddKeyData(toProvider(new byte[] {}), new MetaData(new HashSet<>(), APPLICATION_JSON, false, false, List
                        .of(), null, null), "test", "user", "test@test.com")));
        assertEquals(200, response.getStatus());
    }

    @Test
    public void testModifyKetWithoutIFMatchtag() {
        Optional<StoreInfo> storeInfo = DATA.get("dog");
        Either<String, FailedToLock> expected = Either.left("2");
        when(storage.getKey(eq("dog"), Mockito.isNull())).thenReturn(CompletableFuture.completedFuture(storeInfo));
        when(storage.putKey(eq("dog"), Mockito.isNull(), any(), eq("1"), any()))
                .thenReturn(CompletableFuture.completedFuture(expected));
        byte[] readTree = "{\"food\" : [\"treats\",\"steak\"]}".getBytes(UTF_8);
        ModifyKeyData data = new ModifyKeyData(toProvider(readTree), "message", "user", "mail");
        Response response = RESOURCES.target("/storage/dog").request()
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED)
                .header(HttpHeaders.IF_MATCH, "")
                .buildPut(Entity.entity(data, MediaType.APPLICATION_JSON)).invoke();
        assertEquals(Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        response.close();
    }

    @Test
    public void testGetMasterMetaKeyShouldFail() {
        when(storage.getListForRef(any(), anyString())).thenReturn(CompletableFuture.completedFuture(List.of()));
        Response response = RESOURCES.target("/storage/dog/")
                .request().header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED).get();
        assertEquals(Status.NOT_FOUND.getStatusCode(), response.getStatus());
        response.close();
    }

    @Test
    public void testPutOnMasterMetaKeyShouldFail() {
        WebTarget target = RESOURCES.target("/storage/dog/");
        byte[] readTree = "{\"food\" : [\"treats\",\"steak\"]}".getBytes(UTF_8);
        ModifyKeyData data = new ModifyKeyData(toProvider(readTree), "message", "user", "mail");
        Response response = target.request()
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED)
                .header(HttpHeaders.IF_MATCH, "\"1\"")
                .buildPut(Entity.entity(data, MediaType.APPLICATION_JSON)).invoke();
        assertEquals(Status.METHOD_NOT_ALLOWED.getStatusCode(), response.getStatus());
        response.close();
    }

    @Test
    public void testDeleteKey() {
        Optional<StoreInfo> storeInfo = DATA.get("dog");
        when(storage.getKey(eq("dog"), eq(REFS_HEADS_MASTER))).thenReturn(CompletableFuture.completedFuture(storeInfo));
        when(storage.delete(eq("dog"), eq(REFS_HEADS_MASTER), any())).thenReturn(CompletableFuture.completedFuture(Either.left("1")));
        Response delete = RESOURCES.target("/storage/dog")
                .request()
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON)
                .header("X-jitstatic-name", "user")
                .header("X-jitstatic-mail", "mail")
                .header("X-jitstatic-message", "msg")
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED)
                .delete();
        assertEquals(Status.OK.getStatusCode(), delete.getStatus());
        delete.close();
    }

    @Test
    public void testDeleteKeyNoUserSet() {
        Optional<StoreInfo> storeInfo = DATA.get("dog");
        when(storage.getKey(eq("dog"), eq(REFS_HEADS_MASTER))).thenReturn(CompletableFuture.completedFuture(storeInfo));
        Response delete = RESOURCES.target("/storage/dog")
                .request()
                .header("X-jitstatic-name", "user")
                .header("X-jitstatic-mail", "mail")
                .header("X-jitstatic-message", "msg")
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON)
                .delete();
        assertEquals(Status.UNAUTHORIZED.getStatusCode(), delete.getStatus());
        delete.close();
    }

    @Test
    public void testDeleteKeyNoUserKey() {
        Optional<StoreInfo> storeInfo = DATA.get("horse");
        when(storage.getKey(eq("horse"), eq(REFS_HEADS_MASTER))).thenReturn(CompletableFuture.completedFuture(storeInfo));
        Response delete = RESOURCES.target("/storage/horse").request()
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON)
                .header("X-jitstatic-name", "user")
                .header("X-jitstatic-mail", "mail")
                .header("X-jitstatic-message", "msg")
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED)
                .delete();
        assertEquals(Status.BAD_REQUEST.getStatusCode(), delete.getStatus());
        delete.close();
    }

    @Test
    public void testDeleteNoHeaderInfoSet() {
        Optional<StoreInfo> storeInfo = DATA.get("dog");
        when(storage.getKey(eq("dog"), eq(REFS_HEADS_MASTER))).thenReturn(CompletableFuture.completedFuture(storeInfo));
        Response delete = RESOURCES.target("/storage/dog").request()
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON)
                .header("X-jitstatic-mail", "mail")
                .header("X-jitstatic-message", "msg")
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED)
                .delete();
        assertEquals(Status.BAD_REQUEST.getStatusCode(), delete.getStatus());
        delete.close();
    }

    @Test
    public void testListAll() {
        StoreInfo dogInfo = DATA.get("dog").get();
        StoreInfo bookInfo = DATA.get("book").get();
        Pair<String, StoreInfo> dogPair = Pair.of("dog", dogInfo);
        Pair<String, StoreInfo> bookPair = Pair.of("book", bookInfo);
        when(storage.getListForRef(any(), any())).thenReturn(CompletableFuture.completedFuture(List.of(dogPair, bookPair)));
        KeyDataWrapper list = RESOURCES.target("/storage/").request()
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED)
                .get(KeyDataWrapper.class);
        assertNotNull(list);
        assertEquals(2, list.getResult().size());
        assertEquals(new KeyData(dogPair), list.getResult().get(0));
        assertEquals(new KeyData(bookPair), list.getResult().get(1));
    }

    @Test
    public void testEmptyList() {
        when(storage.getListForRef(any(), any())).thenReturn(CompletableFuture.completedFuture(List.of()));
        assertEquals(Status.NOT_FOUND.getStatusCode(), RESOURCES.target("/storage/").request()
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED)
                .get().getStatus());

    }

    @Test
    public void testisKeyUserAllowed() throws Exception {
        when(storage.getUser(anyString(), anyString(), anyString())).thenReturn(new UserData(Set.of(new Role("role")), "p", null, null));
        assertTrue(APIHelper.isKeyUserAllowed(storage, hashService, new User("u", "p"), REFS_HEADS_MASTER, Set.of(new Role("role"))));
    }

    @Test
    public void testisKeyUserAllowedWithNoRole() throws Exception {
        when(storage.getUser(anyString(), anyString(), anyString())).thenReturn(new UserData(Set.of(), "p", null, null));
        assertFalse(APIHelper.isKeyUserAllowed(storage, hashService, new User("u", "p"), REFS_HEADS_MASTER, Set.of(new Role("role"))));
    }

    @Test
    public void testisKeyUserAllowedWithWrongRole() throws Exception {
        when(storage.getUser(anyString(), anyString(), anyString()))
                .thenReturn(new UserData(Set.of(new Role("other")), "p", null, null));
        assertFalse(APIHelper.isKeyUserAllowed(storage, hashService, new User("u", "p"), REFS_HEADS_MASTER, Set.of(new Role("role"))));
    }

    @Test
    public void testisKeyUserAllowedWithWrongPassword() throws Exception {
        when(storage.getUser(anyString(), anyString(), anyString()))
                .thenReturn(new UserData(Set.of(new Role("role")), "z", null, null));
        assertFalse(APIHelper.isKeyUserAllowed(storage, hashService, new User("u", "p"), REFS_HEADS_MASTER, Set.of(new Role("role"))));
    }

    @Test
    public void testisKeyUserAllowedWithWrongPasswordNoBranch() throws Exception {
        when(storage.getUser(anyString(), anyString(), anyString()))
                .thenReturn(new UserData(Set.of(new Role("role")), "z", null, null));
        assertFalse(APIHelper.isKeyUserAllowed(storage, hashService, new User("u", "p"), null, Set.of(new Role("role"))));
    }

    private static String createCreds(String user,
            String secret) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + secret).getBytes(UTF_8));
    }
}
