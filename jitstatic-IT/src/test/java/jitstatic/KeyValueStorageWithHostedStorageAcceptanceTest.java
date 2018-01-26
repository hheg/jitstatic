package jitstatic;

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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.SortedMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.TransportException;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.TemporaryFolder;

import com.codahale.metrics.health.HealthCheck.Result;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.dropwizard.client.HttpClientBuilder;
import io.dropwizard.client.HttpClientConfiguration;
import io.dropwizard.client.JerseyClientBuilder;
import io.dropwizard.setup.Environment;
import io.dropwizard.testing.ConfigOverride;
import io.dropwizard.testing.ResourceHelpers;
import io.dropwizard.testing.junit.DropwizardAppRule;
import io.dropwizard.util.Duration;
import jitstatic.api.ModifyKeyData;
import jitstatic.tools.TestRepositoryRule;

public class KeyValueStorageWithHostedStorageAcceptanceTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final String ACCEPT_STORAGE = "accept/storage";
	private static final String USER = "suser";
	private static final String PASSWORD = "ssecret";
	private static final TemporaryFolder TMP_FOLDER = new TemporaryFolder();
	private final HttpClientConfiguration HCC = new HttpClientConfiguration();
	private DropwizardAppRule<JitstaticConfiguration> DW;
	private TestRepositoryRule TEST_REPO;
	private String adress;
	private String basic;

	@Rule
	public final RuleChain chain = RuleChain.outerRule(TMP_FOLDER)
			.around((TEST_REPO = new TestRepositoryRule(getFolder(), ACCEPT_STORAGE)))
			.around((DW = new DropwizardAppRule<>(JitstaticApplication.class, ResourceHelpers.resourceFilePath("simpleserver2.yaml"),
					ConfigOverride.config("remote.basePath", getFolder()),
					ConfigOverride.config("remote.remoteRepo", () -> "file://" + TEST_REPO.getBase.get()))));

	@Before
	public void setup() throws InvalidRemoteException, TransportException, GitAPIException, IOException {
		adress = String.format("http://localhost:%d/application", DW.getLocalPort());
		basic = basicAuth();
		HCC.setConnectionRequestTimeout(Duration.minutes(1));
		HCC.setConnectionTimeout(Duration.minutes(1));
		HCC.setTimeout(Duration.minutes(1));
	}

	@After
	public void after() {
		SortedMap<String, Result> healthChecks = DW.getEnvironment().healthChecks().runHealthChecks();
		List<Throwable> errors = healthChecks.entrySet().stream().map(e -> e.getValue().getError()).filter(Objects::nonNull)
				.collect(Collectors.toList());
		errors.stream().forEach(e -> e.printStackTrace());
		assertThat(errors.toString(), errors.isEmpty(), Matchers.is(true));
	}

	@Test
	public void testGetNotFoundKeyWithoutAuth() {
		Client client = buildClient("test client");
		try {
			Response response = client.target(String.format("%s/storage/nokey", adress)).request().get();
			assertEquals(Status.NOT_FOUND.getStatusCode(), response.getStatus());
		} finally {
			client.close();
		}
	}

	@Test
	public void testGetNotFoundKeyWithAuth() {
		Client client = buildClient("test3 client");
		try {
			Response response = client.target(String.format("%s/storage/nokey", adress)).request()
					.header(HttpHeader.AUTHORIZATION.asString(), basic).get();
			assertEquals(Status.NOT_FOUND.getStatusCode(), response.getStatus());
		} finally {
			client.close();
		}
	}

	@Test
	public void testGetAKeyValue() {
		Client client = buildClient("test4 client");
		try {
			JsonNode response = client.target(String.format("%s/storage/" + ACCEPT_STORAGE, adress)).request()
					.header(HttpHeader.AUTHORIZATION.asString(), basic).get(JsonNode.class);
			assertEquals(
					getData(),
					response.get("data").toString());
			assertNotNull(response.get("version").toString());
		} finally {
			client.close();
		}
	}

	@Test
	public void testGetAKeyValueWithoutAuth() {
		Client client = buildClient("test2 client");
		try {
			Response response = client.target(String.format("%s/storage/" + ACCEPT_STORAGE, adress)).request().get();
			assertEquals(Status.UNAUTHORIZED.getStatusCode(), response.getStatus());
		} finally {
			client.close();
		}
	}

	@Test
	public void testModifyAKey() throws JsonParseException, JsonMappingException, IOException {
		Client client = buildClient("testmodify client");
		try {
			WebTarget target = client.target(String.format("%s/storage/" + ACCEPT_STORAGE, adress));
			JsonNode response = target.request().header(HttpHeader.AUTHORIZATION.asString(), basic).get(JsonNode.class);
			assertEquals(getData(),response.get("data").toString());
			JsonNode jsonNode = response.get("version");
			String oldVersion = jsonNode.asText();
			ModifyKeyData data = new ModifyKeyData();
			JsonNode newData = MAPPER.readValue("{\"one\":\"two\"}", JsonNode.class);
			data.setData(newData);
			data.setMessage("commit message");
			data.setHaveVersion(oldVersion);
			String invoke = target.request().header(HttpHeader.AUTHORIZATION.asString(), basic).buildPut(Entity.json(data)).invoke(String.class);
			assertNotEquals(oldVersion, invoke);
			response = target.request().header(HttpHeader.AUTHORIZATION.asString(), basic).get(JsonNode.class);
			assertEquals(newData, response.get("data"));
		} finally {
			client.close();
		}
	}

	private static Supplier<String> getFolder() {
		return () -> {
			try {
				return TMP_FOLDER.newFolder().toString();
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		};
	}

	private Client buildClient(final String name) {
		Environment env = DW.getEnvironment();
		JerseyClientBuilder jerseyClientBuilder = new JerseyClientBuilder(env);
		jerseyClientBuilder.setApacheHttpClientBuilder(new HttpClientBuilder(env).using(HCC));
		return jerseyClientBuilder.build(name);
	}

	private static String basicAuth() throws UnsupportedEncodingException {
		return "Basic " + Base64.getEncoder().encodeToString((USER + ":" + PASSWORD).getBytes("UTF-8"));
	}

	private static String getData() {
		return "{\"key\":{\"data\":\"value1\",\"users\":[{\"captain\":\"america\",\"black\":\"widow\"}]},\"key3\":{\"data\":\"value3\",\"users\":[{\"tony\":\"stark\",\"spider\":\"man\"}]}}";
	}
}
