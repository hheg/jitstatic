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
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.TransportException;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.TemporaryFolder;

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
	private static final TemporaryFolder TMP_FOLDER = new TemporaryFolder();
	private static final HttpClientConfiguration HCC = new HttpClientConfiguration();
	private static final DropwizardAppRule<JitstaticConfiguration> DW;
	private static final TestRepositoryRule TEST_REPO;
	private static String adress;
	private static String basic;

	@ClassRule
	public static final RuleChain chain = RuleChain.outerRule(TMP_FOLDER)
			.around((TEST_REPO = new TestRepositoryRule(getFolder(), ACCEPT_STORAGE)))
			.around((DW = new DropwizardAppRule<>(JitstaticApplication.class,
					ResourceHelpers.resourceFilePath("simpleserver2.yaml"),
					ConfigOverride.config("remote.basePath", getFolder()),				
					ConfigOverride.config("remote.remoteRepo", () -> "file://" + TEST_REPO.getBase.get()))));

	@BeforeClass
	public static void setup() throws InvalidRemoteException, TransportException, GitAPIException, IOException {
		adress = String.format("http://localhost:%d/application", DW.getLocalPort());
		basic = basicAuth();
		HCC.setConnectionRequestTimeout(Duration.minutes(1));
		HCC.setConnectionTimeout(Duration.minutes(1));
		HCC.setTimeout(Duration.minutes(1));
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
			String response = client.target(String.format("%s/storage/" + ACCEPT_STORAGE, adress)).request()
					.header(HttpHeader.AUTHORIZATION.asString(), basic).get(String.class);
			assertEquals("{\"key\":{\"data\":\"value1\",\"users\":[{\"captain\":\"america\",\"black\":\"widow\"}]},\"key3\":{\"data\":\"value3\",\"users\":[{\"tony\":\"stark\",\"spider\":\"man\"}]}}", response);
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
}
