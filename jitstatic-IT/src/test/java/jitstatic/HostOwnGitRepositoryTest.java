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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.ws.rs.NotFoundException;
import javax.ws.rs.client.Client;
import javax.ws.rs.core.Response;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.AbortedByHookException;
import org.eclipse.jgit.api.errors.ConcurrentRefUpdateException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.NoHeadException;
import org.eclipse.jgit.api.errors.NoMessageException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.api.errors.UnmergedPathsException;
import org.eclipse.jgit.api.errors.WrongRepositoryStateException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
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
import com.fasterxml.jackson.core.JsonParser.Feature;
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
import jitstatic.hosted.HostedFactory;
import jitstatic.storage.StorageData;

public class HostOwnGitRepositoryTest {

	private static final String REFS_HEADS_NEWBRANCH = "refs/heads/newbranch";
	private static final String REFS_HEADS_MASTER = "refs/heads/master";
	private static final String USER = "suser";
	private static final String PASSWORD = "ssecret";

	private static final TemporaryFolder TMP_FOLDER = new TemporaryFolder();
	private static final HttpClientConfiguration HCC = new HttpClientConfiguration();
	private static final ObjectMapper MAPPER = new ObjectMapper().enable(Feature.ALLOW_COMMENTS);

	private final DropwizardAppRule<JitstaticConfiguration> DW;

	@Rule
	public final RuleChain chain = RuleChain.outerRule(TMP_FOLDER)
			.around((DW = new DropwizardAppRule<>(JitstaticApplication.class,
					ResourceHelpers.resourceFilePath("simpleserver.yaml"),
					ConfigOverride.config("hosted.basePath", getFolder()))));
	@Rule
	public ExpectedException ex = ExpectedException.none();

	private UsernamePasswordCredentialsProvider provider;
	private String basic;

	private String gitAdress;
	private String storageAdress;

	@Before
	public void setupClass() throws UnsupportedEncodingException {
		final HostedFactory hf = DW.getConfiguration().getHostedFactory();
		provider = new UsernamePasswordCredentialsProvider(hf.getUserName(), hf.getSecret());
		basic = getBasicAuth();
		int localPort = DW.getLocalPort();
		gitAdress = String.format("http://localhost:%d/application/%s/%s", localPort, hf.getServletName(),
				hf.getHostedEndpoint());
		storageAdress = String.format("http://localhost:%d/application", localPort);
		HCC.setConnectionRequestTimeout(Duration.minutes(1));
		HCC.setConnectionTimeout(Duration.minutes(1));
		HCC.setTimeout(Duration.minutes(1));
	}

	@After
	public void after() {
		SortedMap<String, Result> healthChecks = DW.getEnvironment().healthChecks().runHealthChecks();
		List<Throwable> errors = healthChecks.entrySet().stream().map(e -> e.getValue().getError())
				.filter(Objects::nonNull).collect(Collectors.toList());
		errors.stream().forEach(e -> e.printStackTrace());
		assertThat(errors.toString(), errors.isEmpty(), Matchers.is(true));
	}

	@Test
	public void testCloneOwnHostedRepository() throws Exception {
		try (Git git = Git.cloneRepository().setDirectory(TMP_FOLDER.newFolder()).setCredentialsProvider(provider)
				.setURI(gitAdress).call()) {
			String localFilePath = getLocalFilePath();
			Path path = Paths.get(getRepopath(git), localFilePath);
			Files.createDirectories(path.getParent());
			Files.write(path, getData().getBytes("UTF-8"), StandardOpenOption.CREATE_NEW);
			git.add().addFilepattern(localFilePath).call();
			git.commit().setMessage("Inital commit").call();
			git.push().setCredentialsProvider(provider).call();
			assertTrue(Files.exists(path));
		}
		try (Git git = Git.cloneRepository().setDirectory(TMP_FOLDER.newFolder()).setCredentialsProvider(provider)
				.setURI(gitAdress).call()) {
			String localFilePath = getLocalFilePath();
			Path path = Paths.get(getRepopath(git), localFilePath);
			assertTrue(Files.exists(path));
		}
	}

	@Test
	public void testGetFromAnEmptyStorage() {
		ex.expect(NotFoundException.class);
		Client client = buildClient("test10 client");
		try {
			client.target(String.format("%s/storage/key1", storageAdress)).request()
					.header(HttpHeader.AUTHORIZATION.asString(), basic).get(JsonNode.class);
		} finally {
			client.close();
		}
	}

