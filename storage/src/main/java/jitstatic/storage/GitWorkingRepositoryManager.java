package jitstatic.storage;

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


import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PullCommand;
import org.eclipse.jgit.api.PushCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import jitstatic.source.Source.Contact;

class GitWorkingRepositoryManager implements AutoCloseable {
	private static final Logger logger = LogManager.getLogger(GitWorkingRepositoryManager.class);

	static final String WORKING = "working";

	private final Repository workingRepository;
	private final Contact contacts;

	public GitWorkingRepositoryManager(final Path baseDirectory, final String localStorageFilePath,
			final Contact remoteContactInfo) {
		if (!Files.isDirectory(Objects.requireNonNull(baseDirectory)))
			throw new IllegalArgumentException(String.format("Path %s is not a directory", baseDirectory));
		if (!Files.isWritable(baseDirectory))
			throw new IllegalArgumentException(String.format("Path %s is not writeable", baseDirectory));

		try {
			this.workingRepository = setUpWorkingRepository(baseDirectory,
					Objects.requireNonNull(remoteContactInfo, "remoteContactInfo cannot be null"),
					Objects.requireNonNull(localStorageFilePath));
		} catch (IllegalStateException | GitAPIException | IOException e) {
			throw new RuntimeException(e);
		}
		this.contacts = remoteContactInfo;
	}

	private Repository setUpWorkingRepository(final Path repo, final Contact remote, final String localFilePath)
			throws IOException, IllegalStateException, GitAPIException {
		final Path workingRepo = repo.resolve(WORKING);
		final Path localStorage = workingRepo.resolve(localFilePath);
		final URIish bareRepoURL = new URIish(remote.repositoryURI().toURL());
		if (!Files.exists(workingRepo)) {
			Files.createDirectories(workingRepo); // TODO Fix attributes
			UsernamePasswordCredentialsProvider upcp = null;
			if (remote.getUserName() != null) {
				upcp = new UsernamePasswordCredentialsProvider(remote.getUserName(), remote.getPassword());
			}
			final CloneCommand clone = Git.cloneRepository().setURI(bareRepoURL.toString())
					.setDirectory(workingRepo.toFile());
			if (upcp != null) {
				clone.setCredentialsProvider(upcp);
			}
			final Git git = clone.call();
			if (!Files.exists(localStorage)) {
				logger.debug("Storage {} doesn't exist, creating", localStorage);
				Path parent = localStorage.getParent();
				if (parent != null) {
					Files.createDirectories(parent);
				}
				Files.write(localStorage, "{}".getBytes("UTF-8"));
				
				git.add().addFilepattern(localFilePath).call();
				git.commit().setMessage("Initializing commit").call();
				PushCommand push = git.push().setRemote(Constants.DEFAULT_REMOTE_NAME);
				if (upcp != null) {
					push.setCredentialsProvider(upcp);
				}
				push.call();
			}
			return git.getRepository();
		} else {
			Repository repository = new FileRepositoryBuilder().findGitDir(workingRepo.toFile()).readEnvironment()
					.setMustExist(true).build();
			if (!Files.exists(localStorage))
				throw new FileNotFoundException(localStorage.toString()
						+ ". Cannot boot without this storage file. Please create it in order to boot.");
			return repository;
		}
	}

	@Override
	public void close() {
		StorageUtils.closeSilently(workingRepository);
	}

	void refresh() throws GitAPIException {
		try (Git git = new Git(workingRepository)) {
			// TODO Fix this with a specific branch.
			final PullCommand pull = git.pull();
			if (contacts.getUserName() != null) {
				pull.setCredentialsProvider(
						new UsernamePasswordCredentialsProvider(contacts.getUserName(), contacts.getPassword()));
			}
			pull.call();
		}

	}

	public Path resolvePath(String fileStorage) {
		Path parent = workingRepository.getDirectory().toPath().getParent();
		if (parent != null) {
			Path resolved = parent.resolve(fileStorage);
			if (Files.exists(resolved))
				return resolved;
		}
		return null;
	}

}
