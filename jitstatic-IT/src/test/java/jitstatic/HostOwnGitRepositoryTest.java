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
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import javax.ws.rs.client.Client;
import javax.ws.rs.core.GenericType;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.RemoteRefUpdate.Status;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.RuleChain;
import org.junit.rules.TemporaryFolder;

import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.core.JsonParser.Feature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.dropwizard.client.HttpClientBuilder;
import io.dropwizard.client.HttpClientConfiguration;
import io.dropwizard.client.JerseyClientBuilder;
import io.dropwizard.setup.Environment;
import io.dropwizard.testing.ConfigOverride;
import io.dropwizard.testing.ResourceHelpers;
import io.dropwizard.testing.junit.DropwizardAppRule;
import io.dropwizard.util.Duration;
import jitstatic.JitstaticApplication;
import jitstatic.JitstaticConfiguration;
import jitstatic.storage.StorageFactory;

public class HostOwnGitRepositoryTest {

	private static final TemporaryFolder tmpFolder = new TemporaryFolder();
	private static final DropwizardAppRule<JitstaticConfiguration> drop;
	private static final GenericType<Map<String, Object>> type = new GenericType<Map<String, Object>>() {
	};

	@ClassRule
	public static RuleChain chain = RuleChain.outerRule(tmpFolder)
			.around((drop = new DropwizardAppRule<>(JitstaticApplication.class,
					ResourceHelpers.resourceFilePath("simpleserver.yaml"),
					ConfigOverride.config("storage.baseDirectory", getFolder()),
					ConfigOverride.config("storage.localFilePath", "accept/storage"),
					ConfigOverride.config("hosted.basePath", getFolder()))));

	private static UsernamePasswordCredentialsProvider provider;
	private static String basic;
	private static HttpClientConfiguration hcc = new HttpClientConfiguration();

	@Rule
	public ExpectedException ex = ExpectedException.none();

	private final ObjectMapper mapper = new ObjectMapper();

	private String gitAdress;
	private String storageAdress;

	@BeforeClass
	public static void setupClass() throws UnsupportedEncodingException {
		provider = new UsernamePasswordCredentialsProvider(drop.getConfiguration().getHostedFactory().getUserName(),
				drop.getConfiguration().getHostedFactory().getSecret());
		StorageFactory storage = drop.getConfiguration().getStorageFactory();
		basic = "Basic "
				+ Base64.getEncoder().encodeToString((storage.getUser() + ":" + storage.getSecret()).getBytes("UTF-8"));
		hcc.setConnectionRequestTimeout(Duration.minutes(1));
		hcc.setConnectionTimeout(Duration.minutes(1));
		hcc.setTimeout(Duration.minutes(1));
	}

	@Before
	public void setup() {
		gitAdress = String.format("http://localhost:%d/application/%s/%s", drop.getLocalPort(),
				drop.getConfiguration().getHostedFactory().getServletName(),
				drop.getConfiguration().getHostedFactory().getHostedEndpoint());
		storageAdress = String.format("http://localhost:%d/application", drop.getLocalPort());
		mapper.enable(Feature.ALLOW_COMMENTS);
	}

	@Test
	public void testCloneOwnHostedRepository()
			throws InvalidRemoteException, TransportException, GitAPIException, IOException {
		try (Git git = Git.cloneRepository().setDirectory(tmpFolder.newFolder()).setCredentialsProvider(provider)
				.setURI(gitAdress).call()) {
			String localFilePath = drop.getConfiguration().getStorageFactory().getLocalFilePath();
			Path path = Paths.get(git.getRepository().getDirectory().getParentFile().getAbsolutePath(), localFilePath);
			assertTrue(Files.exists(path));
		}
	}

	@Test
	public void testPushToOwnHostedRepository()
			throws InvalidRemoteException, TransportException, GitAPIException, IOException {
		try (Git git = Git.cloneRepository().setDirectory(tmpFolder.newFolder()).setURI(gitAdress)
				.setCredentialsProvider(provider).call()) {
			String localFilePath = drop.getConfiguration().getStorageFactory().getLocalFilePath();
			Path path = Paths.get(git.getRepository().getDirectory().getParentFile().getAbsolutePath(), localFilePath);

			Map<String, Map<String, Object>> sourceMap = readSource(path);
			Map<String, Object> value = new HashMap<>();
			sourceMap.put("key", value);
			writeSource(sourceMap, path);

			git.add().addFilepattern(localFilePath).call();
			git.commit().setMessage("Test commit").call();
			Iterable<PushResult> call = git.push().setCredentialsProvider(provider).call();
			PushResult pr = call.iterator().next();
			RemoteRefUpdate remoteUpdate = pr.getRemoteUpdate("refs/heads/master");
			assertEquals(Status.OK, remoteUpdate.getStatus());
		}
	}

	@Test
	public void testPushToOwnHostedRepositoryAndFetchResultFromKeyValuestorage()
			throws InvalidRemoteException, TransportException, GitAPIException, IOException {
		try (Git git = Git.cloneRepository().setDirectory(tmpFolder.newFolder()).setURI(gitAdress)
				.setCredentialsProvider(provider).call()) {
			String localFilePath = drop.getConfiguration().getStorageFactory().getLocalFilePath();
			Path path = Paths.get(git.getRepository().getDirectory().getParentFile().getAbsolutePath(), localFilePath);

			Map<String, Map<String, Object>> sourceMap = readSource(path);
			Map<String, Object> value = new HashMap<>();
			value.put("key", "value");
			sourceMap.put("urlkey", value);
			writeSource(sourceMap, path);

			git.add().addFilepattern(localFilePath).call();
			git.commit().setMessage("Test commit").call();
			Iterable<PushResult> call = git.push().setCredentialsProvider(provider).call();
			PushResult pr = call.iterator().next();
			RemoteRefUpdate remoteUpdate = pr.getRemoteUpdate("refs/heads/master");
			assertEquals(Status.OK, remoteUpdate.getStatus());
		}

		Client client = buildClient("test4 client");
		try {
			Map<String, Object> response = client.target(String.format("%s/storage/urlkey", storageAdress)).request()
					.header(HttpHeader.AUTHORIZATION.asString(), basic).get(type);
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

	private Map<String, Map<String, Object>> readSource(final Path storage) throws IOException {
		assertTrue(Files.exists(storage));
		try (InputStream bc = Files.newInputStream(storage);) {
			return mapper.readValue(bc, new TypeReference<Map<String, Map<String, Object>>>() {
			});
		}
	}

	private void writeSource(final Map<String, Map<String, Object>> map, final Path storage)
			throws JsonGenerationException, JsonMappingException, IOException {
		assertTrue(Files.exists(storage));
		mapper.writeValue(storage.toFile(), map);
	}

	private Client buildClient(final String name) {
		Environment environment = drop.getEnvironment();
		HttpClientBuilder httpClientBuilder = new HttpClientBuilder(environment);
		httpClientBuilder.using(hcc);
		JerseyClientBuilder jerseyClientBuilder = new JerseyClientBuilder(environment);
		jerseyClientBuilder.setApacheHttpClientBuilder(httpClientBuilder);
		return jerseyClientBuilder.build(name);
	}

}
