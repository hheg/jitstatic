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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import org.glassfish.jersey.server.filter.RolesAllowedDynamicFeature;
import org.glassfish.jersey.test.grizzly.GrizzlyWebTestContainerFactory;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.Mockito;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.dropwizard.auth.AuthDynamicFeature;
import io.dropwizard.auth.AuthValueFactoryProvider;
import io.dropwizard.auth.basic.BasicCredentialAuthFilter;
import io.dropwizard.testing.junit.ResourceTestRule;
import jitstatic.auth.ConfiguratedAuthenticator;
import jitstatic.auth.User;
import jitstatic.storage.Storage;
import jitstatic.storage.StorageData;
import jitstatic.storage.StoreInfo;

public class MapResourceTest {
	private static final String USER = "user";
	private static final String SECRET = "secret";
	private static final String BASIC_AUTH_CRED;
	static {
		try {
			BASIC_AUTH_CRED = "Basic " + Base64.getEncoder().encodeToString((USER + ":" + SECRET).getBytes("UTF-8"));
		} catch (UnsupportedEncodingException e) {
			throw new Error(e);
		}
	}
	private static final Storage STORAGE = mock(Storage.class);
	private static final Map<String, StoreInfo> DATA = new HashMap<>();
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static String returnedDog;
	private static String returnedHorse;

	@Rule
	public ExpectedException ex = ExpectedException.none();

	@ClassRule
	public static final ResourceTestRule RESOURCES = ResourceTestRule.builder()
			.setTestContainerFactory(new GrizzlyWebTestContainerFactory())
			.addProvider(new AuthDynamicFeature(new BasicCredentialAuthFilter.Builder<User>()
					.setAuthenticator(new ConfiguratedAuthenticator()).setRealm("jitstatic").buildAuthFilter()))
			.addProvider(RolesAllowedDynamicFeature.class)
			.addProvider(new AuthValueFactoryProvider.Binder<>(User.class)).addResource(new MapResource(STORAGE))
			.build();

	@BeforeClass
	public static void setupClass() throws JsonProcessingException, IOException {
		JsonNode dog = MAPPER.readTree("{\"food\" : [\"bone\",\"meat\"]}");
		Set<User> users = new HashSet<>(Arrays.asList(new User(USER, SECRET)));
		StoreInfo dogData = new StoreInfo(new StorageData(users, dog), "1");
		returnedDog = MAPPER.writeValueAsString(new KeyData(dogData.getVersion(), dogData.getStorageData().getData()));
		DATA.put("dog", dogData);
		JsonNode horse = MAPPER.readTree("{\"food\" : [\"wheat\",\"grass\"]}");
		StoreInfo horseData = new StoreInfo(new StorageData(new HashSet<>(), horse), "1");
		returnedHorse = MAPPER
				.writeValueAsString(new KeyData(horseData.getVersion(), horseData.getStorageData().getData()));
		DATA.put("horse", horseData);
	}

