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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import javax.ws.rs.client.Client;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.RemoteRefUpdate.Status;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.TemporaryFolder;

import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.core.JsonParser.Feature;
import com.fasterxml.jackson.core.type.TypeReference;
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
import jitstatic.auth.User;
import jitstatic.hosted.HostedFactory;
import jitstatic.storage.StorageData;

public class HostOwnGitRepositoryTest {

	private static final String ACCEPT_STORAGE = "accept/storage";
	private static final TemporaryFolder tmpFolder = new TemporaryFolder();
	private static final DropwizardAppRule<JitstaticConfiguration> DW;
	private static final ObjectMapper mapper = new ObjectMapper();
	private static final String USER = "suser";
	private static final String PASSWORD = "ssecret";
	private static final HttpClientConfiguration hcc = new HttpClientConfiguration();
	
	@ClassRule
	public static final RuleChain chain = RuleChain.outerRule(tmpFolder)
			.around((DW = new DropwizardAppRule<>(JitstaticApplication.class,
					ResourceHelpers.resourceFilePath("simpleserver.yaml"),
					ConfigOverride.config("storage.baseDirectory", getFolder()),
					ConfigOverride.config("storage.localFilePath", ACCEPT_STORAGE),
					ConfigOverride.config("hosted.basePath", getFolder()))));

	private static UsernamePasswordCredentialsProvider provider;
	private static String basic;
	
	private static String gitAdress;
	private static String storageAdress;
	
	@BeforeClass
	public static void setupClass() throws UnsupportedEncodingException {
		final HostedFactory hf = DW.getConfiguration().getHostedFactory();
		provider = new UsernamePasswordCredentialsProvider(hf.getUserName(), hf.getSecret());
		basic = getBasicAuth();
		mapper.enable(Feature.ALLOW_COMMENTS);
		int localPort = DW.getLocalPort();
		gitAdress = String.format("http://localhost:%d/application/%s/%s", localPort, hf.getServletName(),
				hf.getHostedEndpoint());
		storageAdress = String.format("http://localhost:%d/application", localPort);
		hcc.setConnectionRequestTimeout(Duration.minutes(1));
		hcc.setConnectionTimeout(Duration.minutes(1));
		hcc.setTimeout(Duration.minutes(1));
	}

	@Test
	public void testCloneOwnHostedRepository() throws Exception {
		try (Git git = Git.cloneRepository().setDirectory(tmpFolder.newFolder()).setCredentialsProvider(provider)
				.setURI(gitAdress).call()) {
			String localFilePath = getLocalFilePath();
			Path path = Paths.get(getRepopath(git), localFilePath);
			assertTrue(Files.exists(path));
		}
	}

	@Test
	public void testPushToOwnHostedRepositoryAndFetchResultFromKeyValuestorage() throws Exception {
		StorageData data = null;
		try (Git git = Git.cloneRepository().setDirectory(tmpFolder.newFolder()).setURI(gitAdress)
				.setCredentialsProvider(provider).call()) {
			String localFilePath = getLocalFilePath();
			Path path = Paths.get(getRepopath(git), localFilePath);

			Map<String, StorageData> sourceMap = readSource(path);

			Set<User> users = new HashSet<>();
			users.add(new User(USER, PASSWORD));

			data = new StorageData(users, mapper.readTree("\"value\""));
			sourceMap.put("key1", data);

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
			JsonNode response = client.target(String.format("%s/storage/key1", storageAdress)).request()
					.header(HttpHeader.AUTHORIZATION.asString(), basic).get(JsonNode.class);
			assertEquals(data.getData(), response);
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

	private static String getBasicAuth() throws UnsupportedEncodingException {
		return "Basic "
				+ Base64.getEncoder().encodeToString((USER + ":" + PASSWORD).getBytes("UTF-8"));
	}

	private Map<String, StorageData> readSource(final Path storage) throws IOException {
		assertTrue(Files.exists(storage));
		try (InputStream bc = Files.newInputStream(storage);) {
			return mapper.readValue(bc, new TypeReference<Map<String, StorageData>>() {
			});
		}
	}

	private void writeSource(final Map<String, StorageData> map, final Path storage)
			throws JsonGenerationException, JsonMappingException, IOException {
		assertTrue(Files.exists(storage));
		mapper.writeValue(storage.toFile(), map);
	}

	private Client buildClient(final String name) {
		Environment environment = DW.getEnvironment();
		JerseyClientBuilder jerseyClientBuilder = new JerseyClientBuilder(environment);
		jerseyClientBuilder.setApacheHttpClientBuilder(new HttpClientBuilder(environment).using(hcc));
		return jerseyClientBuilder.build(name);
	}

	private String getRepopath(Git git) {
		return git.getRepository().getDirectory().getParentFile().getAbsolutePath();
	}

	private String getLocalFilePath() {
		return DW.getConfiguration().getStorageFactory().getLocalFilePath();
	}

}
