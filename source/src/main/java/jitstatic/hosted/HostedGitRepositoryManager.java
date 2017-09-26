package jitstatic.hosted;

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

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import javax.servlet.ServletRequestListener;
import javax.servlet.http.HttpServletRequest;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.ServiceMayNotContinueException;
import org.eclipse.jgit.transport.resolver.RepositoryResolver;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;
import org.eclipse.jgit.transport.resolver.ServiceNotEnabledException;

import jitstatic.source.Source;
import jitstatic.source.SourceEventListener;

class HostedGitRepositoryManager implements Source {
	@Deprecated
	static final String BARE = "bare";
	private final Repository bareRepository;
	private final String endPointName;
	private final GitRecievePackListener listener;
	private final String branch;
	private final String storageFile;

	public HostedGitRepositoryManager(final Path workingDirectory, final String endPointName, final String store,
			final String branch) {
		Objects.requireNonNull(workingDirectory);
		Objects.requireNonNull(endPointName);
		Objects.requireNonNull(store);
		Objects.requireNonNull(branch);

		if (endPointName.isEmpty()) {
			throw new IllegalArgumentException(String.format("Parameter endPointName cannot be empty"));
		}
		this.endPointName = endPointName;
		if (!Files.isDirectory(workingDirectory)) {
			throw new IllegalArgumentException(String.format("Path %s is not a directory", workingDirectory));
		}
		if (!Files.isWritable(workingDirectory)) {
			throw new IllegalArgumentException(String.format("Path %s is not writeable", workingDirectory));
		}
		if (store.isEmpty()) {
			throw new IllegalArgumentException("Storage name cannot be empty");
		}
		if (branch.isEmpty()) {
			throw new IllegalArgumentException("Branch name cannot be empty");
		}

		final String normalizedBranchName = normalizeBranchName(branch);

		if (!Repository.isValidRefName(normalizedBranchName)) {
			throw new IllegalArgumentException(branch + " is not an valid branch reference");
		}

		try {
			this.bareRepository = setUpBareRepository(workingDirectory, store, normalizedBranchName);
		} catch (final IllegalStateException | GitAPIException | IOException e) {
			throw new RuntimeException(e);
		} catch (final BranchNotFoundException bnfe) {
			// Transform to current scope.
			throw new BranchNotFoundException(branch);
		}
		this.branch = normalizedBranchName;
		this.storageFile = store;
		this.listener = new GitRecievePackListener();
	}

	private String normalizeBranchName(String branch) {
		if (!branch.startsWith(Constants.R_HEADS)) {
			branch = Constants.R_HEADS + branch;
		}
		return Repository.normalizeBranchName(branch);
	}

	private Repository setUpBareRepository(final Path repo, final String store, final String branch)
			throws IOException, IllegalStateException, GitAPIException {
		final Path bareRepo = repo.resolve(BARE); // TODO remove specific BARE directory
		if (!Files.exists(bareRepo)) {
			Files.createDirectories(bareRepo);
			final Repository repository = Git.init().setDirectory(bareRepo.toFile()).setBare(true).call()
					.getRepository();
			initializeRepo(repository, store, branch);
			return repository;
		} else {
			final Repository repository = new FileRepositoryBuilder().setGitDir(bareRepo.toFile()).readEnvironment()
					.findGitDir(bareRepo.toFile()).setMustExist(true).build();
			try (final StorageChecker sc = new StorageChecker(repository)) {
				sc.check(store, branch);
			}
			return repository;
		}
	}

	private void initializeRepo(final Repository repository, final String store, String branch) throws IOException {
		try (RevWalk rw = new RevWalk(repository)) {
			ObjectInserter oi = repository.newObjectInserter();
			final ObjectId blob = oi.insert(Constants.OBJ_BLOB, Constants.encode("{}"));

			final String[] path = store.trim().split("/");

			FileMode mode = FileMode.REGULAR_FILE;
			oi = repository.newObjectInserter();
			ObjectId id = blob;

			for (int i = path.length - 1; i >= 0; --i) {
				final TreeFormatter treeFormatter = new TreeFormatter();
				treeFormatter.append(path[i], mode, id);
				id = oi.insert(treeFormatter);
				mode = FileMode.TREE;
			}

			final CommitBuilder commitBuilder = new CommitBuilder();
			final PersonIdent person = new PersonIdent("JitStatic", "");
			commitBuilder.setAuthor(person);
			final String msg = "Initializing commit";
			commitBuilder.setMessage(msg);
			commitBuilder.setCommitter(person);
			commitBuilder.setTreeId(id);
			final ObjectId inserted = oi.insert(commitBuilder);
			oi.flush();

			rw.parseCommit(inserted);

			final RefUpdate ru = repository.updateRef(branch);

			ru.setNewObjectId(inserted);
			ru.setRefLogMessage(msg, false);
			ru.setExpectedOldObjectId(ObjectId.zeroId());
			ru.update(rw);
		}
	}

	@Override
	public void close() {
		try {
			this.bareRepository.close();
		} catch (final Exception ignore) {
		}
	}

	public URI repositoryURI() {
		return this.bareRepository.getDirectory().toURI();
	}

	public ServletRequestListener getRequestListener() {
		return this.listener;
	}

	public RepositoryResolver<HttpServletRequest> getRepositoryResolver() {
		return new RepositoryResolver<HttpServletRequest>() {
			@Override
			public Repository open(final HttpServletRequest req, final String name) throws RepositoryNotFoundException,
					ServiceNotAuthorizedException, ServiceNotEnabledException, ServiceMayNotContinueException {
				if (!endPointName.equals(name)) {
					throw new RepositoryNotFoundException(name);
				}
				bareRepository.incrementOpen();
				return bareRepository;
			}
		};
	}

	@Override
	public void addListener(SourceEventListener listener) {
		this.listener.addListener(listener);
	}

	@Override
	public Contact getContact() {
		return new Contact() {

			@Override
			public URI repositoryURI() {
				return bareRepository.getDirectory().toURI();
			}

			@Override
			public String getUserName() {
				return null;
			}

			@Override
			public String getPassword() {
				return null;
			}
		};
	}

	@Override
	public void start() {
		// noop
	}

	@Override
	public void checkHealth() {
		// noop for now
	}

	@Override
	public InputStream getSourceStream() {
		try {
			return StorageExtractor.sourceExtractor(bareRepository, branch, storageFile);
		} catch (final Exception e) {
			throw new RuntimeException(e);
		}
	}
}