	@Test
	public void testPushToOwnHostedRepositoryAndFetchResultFromKeyValuestorage() throws Exception {
		try (Git git = Git.cloneRepository().setDirectory(TMP_FOLDER.newFolder()).setURI(gitAdress)
				.setCredentialsProvider(provider).call()) {
			String localFilePath = getLocalFilePath();
			Path path = Paths.get(getRepopath(git), localFilePath);
			Files.createDirectories(path.getParent());
			Files.write(path, getData().getBytes("UTF-8"), StandardOpenOption.CREATE);
			git.add().addFilepattern(localFilePath).call();
			git.commit().setMessage("Inital commit").call();

			Iterable<PushResult> call = git.push().setCredentialsProvider(provider).call();
			PushResult pr = call.iterator().next();
			RemoteRefUpdate remoteUpdate = pr.getRemoteUpdate(REFS_HEADS_MASTER);
			assertEquals(Status.OK, remoteUpdate.getStatus());
		}

		Client client = buildClient("test4 client");
		try {
			JsonNode response = client.target(String.format("%s/storage/" + getLocalFilePath(), storageAdress))
					.request().header(HttpHeader.AUTHORIZATION.asString(), basic).get(JsonNode.class);
			assertEquals(MAPPER.readValue(getData(), StorageData.class).getData(), response);
		} finally {
			client.close();
		}
	}

	@Test
	public void testPushToOwnHostedRepositoryAndFetchResultFromDoubleKeyValuestorage() throws Exception {
		String localFilePath = "key/key1";
		try (Git git = Git.cloneRepository().setDirectory(TMP_FOLDER.newFolder()).setURI(gitAdress)
				.setCredentialsProvider(provider).call()) {

			Path path = Paths.get(getRepopath(git), localFilePath);
			Files.createDirectories(path.getParent());
			Files.write(path, getData().getBytes("UTF-8"), StandardOpenOption.CREATE);

			git.add().addFilepattern(localFilePath).call();
			git.commit().setMessage("Test commit").call();
			Iterable<PushResult> call = git.push().setCredentialsProvider(provider).call();
			PushResult pr = call.iterator().next();
			RemoteRefUpdate remoteUpdate = pr.getRemoteUpdate(REFS_HEADS_MASTER);
			assertEquals(Status.OK, remoteUpdate.getStatus());
		}

		Client client = buildClient("test4 client");
		try {
			JsonNode response = client.target(String.format("%s/storage/" + localFilePath, storageAdress)).request()
					.header(HttpHeader.AUTHORIZATION.asString(), basic).get(JsonNode.class);
			assertEquals(MAPPER.readValue(getData(), StorageData.class).getData(), response);
		} finally {
			client.close();
		}
	}

	@Test
	public void testPushToOwnHostedRepositoryWithBrokenJSONStorage() throws Exception {
		String originalSHA = null;
		try (Git git = Git.cloneRepository().setDirectory(TMP_FOLDER.newFolder()).setURI(gitAdress)
				.setCredentialsProvider(provider).call()) {
			String localFilePath = getLocalFilePath();
			Path path = Paths.get(getRepopath(git), localFilePath);
			Files.createDirectories(path.getParent());
			Files.write(path, getData().getBytes("UTF-8"), StandardOpenOption.CREATE);
			git.add().addFilepattern(localFilePath).call();
			git.commit().setMessage("Inital commit").call();
			git.push().setCredentialsProvider(provider).call();

			originalSHA = git.getRepository().resolve(Constants.MASTER).getName();

			assertTrue(Files.exists(path));

			Files.write(path, "{".getBytes("UTF-8"), StandardOpenOption.TRUNCATE_EXISTING);

			git.add().addFilepattern(localFilePath).call();
			git.commit().setMessage("Test commit").call();
			Iterable<PushResult> push = git.push().setCredentialsProvider(provider).call();
			PushResult pushResult = push.iterator().next();
			RemoteRefUpdate remoteUpdate = pushResult.getRemoteUpdate(REFS_HEADS_MASTER);
			assertEquals(Status.REJECTED_OTHER_REASON, remoteUpdate.getStatus());
			assertTrue("Was '" + remoteUpdate.getMessage() + "'",
					remoteUpdate.getMessage().startsWith("Error in branch " + REFS_HEADS_MASTER));

		}
		try (Git git = Git.cloneRepository().setDirectory(TMP_FOLDER.newFolder()).setURI(gitAdress)
				.setCredentialsProvider(provider).call()) {
			assertNotNull(readSource(Paths.get(getRepopath(git), getLocalFilePath())));
			assertEquals(originalSHA, git.getRepository().resolve(Constants.MASTER).getName());
		}
	}

