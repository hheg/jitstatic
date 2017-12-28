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

import static org.junit.Assert.assertThat;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;
import java.util.SortedMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.RemoteAddCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.TemporaryFolder;

import com.codahale.metrics.health.HealthCheck.Result;

import io.dropwizard.testing.ConfigOverride;
import io.dropwizard.testing.ResourceHelpers;
import io.dropwizard.testing.junit.DropwizardAppRule;
import jitstatic.hosted.HostedFactory;

public class HostOwnGitRepositoryUninitialized {

	private static final String USER = "suser";
	private static final String PASSWORD = "ssecret";

	private static final TemporaryFolder TMP_FOLDER = new TemporaryFolder();
	
	private final DropwizardAppRule<JitstaticConfiguration> DW;
	private UsernamePasswordCredentialsProvider provider;
	@Rule
	public final RuleChain chain = RuleChain.outerRule(TMP_FOLDER)
			.around((DW = new DropwizardAppRule<>(JitstaticApplication.class,
					ResourceHelpers.resourceFilePath("simpleserver.yaml"),
					ConfigOverride.config("hosted.basePath", getFolder()))));

	@Before
	public void setup() {
		final HostedFactory hf = DW.getConfiguration().getHostedFactory();
		provider = new UsernamePasswordCredentialsProvider(hf.getUserName(), hf.getSecret());
	}
	
	@After
	public void tearDown() {
		SortedMap<String, Result> healthChecks = DW.getEnvironment().healthChecks().runHealthChecks();
		List<Throwable> errors = healthChecks.entrySet().stream().map(e -> e.getValue().getError())
				.filter(Objects::nonNull).collect(Collectors.toList());
		errors.stream().forEach(e -> e.printStackTrace());
		assertThat(errors.toString(), errors.isEmpty(), Matchers.is(true));
	}
	
	@Test
	public void testPushToUninitialized()
			throws IOException, IllegalStateException, GitAPIException, URISyntaxException {
		final File workingFolder = TMP_FOLDER.newFolder();
		try (Git git = Git.init().setDirectory(workingFolder).call()) {
			Files.write(workingFolder.toPath().resolve("file"), getData().getBytes("UTF-8"), StandardOpenOption.CREATE);
			git.add().addFilepattern("file").call();
			git.commit().setMessage("Init").call();
			String remote = "http://localhost:" + DW.getLocalPort() + "/application/selfhosted/git";

			RemoteAddCommand addRemote = git.remoteAdd();
			addRemote.setName("origin");
			addRemote.setUri(new URIish(remote));
			addRemote.call();
			StoredConfig config = git.getRepository().getConfig();
			config.setString(ConfigConstants.CONFIG_BRANCH_SECTION, "master", ConfigConstants.CONFIG_KEY_REMOTE,
					"origin");
			config.setString(ConfigConstants.CONFIG_BRANCH_SECTION, "master", ConfigConstants.CONFIG_KEY_MERGE,
					Constants.R_HEADS + "master");
			git.push().setCredentialsProvider(provider).call();
		}
	}

	private String getData() {
		return getData(1);
	}

	private String getData(int i) {
		return "{\"data\":{\"key" + i
				+ "\":{\"data\":\"value1\",\"users\":[{\"captain\":\"america\",\"black\":\"widow\"}]},\"key3\":{\"data\":\"value3\",\"users\":[{\"tony\":\"stark\",\"spider\":\"man\"}]}},\"users\":[{\"password\":\""
				+ PASSWORD + "\",\"user\":\"" + USER + "\"}]}";
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
}
