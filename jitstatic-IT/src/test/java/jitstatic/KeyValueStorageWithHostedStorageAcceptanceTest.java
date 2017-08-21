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
import java.util.Base64;
import java.util.Map;
import java.util.function.Supplier;

import javax.ws.rs.client.Client;
import javax.ws.rs.core.GenericType;
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

import io.dropwizard.client.HttpClientBuilder;
import io.dropwizard.client.HttpClientConfiguration;
import io.dropwizard.client.JerseyClientBuilder;
import io.dropwizard.testing.ConfigOverride;
import io.dropwizard.testing.ResourceHelpers;
import io.dropwizard.testing.junit.DropwizardAppRule;
import io.dropwizard.util.Duration;
import jitstatic.JitstaticApplication;
import jitstatic.JitstaticConfiguration;
import jitstatic.storage.StorageFactory;

public class KeyValueStorageWithHostedStorageAcceptanceTest {

	private static final TemporaryFolder tmpFolder = new TemporaryFolder();
	private static final DropwizardAppRule<JitstaticConfiguration> drop;
	private static final TestRepositoryRule testRepo;
	private static final GenericType<Map<String, Object>> type = new GenericType<Map<String, Object>>() {
	};

	@Rule
	public ExpectedException ex = ExpectedException.none();

	@ClassRule
	public static RuleChain chain = RuleChain.outerRule(tmpFolder)
			.around((testRepo = new TestRepositoryRule(getFolder(), "accept/storage")))
			.around((drop = new DropwizardAppRule<>(JitstaticApplication.class,
					ResourceHelpers.resourceFilePath("simpleserver2.yaml"),
					ConfigOverride.config("storage.baseDirectory", getFolder()),
					ConfigOverride.config("storage.localFilePath", "accept/storage"),
					ConfigOverride.config("remote.remoteRepo", () -> "file://" + testRepo.getBase.get()))));

	private static String adress;
	private static String basic;
	private static HttpClientConfiguration hcc = new HttpClientConfiguration();

	@BeforeClass
	public static void setup() {
		adress = String.format("http://localhost:%d/application", drop.getLocalPort());
		StorageFactory storage = drop.getConfiguration().getStorageFactory();
		basic = "Basic " + Base64.getEncoder().encodeToString((storage.getUser() + ":" + storage.getSecret()).getBytes());
		hcc.setConnectionRequestTimeout(Duration.minutes(1));
		hcc.setConnectionTimeout(Duration.minutes(1));
		hcc.setTimeout(Duration.minutes(1));
	}

	@Test
	public void testGetNotFoundKeyWithoutAuth() {
		Client client = buildClient("test client");
		try {
			Response response = client.target(String.format("%s/storage/nokey", adress)).request().get();
			assertEquals(Status.UNAUTHORIZED.getStatusCode(), response.getStatus());
		} finally {
			client.close();
		}
	}

	@Test
	public void testGetNotFoundKey() {
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
			Map<String, Object> response = client.target(String.format("%s/storage/key", adress)).request()
					.header(HttpHeader.AUTHORIZATION.asString(), basic).get(type);
			assertEquals("value", response.get("key"));
		} finally {
			client.close();
		}
	}

	@Test
	public void testGetAKeyValueWithoutAuth() {
		Client client = buildClient("test2 client");
		try {
			Response response = client.target(String.format("%s/storage/key", adress)).request().get();
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
		HttpClientBuilder httpClientBuilder = new HttpClientBuilder(drop.getEnvironment());
		httpClientBuilder.using(hcc);
		JerseyClientBuilder jerseyClientBuilder = new JerseyClientBuilder(drop.getEnvironment());
		jerseyClientBuilder.setApacheHttpClientBuilder(httpClientBuilder);
		return jerseyClientBuilder.build(name);
	}
}