	@Test
	public void testPushToOwnHostedRepositoryWithNewBranch()
			throws InvalidRemoteException, TransportException, GitAPIException, IOException {
		try (Git git = Git.cloneRepository().setDirectory(TMP_FOLDER.newFolder()).setURI(gitAdress)
				.setCredentialsProvider(provider).call()) {
			String localFilePath = getLocalFilePath();
			Path path = Paths.get(getRepopath(git), localFilePath);
			Files.createDirectories(path.getParent());
			Files.write(path, getData().getBytes("UTF-8"), StandardOpenOption.CREATE);
			git.add().addFilepattern(localFilePath).call();
			git.commit().setMessage("Inital commit").call();
			git.push().setCredentialsProvider(provider).call();

			git.checkout().setCreateBranch(true).setName("newbranch").call();
			Files.write(path, getData(1).getBytes("UTF-8"), StandardOpenOption.CREATE);
			git.add().addFilepattern(localFilePath).call();
			git.commit().setMessage("Newer commit").call();
			git.push().setCredentialsProvider(provider).call();

			Map<String, Ref> refs = git.lsRemote().setCredentialsProvider(provider).callAsMap();
			assertNotNull(refs.get(REFS_HEADS_NEWBRANCH));

		}
	}

	@Test
	public void testPushToOwnHostedRepositoryWithNewBranchAndThenDelete()
			throws InvalidRemoteException, TransportException, GitAPIException, IOException {
		try (Git git = Git.cloneRepository().setDirectory(TMP_FOLDER.newFolder()).setURI(gitAdress)
				.setCredentialsProvider(provider).call()) {
			String localFilePath = getLocalFilePath();
			Path path = Paths.get(getRepopath(git), localFilePath);
			Files.createDirectories(path.getParent());
			Files.write(path, getData().getBytes("UTF-8"), StandardOpenOption.CREATE);
			git.add().addFilepattern(localFilePath).call();
			git.commit().setMessage("Inital commit").call();
			git.push().setCredentialsProvider(provider).call();

			git.checkout().setCreateBranch(true).setName("newbranch").call();
			Files.write(path, getData(1).getBytes("UTF-8"), StandardOpenOption.CREATE);
			git.add().addFilepattern(localFilePath).call();
			git.commit().setMessage("Newer commit").call();
			git.push().setCredentialsProvider(provider).call();

			Map<String, Ref> refs = git.lsRemote().setCredentialsProvider(provider).callAsMap();
			assertNotNull(refs.get(REFS_HEADS_NEWBRANCH));

			git.checkout().setName("master").call();
			List<String> call = git.branchDelete().setBranchNames("newbranch").setForce(true).call();
			assertTrue(call.stream().allMatch(b -> b.equals(REFS_HEADS_NEWBRANCH)));

			git.push().setCredentialsProvider(provider).setRefSpecs(new RefSpec(":" + REFS_HEADS_NEWBRANCH)).call();
			refs = git.lsRemote().setCredentialsProvider(provider).callAsMap();
			assertNull(refs.get(REFS_HEADS_NEWBRANCH));
		}
		
		Client client = buildClient("test4 client");
		try {
			JsonNode response = client
					.target(String.format("%s/storage/" + getLocalFilePath() + "?ref=newbranch", storageAdress)).request()
					.header(HttpHeader.AUTHORIZATION.asString(), basic).get(JsonNode.class);
			assertEquals(MAPPER.readValue(getData(), StorageData.class).getData(), response);
		} finally {
			client.close();
		}
	}
	
