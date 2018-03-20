package jitstatic.api;

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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.EntityTag;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.glassfish.jersey.server.filter.RolesAllowedDynamicFeature;
import org.glassfish.jersey.test.grizzly.GrizzlyWebTestContainerFactory;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.Mockito;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import io.dropwizard.auth.AuthDynamicFeature;
import io.dropwizard.auth.AuthValueFactoryProvider;
import io.dropwizard.auth.basic.BasicCredentialAuthFilter;
import io.dropwizard.testing.junit.ResourceTestRule;
import jitstatic.StorageData;
import jitstatic.auth.ConfiguratedAuthenticator;
import jitstatic.auth.User;
import jitstatic.storage.KeyAlreadyExist;
import jitstatic.storage.Storage;
import jitstatic.storage.StoreInfo;
import jitstatic.utils.VersionIsNotSameException;
import jitstatic.utils.WrappingAPIException;

public class MapResourceTest {
    private static final String APPLICATION_JSON = "application/json";
    private static final String REFS_HEADS_MASTER = "refs/heads/master";
    private static final String UTF_8 = "UTF-8";
    private static final String USER = "user";
    private static final String SECRET = "secret";
    private static final String PUSER = "puser";
    private static final String PSECRET = "psecret";

    private static final String BASIC_AUTH_CRED;
    private static final String BASIC_AUTH_CRED_POST;

    static {
        try {
            BASIC_AUTH_CRED = "Basic " + Base64.getEncoder().encodeToString((USER + ":" + SECRET).getBytes(UTF_8));
        } catch (UnsupportedEncodingException e) {
            throw new Error(e);
        }
    }
    static {
        try {
            BASIC_AUTH_CRED_POST = "Basic " + Base64.getEncoder().encodeToString((PUSER + ":" + PSECRET).getBytes(UTF_8));
        } catch (UnsupportedEncodingException e) {
            throw new Error(e);
        }
    }
    private Storage STORAGE = mock(Storage.class);
    private static final Map<String, Optional<StoreInfo>> DATA = new HashMap<>();
    private static String returnedDog;
    private static String returnedHorse;

    @Rule
    public ExpectedException ex = ExpectedException.none();

    @Rule
    public ResourceTestRule RESOURCES = ResourceTestRule.builder().setTestContainerFactory(new GrizzlyWebTestContainerFactory())
            .addProvider(
                    new AuthDynamicFeature(new BasicCredentialAuthFilter.Builder<User>().setAuthenticator(new ConfiguratedAuthenticator())
                            .setRealm("jitstatic").setAuthorizer((User u, String r) -> true).buildAuthFilter()))
            .addProvider(RolesAllowedDynamicFeature.class).addProvider(new AuthValueFactoryProvider.Binder<>(User.class))
            .addResource(new MapResource(STORAGE, (user) -> new User(PUSER, PSECRET).equals(user))).build();

