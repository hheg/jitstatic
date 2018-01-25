package jitstatic.tools;

import static org.junit.Assert.assertEquals;

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




import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.function.Supplier;

import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PushCommand;
import org.eclipse.jgit.api.RemoteAddCommand;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.transport.RemoteRefUpdate.Status;
import org.junit.rules.ExternalResource;

public class TestClientRepositoryRule extends ExternalResource {

	private static final String METADATA = ".metadata";
	private final Supplier<String> baseSupplier;
	private final String[] filesToCommit;

	private Supplier<String> userName;
	private Supplier<String> password;
	private Supplier<String> remote;

	public TestClientRepositoryRule(final Supplier<String> base, final Supplier<String> userName,
			final Supplier<String> password, final Supplier<String> remote, final String... string) {
		this.baseSupplier = Objects.requireNonNull(base);
		this.filesToCommit = Objects.requireNonNull(string);
		this.userName = Objects.requireNonNull(userName);
		this.password = Objects.requireNonNull(password);
		this.remote = Objects.requireNonNull(remote);
	}

	@Override
	protected void before() throws Throwable {
		final Path base = Paths.get(baseSupplier.get());
		CloneCommand cloneCommand = Git.cloneRepository().setDirectory(base.toFile()).setURI(remote.get());
		UsernamePasswordCredentialsProvider upcp = null;
		if (userName.get() != null) {
			upcp = new UsernamePasswordCredentialsProvider(userName.get(), password.get());
			cloneCommand.setCredentialsProvider(upcp);
		}

		try (Git git = cloneCommand.call()) {
			for (String file : filesToCommit) {
				final Path filePath = base.resolve(file);
				final Path mfilePath = base.resolve(file+METADATA);
				Files.createDirectories(Objects.requireNonNull(filePath.getParent()));
				try (InputStream is = getClass().getResourceAsStream("/" + file)) {
					Files.copy(is, filePath, StandardCopyOption.REPLACE_EXISTING);
				}
				try (InputStream is = getClass().getResourceAsStream("/" + file + METADATA)) {
					Files.copy(is, mfilePath, StandardCopyOption.REPLACE_EXISTING);
				}
			}
			git.add().addFilepattern(".").call();
			git.commit().setMessage("Initial commit").call();
			final RemoteAddCommand newRemote = git.remoteAdd();
			newRemote.setName(Constants.DEFAULT_REMOTE_NAME);
			newRemote.setUri(new URIish(remote.get()));
			newRemote.call();
			PushCommand push = git.push();
			if (upcp != null) {
				push.setCredentialsProvider(upcp);
			}
			Iterable<PushResult> call = push.call();
			PushResult pr = call.iterator().next();
			RemoteRefUpdate remoteUpdate = pr.getRemoteUpdate("refs/heads/master");
			assertEquals(Status.OK, remoteUpdate.getStatus());
		}
	}
}