	@Test
	public void testRemoveKey() throws InvalidRemoteException, TransportException, GitAPIException, IOException {
		Client client = buildClient("test4 client");
		try (Git git = Git.cloneRepository().setDirectory(TMP_FOLDER.newFolder()).setURI(gitAdress)
				.setCredentialsProvider(provider).call()) {
			String localFilePath = getLocalFilePath();
			Path path = Paths.get(getRepopath(git), localFilePath);
			Files.createDirectories(path.getParent());
			Files.write(path, getData().getBytes("UTF-8"), StandardOpenOption.CREATE);
			git.add().addFilepattern(localFilePath).call();
			git.commit().setMessage("Inital commit").call();
			git.push().setCredentialsProvider(provider).call();
			
			JsonNode response = client
					.target(String.format("%s/storage/" + getLocalFilePath(), storageAdress)).request()
					.header(HttpHeader.AUTHORIZATION.asString(), basic).get(JsonNode.class);
			assertEquals(MAPPER.readValue(getData(), StorageData.class).getData(), response);
			
			git.rm().addFilepattern(localFilePath).call();
			git.commit().setMessage("Removed file").call();
			git.push().setCredentialsProvider(provider).call();
			
			Response resp = client
					.target(String.format("%s/storage/" + getLocalFilePath(), storageAdress)).request()
					.header(HttpHeader.AUTHORIZATION.asString(), basic).get();
			assertEquals(javax.ws.rs.core.Response.Status.NOT_FOUND.getStatusCode(), resp.getStatus());
		} finally {
			client.close();
		}		
	}

	@Test
	public void testGetTag()
			throws NoHeadException, NoMessageException, UnmergedPathsException, ConcurrentRefUpdateException,
			WrongRepositoryStateException, AbortedByHookException, GitAPIException, IOException {
		try (Git git = Git.cloneRepository().setDirectory(TMP_FOLDER.newFolder()).setURI(gitAdress)
				.setCredentialsProvider(provider).call()) {
			String localFilePath = getLocalFilePath();
			Path path = Paths.get(getRepopath(git), localFilePath);
			Files.createDirectories(path.getParent());
			Files.write(path, getData().getBytes("UTF-8"), StandardOpenOption.CREATE);
			git.add().addFilepattern(localFilePath).call();
			git.commit().setMessage("Inital commit").call();
			git.tag().setName("tag").call();
			Iterable<PushResult> call = git.push().setCredentialsProvider(provider).call();
			PushResult pr = call.iterator().next();
			RemoteRefUpdate remoteUpdate = pr.getRemoteUpdate(REFS_HEADS_MASTER);
			assertEquals(Status.OK, remoteUpdate.getStatus());
			git.push().setCredentialsProvider(provider).setPushTags().call();
		}

		Client client = buildClient("test4 client");
		try {
			JsonNode response = client
					.target(String.format("%s/storage/" + getLocalFilePath() + "?ref=tag", storageAdress)).request()
					.header(HttpHeader.AUTHORIZATION.asString(), basic).get(JsonNode.class);
			assertEquals(MAPPER.readValue(getData(), StorageData.class).getData(), response);
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

	private static String getBasicAuth() throws UnsupportedEncodingException {
		return "Basic " + Base64.getEncoder().encodeToString((USER + ":" + PASSWORD).getBytes("UTF-8"));
	}

	private StorageData readSource(final Path storage) throws IOException {
		assertTrue(Files.exists(storage));
		try (InputStream bc = Files.newInputStream(storage);) {
			return MAPPER.readValue(bc, StorageData.class);
		}
	}

	private Client buildClient(final String name) {
		Environment environment = DW.getEnvironment();
		JerseyClientBuilder jerseyClientBuilder = new JerseyClientBuilder(environment);
		jerseyClientBuilder.setApacheHttpClientBuilder(new HttpClientBuilder(environment).using(HCC));
		return jerseyClientBuilder.build(name);
	}

	private static String getRepopath(Git git) {
		return git.getRepository().getDirectory().getParentFile().getAbsolutePath();
	}

	@Deprecated
	private String getLocalFilePath() {
		return "store";
	}

	private String getData() {
		return getData(1);
	}

	private String getData(int i) {
		return "{\"data\":{\"key" + i
				+ "\":{\"data\":\"value1\",\"users\":[{\"captain\":\"america\",\"black\":\"widow\"}]},\"key3\":{\"data\":\"value3\",\"users\":[{\"tony\":\"stark\",\"spider\":\"man\"}]}},\"users\":[{\"password\":\""
				+ PASSWORD + "\",\"user\":\"" + USER + "\"}]}";
	}

}
