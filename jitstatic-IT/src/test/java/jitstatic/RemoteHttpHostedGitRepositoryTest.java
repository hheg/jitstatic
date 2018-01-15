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

import static org.hamcrest.core.Is.isA;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.SortedMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.WebTarget;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.errors.NoRemoteRepositoryException;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.RemoteRefUpdate.Status;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.RuleChain;
import org.junit.rules.TemporaryFolder;

import com.codahale.metrics.health.HealthCheck.Result;
import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.dropwizard.client.JerseyClientBuilder;
import io.dropwizard.testing.ConfigOverride;
import io.dropwizard.testing.ResourceHelpers;
import io.dropwizard.testing.junit.DropwizardAppRule;
import jitstatic.hosted.HostedFactory;

public class RemoteHttpHostedGitRepositoryTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final String REFS_HEADS_MASTER = "refs/heads/master";
	private static final String PASSWORD = "ssecret";
	private static final String USER = "suser";
	private static final String ACCEPT_STORAGE = "accept/storage";
	private static final TemporaryFolder TMP_FOLDER = new TemporaryFolder();
	private DropwizardAppRule<JitstaticConfiguration> remote;
	private DropwizardAppRule<JitstaticConfiguration> local;

	private String remoteAdress;
	private String localAdress;

	private String basicAuth;

	private UsernamePasswordCredentialsProvider provider;
	private JsonNode expectedResponse;

	@Rule
	public RuleChain chain = RuleChain.outerRule(TMP_FOLDER)
			.around((remote = new DropwizardAppRule<>(JitstaticApplication.class, ResourceHelpers.resourceFilePath("simpleserver.yaml"),
					ConfigOverride.config("hosted.basePath", getFolder()))))
			.around(new EraseSystemProperties("hosted.basePath"))
			.around(new TestClientRepositoryRule(getFolder(), () -> remote.getConfiguration().getHostedFactory().getUserName(),
					() -> remote.getConfiguration().getHostedFactory().getSecret(), getRepo(), ACCEPT_STORAGE))
			.around((local = new DropwizardAppRule<>(JitstaticApplication.class, ResourceHelpers.resourceFilePath("simpleserver2.yaml"),
					ConfigOverride.config("remote.basePath", getFolder()), ConfigOverride.config("remote.remoteRepo", getRepo()),
					ConfigOverride.config("remote.userName", () -> remote.getConfiguration().getHostedFactory().getUserName()),
					ConfigOverride.config("remote.remotePassword", () -> remote.getConfiguration().getHostedFactory().getSecret()),
					ConfigOverride.config("remote.pollingPeriod", "1 second"))));

	@Rule
	public final ExpectedException ex = ExpectedException.none();

	@Before
	public void setup() throws JsonProcessingException, IOException {
		basicAuth = buildBasicAuth(USER, PASSWORD);

		remoteAdress = String.format("http://localhost:%d/application", remote.getLocalPort());

		HostedFactory hf = remote.getConfiguration().getHostedFactory();
		provider = new UsernamePasswordCredentialsProvider(hf.getUserName(), hf.getSecret());

		localAdress = String.format("http://localhost:%d/application", local.getLocalPort());
		assertTrue(local.getConfiguration().getHostedFactory() == null);
		try (InputStream is = RemoteHttpHostedGitRepositoryTest.class.getResourceAsStream("/" + ACCEPT_STORAGE)) {
			expectedResponse = MAPPER.readValue(is, StorageData.class).getData();
		}
	}

	@After
	public void after() {
		SortedMap<String, Result> healthChecks = local.getEnvironment().healthChecks().runHealthChecks();
		List<Throwable> errors = healthChecks.entrySet().stream().map(e -> e.getValue().getError()).filter(Objects::nonNull)
				.collect(Collectors.toList());
		errors.stream().forEach(e -> e.printStackTrace());
		assertThat(errors.toString(), errors.isEmpty(), Matchers.is(true));
	}

	@Test
	public void testRemoteHostedGitRepository() {
		Client client = new JerseyClientBuilder(remote.getEnvironment()).build("test5 client");
		try {
			JsonNode response = client.target(String.format("%s/storage/" + ACCEPT_STORAGE, remoteAdress)).request()
					.header(HttpHeader.AUTHORIZATION.asString(), basicAuth).get(JsonNode.class);
			assertEquals(expectedResponse, response.get("data"));
		} finally {
			client.close();
		}
		client = new JerseyClientBuilder(local.getEnvironment()).build("test6 client");
		try {
			JsonNode response = client.target(String.format("%s/storage/" + ACCEPT_STORAGE, localAdress)).request()
					.header(HttpHeader.AUTHORIZATION.asString(), basicAuth).get(JsonNode.class);
			assertEquals(expectedResponse, response.get("data"));
		} finally {
			client.close();
		}
	}

	@Test
	public void testRemoteHostedGitUpdate() throws InvalidRemoteException, TransportException, GitAPIException, IOException {
		JsonNode originalValue = null;
		Client client = new JerseyClientBuilder(local.getEnvironment()).build("test7 client");
		try {
			WebTarget target = client.target(String.format("%s/storage/" + ACCEPT_STORAGE, localAdress));
			JsonNode response = target.request().header(HttpHeader.AUTHORIZATION.asString(), basicAuth).get(JsonNode.class);
			JsonNode oldVersion = response.get("version");
			assertEquals(expectedResponse, response.get("data"));
			originalValue = response;

			Path newFolder = TMP_FOLDER.newFolder().toPath();
			Path storage = newFolder.resolve(ACCEPT_STORAGE);
			try (Git git = Git.cloneRepository().setDirectory(newFolder.toFile()).setURI(getRepo().get()).setCredentialsProvider(provider)
					.call();) {

				StorageData data = readSource(storage);
				JsonNode readTree = MAPPER.readTree("{\"one\":\"two\"}");
				StorageData newData = new StorageData(data.getUsers(), readTree);

				writeSource(newData, storage);

				git.add().addFilepattern(ACCEPT_STORAGE).call();
				RevCommit commit = git.commit().setMessage("Evolve").call();
				Iterable<PushResult> result = git.push().setCredentialsProvider(provider).call();

				RemoteRefUpdate remoteUpdate = result.iterator().next().getRemoteUpdate(REFS_HEADS_MASTER);
				assertEquals(Status.OK, remoteUpdate.getStatus());

				sleep(1500);

				response = target.request().header(HttpHeader.AUTHORIZATION.asString(), basicAuth).get(JsonNode.class);
				assertNotEquals(oldVersion, response.get("version"));
				assertEquals(readTree, response.get("data"));

				git.revert().include(commit).call();
				result = git.push().setCredentialsProvider(provider).call();
				remoteUpdate = result.iterator().next().getRemoteUpdate(REFS_HEADS_MASTER);
				assertEquals(Status.OK, remoteUpdate.getStatus());

				sleep(1500);

				response = target.request().header(HttpHeader.AUTHORIZATION.asString(), basicAuth).get(JsonNode.class);
				assertEquals(originalValue, response);

			}
		} finally {
			client.close();
		}
	}

	@Test
	public void testRemoteHostedJitStaticShouldNotHaveAnyRepoAccess()
			throws InvalidRemoteException, TransportException, GitAPIException, IOException {
		ex.expect(InvalidRemoteException.class);
		ex.expectCause(isA(NoRemoteRepositoryException.class));
		String host = String.format("http://localhost:%d/application/%s/%s", local.getLocalPort(), "selfhosted", "git");
		try (Git git = Git.cloneRepository().setDirectory(TMP_FOLDER.newFolder()).setURI(host).setCredentialsProvider(provider).call()) {
			fail("There shouldn't be any git repo when not hosted");
		}
	}

	@Test
	public void testSingleGetTag() throws InvalidRemoteException, TransportException, GitAPIException, IOException {
		String tag = "tag";
		String store = "store";
		File base = TMP_FOLDER.newFolder();
		Client client = new JerseyClientBuilder(local.getEnvironment()).build("test9 client");
		try {
			String host = String.format("http://localhost:%d/application/%s/%s", remote.getLocalPort(), "selfhosted", "git");
			try (Git git = Git.cloneRepository().setDirectory(base).setURI(host).setCredentialsProvider(provider).call()) {
				Path file = base.toPath().resolve(store);
				Files.write(file, getData(1).getBytes("UTF-8"), StandardOpenOption.CREATE_NEW);
				git.add().addFilepattern(store).call();
				git.commit().setMessage("First").call();
				git.tag().setName(tag).call();
				Files.write(file, getData(2).getBytes("UTF-8"), StandardOpenOption.TRUNCATE_EXISTING);
				git.add().addFilepattern(store).call();
				git.commit().setMessage("Second").call();
				git.push().setCredentialsProvider(provider).call();
				git.push().setCredentialsProvider(provider).setPushTags().call();
			}
			sleep(3000);
			JsonNode result = client.target(String.format("%s/storage/" + store + "?ref=refs/tags/" + tag, localAdress)).request()
					.header(HttpHeader.AUTHORIZATION.asString(), basicAuth).get(JsonNode.class);
			StorageData value = MAPPER.readValue(getData(1), StorageData.class);
			assertEquals(value.getData(), result.get("data"));
		} finally {
			client.close();
		}
	}

	@Test
	public void testDoubleTag() throws InvalidRemoteException, TransportException, GitAPIException, IOException {
		String tag = "tag";
		File base = TMP_FOLDER.newFolder();
		Client client = new JerseyClientBuilder(local.getEnvironment()).build("test8 client");
		try {
			String host = String.format("http://localhost:%d/application/%s/%s", remote.getLocalPort(), "selfhosted", "git");
			try (Git git = Git.cloneRepository().setDirectory(base).setURI(host).setCredentialsProvider(provider).call()) {
				Path file = base.toPath().resolve(ACCEPT_STORAGE);
				Files.write(file, getData(1).getBytes("UTF-8"), StandardOpenOption.TRUNCATE_EXISTING);
				git.add().addFilepattern(ACCEPT_STORAGE).call();
				git.commit().setMessage("First").call();
				git.tag().setName(tag).call();
				Files.write(file, getData(2).getBytes("UTF-8"), StandardOpenOption.TRUNCATE_EXISTING);
				git.add().addFilepattern(ACCEPT_STORAGE).call();
				git.commit().setMessage("Second").call();
				git.push().setCredentialsProvider(provider).call();
				git.push().setCredentialsProvider(provider).setPushTags().call();
			}
			sleep(3000);
			JsonNode result = client.target(String.format("%s/storage/" + ACCEPT_STORAGE + "?ref=refs/tags/" + tag, localAdress))
					.request().header(HttpHeader.AUTHORIZATION.asString(), basicAuth).get(JsonNode.class);
			
			StorageData value = MAPPER.readValue(getData(1), StorageData.class);
			assertEquals(value.getData(), result.get("data"));
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

	private static String buildBasicAuth(final String user, final String password) throws UnsupportedEncodingException {
		return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes("UTF-8"));
	}

	private Supplier<String> getRepo() {
		return () -> String.format("http://localhost:%d/application/%s/%s", remote.getLocalPort(),
				remote.getConfiguration().getHostedFactory().getServletName(),
				remote.getConfiguration().getHostedFactory().getHostedEndpoint());
	}

	private static StorageData readSource(final Path storage) throws IOException {
		assertTrue(Files.exists(storage));
		try (InputStream bc = Files.newInputStream(storage);) {
			return MAPPER.readValue(bc, StorageData.class);
		}
	}

	private static void writeSource(final StorageData data, final Path storage)
			throws JsonGenerationException, JsonMappingException, IOException {
		assertTrue(Files.exists(storage));
		MAPPER.writeValue(storage.toFile(), data);
	}

	private void sleep(final int millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException ignore) {
		}
	}

	private String getData(int i) {
		return "{\"data\":{\"key" + i
				+ "\":{\"data\":\"value1\",\"users\":[{\"captain\":\"america\",\"black\":\"widow\"}]},\"key3\":{\"data\":\"value3\",\"users\":[{\"tony\":\"stark\",\"spider\":\"man\"}]}},\"users\":[{\"password\":\""
				+ PASSWORD + "\",\"user\":\"" + USER + "\"}]}";
	}

}
