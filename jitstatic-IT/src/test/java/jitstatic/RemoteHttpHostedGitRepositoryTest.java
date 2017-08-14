package jitstatic;

/*-
 * #%L
 * jitstatic
 * %%
 * Copyright (C) 2017 HHegardt
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

import org.eclipse.jetty.http.HttpHeader;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.TemporaryFolder;

import com.fasterxml.jackson.databind.deser.BuilderBasedDeserializer;

import io.dropwizard.client.JerseyClientBuilder;
import io.dropwizard.testing.ConfigOverride;
import io.dropwizard.testing.ResourceHelpers;
import io.dropwizard.testing.junit.DropwizardAppRule;
import jitstatic.storage.StorageFactory;

public class RemoteHttpHostedGitRepositoryTest {

	private static final TemporaryFolder tmpFolder = new TemporaryFolder();
	private static final DropwizardAppRule<JitstaticConfiguration> remote;
	private static final DropwizardAppRule<JitstaticConfiguration> local;
	private static final GenericType<Map<String, Object>> type = new GenericType<Map<String, Object>>() {
	};

	@ClassRule
	public static RuleChain chain = RuleChain.outerRule(tmpFolder)
			.around((remote = new DropwizardAppRule<>(JitstaticApplication.class,
					ResourceHelpers.resourceFilePath("simpleserver.yaml"),
					ConfigOverride.config("storage.baseDirectory", getFolder()),
					ConfigOverride.config("storage.localFilePath", "accept/storage2"),
					ConfigOverride.config("hosted.basePath", getFolder()))))
			.around(new EraseSystemProperties("hosted.basePath"))
			.around(new TestClientRepositoryRule(getFolder(),
					() -> remote.getConfiguration().getHostedFactory().getUserName(),
					() -> remote.getConfiguration().getHostedFactory().getSecret(), getRepo(), "accept/storage2"))
			.around((local = new DropwizardAppRule<>(JitstaticApplication.class,
					ResourceHelpers.resourceFilePath("simpleserver2.yaml"),
					ConfigOverride.config("storage.baseDirectory", getFolder()),
					ConfigOverride.config("storage.localFilePath", "accept/storage2"),
					ConfigOverride.config("remote.remoteRepo", getRepo()),
					ConfigOverride.config("remote.userName",
							() -> remote.getConfiguration().getHostedFactory().getUserName()),
					ConfigOverride.config("remote.remotePassword",
							() -> remote.getConfiguration().getHostedFactory().getSecret()))));
	private static String remoteAdress;
	private static String localAdress;

	private static String remoteBasic;
	private static String localBasic;

	@BeforeClass
	public static void setup() {
		remoteAdress = String.format("http://localhost:%d/application", remote.getLocalPort());
		localAdress = String.format("http://localhost:%d/application", local.getLocalPort());

		StorageFactory storage = remote.getConfiguration().getStorageFactory();
		remoteBasic = buildBasicAuth(storage.getUser(), storage.getSecret());
		storage = local.getConfiguration().getStorageFactory();
		localBasic = buildBasicAuth(storage.getUser(), storage.getSecret());
	}

	@Test
	public void testRemoteHostedGitRepository() {
		Client client = new JerseyClientBuilder(remote.getEnvironment()).build("test5 client");
		try {
			Map<String, Object> response = client.target(String.format("%s/storage/urlkey", remoteAdress)).request()
					.header(HttpHeader.AUTHORIZATION.asString(), remoteBasic).get(type);
			assertEquals("value", response.get("key"));
		} finally {
			client.close();
		}
		client = new JerseyClientBuilder(local.getEnvironment()).build("test6 client");
		try {
			Map<String, Object> response = client.target(String.format("%s/storage/urlkey", localAdress)).request()
					.header(HttpHeader.AUTHORIZATION.asString(), localBasic).get(type);
			assertEquals("value", response.get("key"));
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

	private static String buildBasicAuth(final String user, final String password) {
		return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes());
	}

	private static Supplier<String> getRepo() {
		return () -> String.format("http://localhost:%d/application/%s/%s", remote.getLocalPort(),
				remote.getConfiguration().getHostedFactory().getServletName(),
				remote.getConfiguration().getHostedFactory().getHostedEndpoint());
	}

}
