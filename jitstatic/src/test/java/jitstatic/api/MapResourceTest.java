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

import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response.Status;

import org.glassfish.jersey.server.filter.RolesAllowedDynamicFeature;
import org.glassfish.jersey.test.grizzly.GrizzlyWebTestContainerFactory;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import io.dropwizard.auth.AuthDynamicFeature;
import io.dropwizard.auth.AuthValueFactoryProvider;
import io.dropwizard.auth.basic.BasicCredentialAuthFilter;
import io.dropwizard.testing.junit.ResourceTestRule;
import jitstatic.api.MapResource;
import jitstatic.auth.ConfiguratedAuthenticator;
import jitstatic.auth.User;
import jitstatic.storage.Storage;

public class MapResourceTest {
	private static final String user = "user";
	private static final String secret = "secret";
	private static final String basicAuthCred = "Basic " + Base64.getEncoder().encodeToString((user + ":" + secret).getBytes());
	private static final Storage storage = mock(Storage.class);
	private static final Map<String, Map<String, Object>> data = new HashMap<>();
	private static final GenericType<Map<String, Object>> genericType = new GenericType<Map<String, Object>>() {
	};
	@Rule
	public ExpectedException ex = ExpectedException.none();

	@ClassRule
	public static final ResourceTestRule resources = ResourceTestRule.builder()
			.setTestContainerFactory(new GrizzlyWebTestContainerFactory())
			.addProvider(new AuthDynamicFeature(new BasicCredentialAuthFilter.Builder<User>()
					.setAuthenticator(new ConfiguratedAuthenticator(user, secret)).setRealm("jitstatic")
					.buildAuthFilter()))
			.addProvider(RolesAllowedDynamicFeature.class)
			.addProvider(new AuthValueFactoryProvider.Binder<>(User.class)).addResource(new MapResource(storage)).build();

	@BeforeClass
	public static void setupClass() {
		Map<String, Object> dogData = new HashMap<>();
		dogData.put("food", Arrays.asList(new String[] { "bone", "meat" }));
		Map<String, Object> dogToys = new HashMap<>();
		dogToys.put("ball", "frizbee");
		dogData.put("toys", dogToys);
		data.put("dog", dogData);
	}

	@Test
	public void testGettingKeyFromResource() {
		Map<String, Object> expected = data.get("dog");
		when(storage.get("dog")).thenReturn(expected);
		Map<String, Object> response = resources.target("/storage/dog").request().header(HttpHeaders.AUTHORIZATION, basicAuthCred)
				.get(genericType);

		assertEquals(expected, response);

	}

	@Test
	public void testKeyNotFound() {
		ex.expect(WebApplicationException.class);
		ex.expectMessage(Status.NOT_FOUND.toString());
		when(storage.get(any())).thenReturn(null);

		resources.target("/storage/cat").request().header(HttpHeaders.AUTHORIZATION, basicAuthCred).get(genericType);
	}
}