	@Test
	public void testGettingKeyFromResource() throws InterruptedException, ExecutionException {
		Future<StoreInfo> expected = CompletableFuture.completedFuture(DATA.get("dog"));
		when(STORAGE.get("dog", null)).thenReturn(expected);
		JsonNode response = RESOURCES.target("/storage/dog").request()
				.header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED).get(JsonNode.class);
		assertEquals(returnedDog, response.toString());
	}

	@Test
	public void testGettingKeyFromResourceWithNoAuthentication() {
		ex.expect(WebApplicationException.class);
		ex.expectMessage(Status.UNAUTHORIZED.toString());
		Future<StoreInfo> expected = CompletableFuture.completedFuture(DATA.get("dog"));
		when(STORAGE.get("dog", null)).thenReturn(expected);
		RESOURCES.target("/storage/dog").request().get(JsonNode.class);
	}

	@Test
	public void testKeyNotFound() {
		ex.expect(WebApplicationException.class);
		ex.expectMessage(Status.NOT_FOUND.toString());
		when(STORAGE.get(any(), Mockito.anyString())).thenReturn(null);
		RESOURCES.target("/storage/cat").request().header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED)
				.get(StorageData.class);
	}

	@Test
	public void testNoAuthorizationOnPermittedResource() throws InterruptedException, ExecutionException {
		Future<StoreInfo> expected = CompletableFuture.completedFuture(DATA.get("horse"));
		when(STORAGE.get("horse", null)).thenReturn(expected);
		JsonNode response = RESOURCES.target("/storage/horse").request().get(JsonNode.class);
		assertEquals(returnedHorse, response.toString());
	}

	@Test
	public void testKeyIsFoundButWrongUser() throws UnsupportedEncodingException {
		ex.expect(WebApplicationException.class);
		ex.expectMessage(Status.UNAUTHORIZED.toString());
		Future<StoreInfo> expected = CompletableFuture.completedFuture(DATA.get("dog"));
		when(STORAGE.get("dog", null)).thenReturn(expected);
		final String bac = "Basic " + Base64.getEncoder().encodeToString(("anotheruser:" + SECRET).getBytes("UTF-8"));
		RESOURCES.target("/storage/dog").request().header(HttpHeaders.AUTHORIZATION, bac).get(JsonNode.class);
	}

	@Test
	public void testKeyIsFoundWithBranch() throws InterruptedException, ExecutionException {
		Future<StoreInfo> expected = CompletableFuture.completedFuture(DATA.get("horse"));
		when(STORAGE.get(Mockito.matches("horse"), Mockito.matches("refs/heads/branch"))).thenReturn(expected);
		JsonNode response = RESOURCES.target("/storage/horse").queryParam("ref", "refs/heads/branch").request()
				.get(JsonNode.class);
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
		Future<StoreInfo> expected = CompletableFuture.completedFuture(DATA.get("horse"));
		when(STORAGE.get(Mockito.matches("horse"), Mockito.matches("refs/tags/branch"))).thenReturn(expected);
		JsonNode response = RESOURCES.target("/storage/horse").queryParam("ref", "refs/tags/branch").request()
				.get(JsonNode.class);
		assertEquals(returnedHorse, response.toString());
	}

	@Test
	public void testDoubleKeyIsFoundWithTags() throws InterruptedException, ExecutionException {
		Future<StoreInfo> expected = CompletableFuture.completedFuture(DATA.get("horse"));
		when(STORAGE.get(Mockito.matches("horse/horse"), Mockito.matches("refs/tags/branch"))).thenReturn(expected);
		JsonNode response = RESOURCES.target("/storage/horse/horse").queryParam("ref", "refs/tags/branch").request()
				.get(JsonNode.class);
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
	public void testPutAKey() throws IOException {
		WebTarget target = RESOURCES.target("/storage/horse");
		StoreInfo storeInfo = DATA.get("horse");
		Future<Void> expected = CompletableFuture.completedFuture(null);
		when(STORAGE.put(Mockito.eq(storeInfo.getStorageData().getData()), Mockito.eq("1"), Mockito.eq("message"),
				Mockito.any(), Mockito.eq("horse"), Mockito.any())).thenReturn(expected);
		ModifyKeyData data = new ModifyKeyData();
		JsonNode readTree = MAPPER.readTree("{\"food\" : [\"wheat\",\"carrots\"]}");
		data.setMessage("message");
		data.setVersion("1");
		data.setData(readTree);
		Response response = target.request().header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED)
				.buildPut(Entity.entity(data, MediaType.APPLICATION_JSON)).invoke();
		assertEquals(Status.OK,response.getStatus());		
	}
	
	@Test
	public void testPutAMissingKey() throws IOException {
		WebTarget target = RESOURCES.target("/storage/horse");
		ModifyKeyData data = new ModifyKeyData();
		JsonNode readTree = MAPPER.readTree("{\"food\" : [\"wheat\",\"carrots\"]}");
		data.setMessage("message");
		data.setVersion("1");
		data.setData(readTree);
		Response response = target.request().header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED)
				.buildPut(Entity.entity(data, MediaType.APPLICATION_JSON)).invoke();
		assertEquals(Status.NOT_FOUND.getStatusCode(), response.getStatus());
	}
}