    @BeforeClass
    public static void setupClass() throws JsonProcessingException, IOException {
        byte[] dog = "{\"food\":[\"bone\",\"meat\"]}".getBytes(UTF_8);
        Set<User> users = new HashSet<>(Arrays.asList(new User(USER, SECRET)));
        byte[] b = new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 };
        StoreInfo bookData = new StoreInfo(b, new StorageData(users, "application/octet-stream"), "1");
        DATA.put("book", Optional.of(bookData));
        StoreInfo dogData = new StoreInfo(dog, new StorageData(users, null), "1");
        returnedDog = new String(dogData.getData());
        DATA.put("dog", Optional.of(dogData));
        byte[] horse = "{\"food\":[\"wheat\",\"grass\"]}".getBytes(UTF_8);
        StoreInfo horseData = new StoreInfo(horse, new StorageData(new HashSet<>(), null), "1");
        returnedHorse = new String(horseData.getData());
        DATA.put("horse", Optional.of(horseData));
        byte[] cat = "{\"food\":[\"fish\",\"bird\"]}".getBytes(UTF_8);
        users = new HashSet<>(Arrays.asList(new User("auser", "apass")));
        StoreInfo catData = new StoreInfo(cat, new StorageData(users, null), "1");
        DATA.put("cat", Optional.of(catData));
    }

    @Test
    public void testGettingKeyFromResource() throws InterruptedException, ExecutionException {
        CompletableFuture<Optional<StoreInfo>> expected = CompletableFuture.completedFuture(DATA.get("dog"));
        when(STORAGE.get("dog", null)).thenReturn(expected);
        JsonNode response = RESOURCES.target("/storage/dog").request().header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED)
                .get(JsonNode.class);
        assertEquals(returnedDog, response.toString());
    }

    @Test
    public void testGettingKeyFromResourceWithNoAuthentication() {
        ex.expect(WebApplicationException.class);
        ex.expectMessage(Status.UNAUTHORIZED.toString());
        CompletableFuture<Optional<StoreInfo>> expected = CompletableFuture.completedFuture(DATA.get("dog"));
        when(STORAGE.get("dog", null)).thenReturn(expected);
        RESOURCES.target("/storage/dog").request().get(JsonNode.class);
    }

    @Test
    public void testKeyNotFound() {
        ex.expect(WebApplicationException.class);
        ex.expectMessage(Status.NOT_FOUND.toString());
        when(STORAGE.get(any(), Mockito.anyString())).thenReturn(null);
        RESOURCES.target("/storage/cat").request().header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED).get(StorageData.class);
    }

    @Test
    public void testNoAuthorizationOnPermittedResource() throws InterruptedException, ExecutionException {
        CompletableFuture<Optional<StoreInfo>> expected = CompletableFuture.completedFuture(DATA.get("horse"));
        when(STORAGE.get("horse", null)).thenReturn(expected);
        JsonNode response = RESOURCES.target("/storage/horse").request().get(JsonNode.class);
        assertEquals(returnedHorse, response.toString());
    }

    @Test
    public void testKeyIsFoundButWrongUser() throws UnsupportedEncodingException {
        ex.expect(WebApplicationException.class);
        ex.expectMessage(Status.UNAUTHORIZED.toString());
        CompletableFuture<Optional<StoreInfo>> expected = CompletableFuture.completedFuture(DATA.get("dog"));
        when(STORAGE.get("dog", null)).thenReturn(expected);
        final String bac = "Basic " + Base64.getEncoder().encodeToString(("anotheruser:" + SECRET).getBytes(UTF_8));
        RESOURCES.target("/storage/dog").request().header(HttpHeaders.AUTHORIZATION, bac).get(JsonNode.class);
    }

    @Test
    public void testKeyIsFoundWithBranch() throws InterruptedException, ExecutionException {
        CompletableFuture<Optional<StoreInfo>> expected = CompletableFuture.completedFuture(DATA.get("horse"));
        when(STORAGE.get(Mockito.matches("horse"), Mockito.matches("refs/heads/branch"))).thenReturn(expected);
        JsonNode response = RESOURCES.target("/storage/horse").queryParam("ref", "refs/heads/branch").request().get(JsonNode.class);
        assertEquals(returnedHorse, response.toString());
    }

    @Test
    public void testKeyIsNotFoundWithMalformedBranch() throws InterruptedException, ExecutionException {
        ex.expect(WebApplicationException.class);
        ex.expectMessage(Status.NOT_FOUND.toString());
        RESOURCES.target("/storage/horse").queryParam("ref", "refs/beads/branch").request().get(JsonNode.class);
    }

    @Test
    public void testKeyIsNotFoundWithMalformedTag() throws InterruptedException, ExecutionException {
        ex.expect(WebApplicationException.class);
        ex.expectMessage(Status.NOT_FOUND.toString());
        RESOURCES.target("/storage/horse").queryParam("ref", "refs/bads/branch").request().get(JsonNode.class);
    }

    @Test
    public void testKeyIsFoundWithTags() throws InterruptedException, ExecutionException {
        CompletableFuture<Optional<StoreInfo>> expected = CompletableFuture.completedFuture(DATA.get("horse"));
        when(STORAGE.get(Mockito.matches("horse"), Mockito.matches("refs/tags/branch"))).thenReturn(expected);
        JsonNode response = RESOURCES.target("/storage/horse").queryParam("ref", "refs/tags/branch").request().get(JsonNode.class);
        assertEquals(returnedHorse, response.toString());
    }

    @Test
    public void testDoubleKeyIsFoundWithTags() throws InterruptedException, ExecutionException {
        CompletableFuture<Optional<StoreInfo>> expected = CompletableFuture.completedFuture(DATA.get("horse"));
        when(STORAGE.get(Mockito.matches("horse/horse"), Mockito.matches("refs/tags/branch"))).thenReturn(expected);
        JsonNode response = RESOURCES.target("/storage/horse/horse").queryParam("ref", "refs/tags/branch").request().get(JsonNode.class);
        assertEquals(returnedHorse, response.toString());
    }

    @Test
    public void testFaultyRef() {
        ex.expect(WebApplicationException.class);
        ex.expectMessage(Status.NOT_FOUND.toString());
        RESOURCES.target("/storage/horse").queryParam("ref", "refs/beads/branch").request().get(JsonNode.class);
    }

    @Test
    public void testEmptyRef() {
        ex.expect(WebApplicationException.class);
        ex.expectMessage(Status.NOT_FOUND.toString());
        RESOURCES.target("/storage/horse").queryParam("ref", "").request().get(JsonNode.class);
    }

    @Test
    public void testPutAKeyWithNoUser() throws IOException {
        WebTarget target = RESOURCES.target("/storage/horse");
        ModifyKeyData data = new ModifyKeyData();
        byte[] readTree = "{\"food\" : [\"wheat\",\"carrots\"]}".getBytes(UTF_8);
        data.setMessage("message");
        data.setData(readTree);
        data.setUserMail("u@m");
        data.setUserInfo("user");
        Response response = target.request().header(HttpHeaders.ETAG, "1").buildPut(Entity.entity(data, MediaType.APPLICATION_JSON))
                .invoke();
        assertEquals(Status.UNAUTHORIZED.getStatusCode(), response.getStatus());
        response.close();
    }

    @Test
    public void testPutADeletedKey() throws IOException {
        WebTarget target = RESOURCES.target("/storage/dog");
        ModifyKeyData data = new ModifyKeyData();
        byte[] readTree = "{\"food\" : [\"treats\",\"meat\"]}".getBytes(UTF_8);
        data.setMessage("message");
        data.setData(readTree);
        data.setUserMail("u@m");
        data.setUserInfo("user");
        Response response = target.request().header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED)
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON).header(HttpHeaders.IF_MATCH, "1")
                .buildPut(Entity.entity(data, MediaType.APPLICATION_JSON)).invoke();

        assertEquals(Status.NOT_FOUND.getStatusCode(), response.getStatus());
        response.close();
    }

    @Test
    public void testPutAKey() throws IOException, RefNotFoundException, InterruptedException, ExecutionException {
        WebTarget target = RESOURCES.target("/storage/dog");
        Optional<StoreInfo> storeInfo = DATA.get("dog");
        CompletableFuture<String> expected = CompletableFuture.completedFuture("2");
        when(STORAGE.get(Mockito.eq("dog"), Mockito.eq(null))).thenReturn(CompletableFuture.completedFuture(storeInfo));
        when(STORAGE.put(Mockito.any(), Mockito.eq("1"), Mockito.eq("message"), Mockito.any(), Mockito.any(), Mockito.eq("dog"),
                Mockito.eq(null))).thenReturn(expected);
        ModifyKeyData data = new ModifyKeyData();
        byte[] readTree = "{\"food\" : [\"treats\",\"steak\"]}".getBytes(UTF_8);
        data.setMessage("message");
        data.setData(readTree);
        data.setUserMail("mail");
        data.setUserInfo("user");
        Response response = target.request().header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED).header(HttpHeaders.IF_MATCH, "\"1\"")
                .buildPut(Entity.entity(data, MediaType.APPLICATION_JSON)).invoke();
        assertEquals(Status.OK.getStatusCode(), response.getStatus());
        EntityTag entityTag = response.getEntityTag();
        assertEquals(expected.get(), entityTag.getValue());
        response.close();
    }

    @Test
    public void testPutAKeyOtherVersion() throws RefNotFoundException, IOException {
        WebTarget target = RESOURCES.target("/storage/dog");
        Optional<StoreInfo> storeInfo = DATA.get("dog");
        CompletableFuture<String> expected = CompletableFuture.completedFuture("2");
        when(STORAGE.get(Mockito.eq("dog"), Mockito.eq(null))).thenReturn(CompletableFuture.completedFuture(storeInfo));
        when(STORAGE.put(Mockito.any(), Mockito.eq("1"), Mockito.eq("message"), Mockito.any(), Mockito.any(), Mockito.eq("dog"),
                Mockito.eq(null))).thenReturn(expected);
        ModifyKeyData data = new ModifyKeyData();
        byte[] readTree = "{\"food\" : [\"treats\",\"steak\"]}".getBytes(UTF_8);
        data.setMessage("message");
        data.setData(readTree);
        data.setUserMail("u@m");
        data.setUserInfo("user");
        Response response = target.request().header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED).header(HttpHeaders.IF_MATCH, "\"2\"")
                .buildPut(Entity.entity(data, MediaType.APPLICATION_JSON)).invoke();

        assertEquals(Status.PRECONDITION_FAILED.getStatusCode(), response.getStatus());
        response.close();
    }

    @Test
    public void testPutAKeyOtherVersionMissingHeader() throws RefNotFoundException, IOException {
        WebTarget target = RESOURCES.target("/storage/dog");
        Optional<StoreInfo> storeInfo = DATA.get("dog");
        CompletableFuture<String> expected = CompletableFuture.completedFuture("2");
        when(STORAGE.get(Mockito.eq("dog"), Mockito.eq(null))).thenReturn(CompletableFuture.completedFuture(storeInfo));
        when(STORAGE.put(Mockito.any(), Mockito.eq("1"), Mockito.eq("message"), Mockito.any(), Mockito.any(), Mockito.eq("dog"),
                Mockito.eq(null))).thenReturn(expected);
        ModifyKeyData data = new ModifyKeyData();
        byte[] readTree = "{\"food\" : [\"treats\",\"steak\"]}".getBytes(UTF_8);
        data.setMessage("message");
        data.setData(readTree);
        data.setUserMail("u@m");
        data.setUserInfo("user");
        Response response = target.request().header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED).buildPut(Entity.entity(data, MediaType.APPLICATION_JSON)).invoke();
        assertEquals(Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        response.close();
    }

    @Test
    public void testPutAMissingKey() throws IOException {
        WebTarget target = RESOURCES.target("/storage/horse");
        ModifyKeyData data = new ModifyKeyData();
        byte[] readTree = "{\"food\" : [\"wheat\",\"carrots\"]}".getBytes(UTF_8);
        data.setMessage("message");
        data.setData(readTree);
        data.setUserMail("u@m");
        data.setUserInfo("user");
        Response response = target.request().header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED).header(HttpHeaders.IF_MATCH, "\"1\"")
                .buildPut(Entity.entity(data, MediaType.APPLICATION_JSON)).invoke();
        assertEquals(Status.NOT_FOUND.getStatusCode(), response.getStatus());
        response.close();
    }

    @Test
    public void testPutAKeyWithNoUsers() throws IOException {
        WebTarget target = RESOURCES.target("/storage/horse");
        Optional<StoreInfo> storeInfo = DATA.get("horse");
        when(STORAGE.get(Mockito.eq("horse"), Mockito.eq(null))).thenReturn(CompletableFuture.completedFuture(storeInfo));
        ModifyKeyData data = new ModifyKeyData();
        byte[] readTree = "{\"food\" : [\"wheat\",\"carrots\"]}".getBytes(UTF_8);
        data.setMessage("message");
        data.setData(readTree);
        data.setUserMail("u@m");
        data.setUserInfo("user");
        Response response = target.request().header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED).header(HttpHeaders.IF_MATCH, "\"1\"")
                .buildPut(Entity.entity(data, MediaType.APPLICATION_JSON)).invoke();
        assertEquals(Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        response.close();
    }

    @Test
    public void testPutAKeyWithWrongUser() throws IOException {
        WebTarget target = RESOURCES.target("/storage/cat");
        Optional<StoreInfo> storeInfo = DATA.get("cat");
        when(STORAGE.get(Mockito.eq("cat"), Mockito.eq(null))).thenReturn(CompletableFuture.completedFuture(storeInfo));
        ModifyKeyData data = new ModifyKeyData();
        byte[] readTree = "{\"food\" : [\"wheat\",\"carrots\"]}".getBytes(UTF_8);
        data.setMessage("message");
        data.setData(readTree);
        data.setUserMail("u@m");
        data.setUserInfo("user");
        Response response = target.request().header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED).header(HttpHeaders.IF_MATCH, "\"1\"")
                .buildPut(Entity.entity(data, MediaType.APPLICATION_JSON)).invoke();
        assertEquals(Status.UNAUTHORIZED.getStatusCode(), response.getStatus());
        response.close();
    }

    @Test
    public void testPutKeyIsFoundButNotFoundWhenModifying() throws RefNotFoundException, IOException {
        WebTarget target = RESOURCES.target("/storage/dog");
        Optional<StoreInfo> storeInfo = DATA.get("dog");
        when(STORAGE.get(Mockito.eq("dog"), Mockito.eq(null))).thenReturn(CompletableFuture.completedFuture(storeInfo));
        when(STORAGE.put(Mockito.any(), Mockito.eq("1"), Mockito.eq("message"), Mockito.any(), Mockito.any(), Mockito.eq("dog"),
                Mockito.eq(null))).thenReturn(null);
        ModifyKeyData data = new ModifyKeyData();
        byte[] readTree = "{\"food\" : [\"treats\",\"steak\"]}".getBytes(UTF_8);
        data.setMessage("message");
        data.setData(readTree);
        data.setUserMail("u@m");
        data.setUserInfo("user");
        Response response = target.request().header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED).header(HttpHeaders.IF_MATCH, "\"1\"")
                .buildPut(Entity.entity(data, MediaType.APPLICATION_JSON)).invoke();
        assertEquals(Status.NOT_FOUND.getStatusCode(), response.getStatus());
        response.close();
    }

    @Test
    public void testPutKeyButRefIsDeletedWhilst() throws IOException {
        WebTarget target = RESOURCES.target("/storage/dog");
        Optional<StoreInfo> storeInfo = DATA.get("dog");
        when(STORAGE.get(Mockito.eq("dog"), Mockito.eq(null))).thenReturn(CompletableFuture.completedFuture(storeInfo));
        when(STORAGE.put(Mockito.any(), Mockito.eq("1"), Mockito.eq("message"), Mockito.any(), Mockito.any(), Mockito.eq("dog"),
                Mockito.eq(null))).thenReturn(CompletableFuture.supplyAsync(() -> {
                    throw new WrappingAPIException(new RefNotFoundException(""));
                }));
        ModifyKeyData data = new ModifyKeyData();
        byte[] readTree = "{\"food\" : [\"treats\",\"steak\"]}".getBytes(UTF_8);
        data.setMessage("message");
        data.setData(readTree);
        data.setUserMail("u@m");
        data.setUserInfo("user");
        Response response = target.request().header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED).header(HttpHeaders.IF_MATCH, "\"1\"")
                .buildPut(Entity.entity(data, MediaType.APPLICATION_JSON)).invoke();
        assertEquals(Status.NOT_FOUND.getStatusCode(), response.getStatus());
        response.close();
    }

    @Test
    public void testPutKeyButKeyIsDeletedWhilst() throws IOException {
        WebTarget target = RESOURCES.target("/storage/dog");
        Optional<StoreInfo> storeInfo = DATA.get("dog");
        when(STORAGE.get(Mockito.eq("dog"), Mockito.eq(null))).thenReturn(CompletableFuture.completedFuture(storeInfo));
        when(STORAGE.put(Mockito.any(), Mockito.eq("1"), Mockito.eq("message"), Mockito.any(), Mockito.any(), Mockito.eq("dog"),
                Mockito.eq(null))).thenReturn(CompletableFuture.supplyAsync(() -> {
                    throw new WrappingAPIException(new UnsupportedOperationException(""));
                }));
        ModifyKeyData data = new ModifyKeyData();
        byte[] readTree = "{\"food\" : [\"treats\",\"steak\"]}".getBytes(UTF_8);
        data.setMessage("message");
        data.setData(readTree);
        data.setUserMail("u@m");
        data.setUserInfo("user");
        Response response = target.request().header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED).header(HttpHeaders.IF_MATCH, "\"1\"")
                .buildPut(Entity.entity(data, MediaType.APPLICATION_JSON)).invoke();
        assertEquals(Status.NOT_FOUND.getStatusCode(), response.getStatus());
        response.close();
    }

    @Test
    public void testPutKeyButVersionIsChangedWhilst() throws IOException {
        WebTarget target = RESOURCES.target("/storage/dog");
        Optional<StoreInfo> storeInfo = DATA.get("dog");
        when(STORAGE.get(Mockito.eq("dog"), Mockito.eq(null))).thenReturn(CompletableFuture.completedFuture(storeInfo));
        when(STORAGE.put(Mockito.any(), Mockito.eq("1"), Mockito.eq("message"), Mockito.any(), Mockito.any(), Mockito.eq("dog"),
                Mockito.eq(null))).thenReturn(CompletableFuture.supplyAsync(() -> {
                    throw new WrappingAPIException(new VersionIsNotSameException());
                }));
        ModifyKeyData data = new ModifyKeyData();
        byte[] readTree = "{\"food\" : [\"treats\",\"steak\"]}".getBytes(UTF_8);
        data.setMessage("message");
        data.setData(readTree);
        data.setUserMail("u@m");
        data.setUserInfo("user");
        Response response = target.request().header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON)
                .header(HttpHeaders.ACCEPT, "application/json").header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED)
                .header(HttpHeaders.IF_MATCH, "\"1\"").buildPut(Entity.entity(data, MediaType.APPLICATION_JSON)).invoke();
        assertEquals(Status.CONFLICT.getStatusCode(), response.getStatus());
        response.close();
    }

    @Test
    public void testPutKeyGeneralError() throws IOException {
        WebTarget target = RESOURCES.target("/storage/dog");
        Optional<StoreInfo> storeInfo = DATA.get("dog");
        when(STORAGE.get(Mockito.eq("dog"), Mockito.eq(null))).thenReturn(CompletableFuture.completedFuture(storeInfo));
        when(STORAGE.put(Mockito.any(), Mockito.eq("1"), Mockito.eq("message"), Mockito.any(), Mockito.any(), Mockito.eq("dog"),
                Mockito.eq(null))).thenReturn(CompletableFuture.supplyAsync(() -> {
                    throw new WrappingAPIException(new Exception("Test exception"));
                }));
        ModifyKeyData data = new ModifyKeyData();
        byte[] readTree = "{\"food\" : [\"treats\",\"steak\"]}".getBytes(UTF_8);
        data.setMessage("message");
        data.setData(readTree);
        data.setUserMail("u@m");
        data.setUserInfo("user");
        Response response = target.request().header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED).header(HttpHeaders.IF_MATCH, "\"1\"")
                .buildPut(Entity.entity(data, MediaType.APPLICATION_JSON)).invoke();
        assertEquals(Status.INTERNAL_SERVER_ERROR.getStatusCode(), response.getStatus());
    }

    @Test
    public void testPutKeyOnTag() throws IOException {
        WebTarget target = RESOURCES.target("/storage/dog").queryParam("ref", "refs/tags/tag");
        ModifyKeyData data = new ModifyKeyData();
        byte[] readTree = "{\"food\" : [\"treats\",\"steak\"]}".getBytes(UTF_8);
        data.setMessage("message");
        data.setData(readTree);
        data.setUserMail("u@m");
        data.setUserInfo("user");
        Response response = target.request().header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED).header(HttpHeaders.IF_MATCH, "\"1\"")
                .buildPut(Entity.entity(data, MediaType.APPLICATION_JSON)).invoke();
        assertEquals(Status.FORBIDDEN.getStatusCode(), response.getStatus());
        response.close();
    }

    @Test
    public void testNotModified() {
        Optional<StoreInfo> storeInfo = DATA.get("dog");
        CompletableFuture<Optional<StoreInfo>> expected = CompletableFuture.completedFuture(storeInfo);
        when(STORAGE.get("dog", null)).thenReturn(expected);
        Response response = RESOURCES.target("/storage/dog").request().header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED).get();
        assertEquals(returnedDog, response.readEntity(JsonNode.class).toString());
        assertEquals(storeInfo.get().getVersion(), response.getEntityTag().getValue());
        response = RESOURCES.target("/storage/dog").request().header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED)
                .header(HttpHeaders.IF_NONE_MATCH, "\"" + storeInfo.get().getVersion() + "\"").get();
        assertEquals(Status.NOT_MODIFIED.getStatusCode(), response.getStatus());
    }

    @Test
    public void testGetapplicatiOnoctetstream() {
        CompletableFuture<Optional<StoreInfo>> expected = CompletableFuture.completedFuture(DATA.get("book"));
        when(STORAGE.get("book", null)).thenReturn(expected);
        Response response = RESOURCES.target("/storage/book").request().header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED).get();
        assertEquals(Status.OK.getStatusCode(), response.getStatus());
        assertArrayEquals(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 }, response.readEntity(byte[].class));
        response.close();
    }

    @Test
    public void testModifyApplicatiOnoctetStream() {
        CompletableFuture<Optional<StoreInfo>> expected = CompletableFuture.completedFuture(DATA.get("book"));
        when(STORAGE.get("book", null)).thenReturn(expected);
        when(STORAGE.put(Mockito.any(), Mockito.eq("1"), Mockito.eq("message"), Mockito.any(), Mockito.any(), Mockito.eq("book"),
                Mockito.eq(null))).thenReturn(CompletableFuture.completedFuture("2"));
        Response response = RESOURCES.target("/storage/book").request().header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED).get();
        assertEquals(Status.OK.getStatusCode(), response.getStatus());
        assertArrayEquals(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 }, response.readEntity(byte[].class));
        EntityTag entityTag = response.getEntityTag();
        response.close();
        ModifyKeyData data = new ModifyKeyData();
        data.setMessage("message");
        byte[] byteData = new byte[] { 8, 7, 6, 5, 4, 3, 2, 1 };
        data.setData(byteData);
        data.setUserMail("u@m");
        data.setUserInfo("user");
        response = RESOURCES.target("/storage/book").request().header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON)
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_OCTET_STREAM).header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED)
                .header(HttpHeaders.IF_MATCH, "\"" + entityTag.getValue() + "\"").buildPut(Entity.entity(data, MediaType.APPLICATION_JSON))
                .invoke();
        assertEquals(Status.OK.getStatusCode(), response.getStatus());
        response.close();
    }

    @Test
    public void testAddKey() throws JsonProcessingException {
        StoreInfo si = new StoreInfo(new byte[] { 1 }, new StorageData(new HashSet<>(), APPLICATION_JSON), "1");
        when(STORAGE.add(Mockito.eq("test"), Mockito.eq(REFS_HEADS_MASTER), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any())).thenReturn(CompletableFuture.completedFuture(si));
        AddKeyData addKeyData = new AddKeyData("test", REFS_HEADS_MASTER, new byte[] { 1 },
                new StorageData(new HashSet<>(), APPLICATION_JSON), "testmessage", "user", "test@test.com");
        Response response = RESOURCES.target("/storage").request().header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED_POST)
                .post(Entity.json(addKeyData));
        assertEquals(Status.OK.getStatusCode(), response.getStatus());
        assertEquals("1", response.getEntityTag().getValue());
        assertArrayEquals(new byte[] { 1 }, si.getData());
        assertEquals(response.getHeaderString(HttpHeaders.CONTENT_TYPE), APPLICATION_JSON);
        response.close();
    }

    @Test
    public void testAddKeyNoUser() {
        Response response = RESOURCES.target("/storage").request().post(Entity.json(new AddKeyData("test", REFS_HEADS_MASTER,
                new byte[] { 1 }, new StorageData(new HashSet<>(), APPLICATION_JSON), "testmessage", "user", "test@test.com")));
        assertEquals(Status.UNAUTHORIZED.getStatusCode(), response.getStatus());
        response.close();
    }

    @Test
    public void testAddKeyWrongUser() throws UnsupportedEncodingException {
        byte[] data = "{\"food\" : [\"treats\",\"steak\"]}".getBytes(UTF_8);
        when(STORAGE.add(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(CompletableFuture.completedFuture(new StoreInfo(data, new StorageData(new HashSet<>(), null), "1")));
        Response response = RESOURCES.target("/storage").request().header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED)
                .post(Entity.json(new AddKeyData("test", REFS_HEADS_MASTER, data, new StorageData(new HashSet<>(), APPLICATION_JSON),
                        "test", "user", "test@test.com")));
        assertEquals(Status.UNAUTHORIZED.getStatusCode(), response.getStatus());
        response.close();
    }

    @Test
    public void testAddKeyNoBranch() throws JsonProcessingException {
        Response response = RESOURCES.target("/storage").request().accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED_POST).post(Entity.json(new AddKeyData("test", "refs/tags/master",
                        new byte[] { 1 }, new StorageData(new HashSet<>(), APPLICATION_JSON), "test", "user", "test@test.com")));
        assertEquals(422, response.getStatus());
        response.close();
    }

    @Test
    public void testAddKeyKeyAlreadyExist() throws UnsupportedEncodingException {
        byte[] data = "{\"food\" : [\"treats\",\"steak\"]}".getBytes(UTF_8);
        CompletableFuture<StoreInfo> runAsync = CompletableFuture.supplyAsync(() -> {
            throw new WrappingAPIException(new KeyAlreadyExist("test", REFS_HEADS_MASTER));
        });
        when(STORAGE.add(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(runAsync);
        Response response = RESOURCES.target("/storage").request().header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED_POST)
                .post(Entity.json(new AddKeyData("test", REFS_HEADS_MASTER, data, new StorageData(new HashSet<>(), APPLICATION_JSON),
                        "test", "user", "test@test.com")));
        assertEquals(Status.CONFLICT.getStatusCode(), response.getStatus());
        response.close();
    }

    @Test
    public void testAddKeyBranchNotFound() throws UnsupportedEncodingException {
        byte[] data = "{\"food\" : [\"treats\",\"steak\"]}".getBytes(UTF_8);
        CompletableFuture<StoreInfo> runAsync = CompletableFuture.supplyAsync(() -> {
            throw new WrappingAPIException(new RefNotFoundException(REFS_HEADS_MASTER));
        });
        when(STORAGE.add(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(runAsync);
        Response response = RESOURCES.target("/storage").request().header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED_POST)
                .post(Entity.json(new AddKeyData("test", REFS_HEADS_MASTER, data, new StorageData(new HashSet<>(), APPLICATION_JSON),
                        "test", "user", "test@test.com")));
        assertEquals(Status.NOT_FOUND.getStatusCode(), response.getStatus());
        response.close();
    }

    @Test
    public void testAddKeyDataIsMalformed() {
        byte[] data = new byte[] { 1 };
        CompletableFuture<StoreInfo> runAsync = CompletableFuture.supplyAsync(() -> {
            throw new WrappingAPIException(new IOException("Data is malformed"));
        });
        when(STORAGE.add(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(runAsync);
        Response response = RESOURCES.target("/storage").request().header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED_POST)
                .post(Entity.json(new AddKeyData("test", REFS_HEADS_MASTER, data, new StorageData(new HashSet<>(), APPLICATION_JSON),
                        "test", "user", "test@test.com")));
        assertEquals(422, response.getStatus());
        response.close();
    }

    @Test
    public void testAddKeyDataWithNodata() {
        Response response = RESOURCES.target("/storage").request().header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED_POST)
                .accept(MediaType.APPLICATION_JSON).post(Entity.json(new AddKeyData("test", REFS_HEADS_MASTER, new byte[] {},
                        new StorageData(new HashSet<>(), APPLICATION_JSON), "test", "user", "test@test.com")));
        assertEquals(422, response.getStatus());
        assertEquals("[\"data size must be between 1 and 2147483647\"]", response.readEntity(JsonNode.class).get("errors").toString());
    }

    @Test
    public void testModifyKetWithoutIFMatchtag() throws UnsupportedEncodingException, InterruptedException, ExecutionException {
        WebTarget target = RESOURCES.target("/storage/dog");
        Optional<StoreInfo> storeInfo = DATA.get("dog");
        CompletableFuture<String> expected = CompletableFuture.completedFuture("2");
        when(STORAGE.get(Mockito.eq("dog"), Mockito.eq(null))).thenReturn(CompletableFuture.completedFuture(storeInfo));
        when(STORAGE.put(Mockito.any(), Mockito.eq("1"), Mockito.eq("message"), Mockito.any(), Mockito.any(), Mockito.eq("dog"),
                Mockito.eq(null))).thenReturn(expected);
        ModifyKeyData data = new ModifyKeyData();
        byte[] readTree = "{\"food\" : [\"treats\",\"steak\"]}".getBytes(UTF_8);
        data.setMessage("message");
        data.setData(readTree);
        data.setUserMail("mail");
        data.setUserInfo("user");
        Response response = target.request().header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED).header(HttpHeaders.IF_MATCH, "")
                .buildPut(Entity.entity(data, MediaType.APPLICATION_JSON)).invoke();
        assertEquals(Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        response.close();
    }

}
