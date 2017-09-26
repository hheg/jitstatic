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

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Base64;
import java.util.function.Supplier;

import javax.ws.rs.client.Client;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import org.eclipse.jetty.http.HttpHeader;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.RuleChain;
import org.junit.rules.TemporaryFolder;

import com.fasterxml.jackson.databind.JsonNode;

import io.dropwizard.client.HttpClientBuilder;
import io.dropwizard.client.HttpClientConfiguration;
import io.dropwizard.client.JerseyClientBuilder;
import io.dropwizard.setup.Environment;
import io.dropwizard.testing.ConfigOverride;
import io.dropwizard.testing.ResourceHelpers;
import io.dropwizard.testing.junit.DropwizardAppRule;
import io.dropwizard.util.Duration;

public class KeyValueStorageWithHostedStorageAcceptanceTest {

	private static final String ACCEPT_STORAGE = "accept/storage";
	private static final String USER = "suser";
	private static final String PASSWORD = "ssecret";
	private static final TemporaryFolder tmpFolder = new TemporaryFolder();
	private static final HttpClientConfiguration hcc = new HttpClientConfiguration();
	private static final DropwizardAppRule<JitstaticConfiguration> DW;
	private static final TestRepositoryRule testRepo;
	private static String adress;
	private static String basic;

	@ClassRule
	public static final RuleChain chain = RuleChain.outerRule(tmpFolder)
			.around((testRepo = new TestRepositoryRule(getFolder(), ACCEPT_STORAGE)))
			.around((DW = new DropwizardAppRule<>(JitstaticApplication.class,
					ResourceHelpers.resourceFilePath("simpleserver2.yaml"),
					ConfigOverride.config("remote.basePath", getFolder()),
					ConfigOverride.config("remote.localFilePath", ACCEPT_STORAGE),
					ConfigOverride.config("remote.remoteRepo", () -> "file://" + testRepo.getBase.get()))));

	@BeforeClass
	public static void setup() throws UnsupportedEncodingException {
		adress = String.format("http://localhost:%d/application", DW.getLocalPort());
		basic = basicAuth();
		hcc.setConnectionRequestTimeout(Duration.minutes(1));
		hcc.setConnectionTimeout(Duration.minutes(1));
		hcc.setTimeout(Duration.minutes(1));
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
			JsonNode response = client.target(String.format("%s/storage/key1", adress)).request()
					.header(HttpHeader.AUTHORIZATION.asString(), basic).get(JsonNode.class);
			assertEquals("value1", response.asText());
		} finally {
			client.close();
		}
	}

	@Test
	public void testGetAKeyValueWithoutAuth() {
		Client client = buildClient("test2 client");
		try {
			Response response = client.target(String.format("%s/storage/key1", adress)).request().get();
			assertEquals(Status.UNAUTHORIZED.getStatusCode(), response.getStatus());
		} finally {
			client.close();
		}
	}

	private static Supplier<String> getFolder() {
		return () -> {
			try {
				return tmpFolder.newFolder().toString();
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		};
	}

	private Client buildClient(final String name) {
		Environment env = DW.getEnvironment();
		JerseyClientBuilder jerseyClientBuilder = new JerseyClientBuilder(env);
		jerseyClientBuilder.setApacheHttpClientBuilder(new HttpClientBuilder(env).using(hcc));
		return jerseyClientBuilder.build(name);
	}

	private static String basicAuth() throws UnsupportedEncodingException {
		return "Basic " + Base64.getEncoder().encodeToString((USER + ":" + PASSWORD).getBytes("UTF-8"));
	}

}
