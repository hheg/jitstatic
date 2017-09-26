package jitstatic.remote;

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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LsRemoteCommand;
import org.eclipse.jgit.api.MergeResult.MergeStatus;
import org.eclipse.jgit.api.PullCommand;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.errors.CanceledException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidConfigurationException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.NoHeadException;
import org.eclipse.jgit.api.errors.RefNotAdvertisedException;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.api.errors.WrongRepositoryStateException;
import org.eclipse.jgit.errors.AmbiguousObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.RevisionSyntaxException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jitstatic.hosted.BranchNotFoundException;
import jitstatic.hosted.StorageChecker;
import jitstatic.hosted.StorageExtractor;
import jitstatic.source.Source.Contact;
import jitstatic.source.SourceEventListener;

class RemoteRepositoryManager implements Contact, AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(RemoteRepositoryManager.class);
	private final URI remoteRepo;
	private final List<SourceEventListener> listeners = new ArrayList<>();

	private final String userName;
	private final String password;

	private volatile String latestSHA = null;

	private final AtomicReference<Exception> faultRef = new AtomicReference<>();
	private final String storageFile;
	private final String branch;
	private final Repository repository;

	public RemoteRepositoryManager(final URI remoteRepo, final String userName, final String password,
			final String branch, final String storageFile, final Path baseDirectory) {
		this.remoteRepo = Objects.requireNonNull(remoteRepo, "remote endpoint cannot be null");
		Objects.requireNonNull(branch, "branch cannot be null");
		this.storageFile = Objects.requireNonNull(storageFile, "storageFile cannot be null");

		final String normalizedBranchName = normalizeBranchName(branch);

		if(!Repository.isValidRefName(normalizedBranchName)) {
			throw new IllegalArgumentException(branch + " is not an valid branch reference");
		}
		this.branch = normalizedBranchName;
		
		this.userName = userName;
		this.password = password == null ? "" : password;
		try {
			this.repository = setUpRepository(Objects.requireNonNull(baseDirectory));
		} catch (GitAPIException | IOException e) {
			throw new RuntimeException(e);
		}
		try {
			checkStorage();
		} catch (final RevisionSyntaxException | IOException e) {
			throw new RuntimeException(e);
		} catch(final BranchNotFoundException bnfe) {
			throw new BranchNotFoundException(branch);
		}
	}
	
	private String normalizeBranchName(String branch) {
		if(!branch.startsWith(Constants.R_HEADS)) {
			branch = Constants.R_HEADS + branch;
		}
		return Repository.normalizeBranchName(branch);
	}

	private void checkStorage()
			throws RevisionSyntaxException, AmbiguousObjectException, IncorrectObjectTypeException, IOException {
		try (StorageChecker sc = new StorageChecker(this.repository)) {
			sc.check(this.storageFile, this.branch);
		}
	}

	private Repository setUpRepository(final Path baseDirectory)
			throws InvalidRemoteException, TransportException, GitAPIException, IOException {
		final Repository r = getRepository(baseDirectory);
		if (r == null) {
			Files.createDirectories(baseDirectory);
			final CloneCommand cc = Git.cloneRepository().setDirectory(baseDirectory.toFile())
					.setURI(remoteRepo.toString());
			if (this.userName != null) {
				cc.setCredentialsProvider(new UsernamePasswordCredentialsProvider(this.userName, this.password));
			}
			return cc.call().getRepository();
		}
		return r;
	}

	private Repository getRepository(final Path baseDirectory) {
		try {
			return Git.open(baseDirectory.toFile()).getRepository();
		} catch (final IOException ignore) {
		}
		return null;
	}

	public void addListeners(SourceEventListener listener) {
		this.listeners.add(listener);
	}

	public Runnable checkRemote() {
		return () -> {
			try {

				final LsRemoteCommand lsRemoteRepository = Git.lsRemoteRepository();
				if (userName != null) {
					lsRemoteRepository
							.setCredentialsProvider(new UsernamePasswordCredentialsProvider(userName, password));
				}
				final Collection<Ref> refs = lsRemoteRepository.setHeads(true).setTags(true)
						.setRemote(this.remoteRepo.toString()).call();
				final Iterator<Ref> iterator = refs.iterator();
				boolean triggered = false;
				while (iterator.hasNext()) {
					final Ref next = iterator.next();
					if (branch.equals(next.getName())) {
						final String remoteSHA = next.getObjectId().getName();
						if (!remoteSHA.equals(getLatestSHA())) {
							pullChanges();
							setLatestSHA(remoteSHA);
							listeners.forEach(SourceEventListener::onEvent);
							triggered = true;
							break;
						}
					}
				}
				if (!triggered) {
					throw new RepositoryIsMissingIntendedBranch(
							"Repository doesn't have a " + branch + " branch");
				}
				setFault(null);
			} catch (final Exception e) {
				setFault(e);
			}
		};
	}

	private void pullChanges() throws GitAPIException, WrongRepositoryStateException, InvalidConfigurationException,
			InvalidRemoteException, CanceledException, RefNotFoundException, RefNotAdvertisedException, NoHeadException,
			TransportException {
		final Git git = Git.wrap(this.repository);
		final PullCommand pull = git.pull();
		if (this.userName != null) {
			pull.setCredentialsProvider(new UsernamePasswordCredentialsProvider(this.userName, this.password));
		}
		final PullResult pr = pull.call();

		if (!pr.isSuccessful()) {
			throw new RuntimeException("Pull failed because " + getError(pr));
		}
	}

	private MergeStatus getError(final PullResult pr) {
		return pr.getMergeResult().getMergeStatus();
	}

	public Exception getFault() {
		return faultRef.getAndSet(null);
	}

	private void setFault(final Exception fault) {
		final Exception old = faultRef.getAndSet(fault);
		if (old != null) {
			log.warn("Unregistered exception", old);
		}
	}

	@Override
	public URI repositoryURI() {
		return remoteRepo;
	}

	@Override
	public String getUserName() {
		return userName;
	}

	@Override
	public String getPassword() {
		return password;
	}

	public Contact getContact() {
		return this;
	}

	String getLatestSHA() {
		return latestSHA;
	}

	void setLatestSHA(String latestSHA) {
		this.latestSHA = latestSHA;
	}

	@Override
	public void close() {
		if (this.repository != null) {
			this.repository.close();
		}
	}

	public InputStream getStorageInputStream() throws RevisionSyntaxException, AmbiguousObjectException, IncorrectObjectTypeException, IOException {
		return StorageExtractor.sourceExtractor(repository,branch,storageFile);
	}
}
