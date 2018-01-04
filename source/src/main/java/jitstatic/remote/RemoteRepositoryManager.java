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
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.FetchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.errors.RevisionSyntaxException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.FetchResult;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceiveCommand.Result;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.TrackingRefUpdate;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jitstatic.CorruptedSourceException;
import jitstatic.JitStaticConstants;
import jitstatic.LinkedException;
import jitstatic.SourceChecker;
import jitstatic.SourceExtractor;
import jitstatic.hosted.FileObjectIdStore;
import jitstatic.source.SourceEventListener;
import jitstatic.util.Pair;

public class RemoteRepositoryManager implements AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(RemoteRepositoryManager.class);
	private final URI remoteRepo;
	private final List<SourceEventListener> listeners = new ArrayList<>();
	private final SourceExtractor extractor;
	private final String userName;
	private final String password;

	private final AtomicReference<Exception> faultRef = new AtomicReference<>();
	private final AtomicReference<Object> lock = new AtomicReference<Object>();
	private final Repository repository;
	private final String defaultRef;

	public RemoteRepositoryManager(final URI remoteRepo, final String userName, final String password,
			final Path baseDirectory, final String defaultRef) throws CorruptedSourceException, IOException {
		this.remoteRepo = Objects.requireNonNull(remoteRepo, "remote endpoint cannot be null");

		this.userName = userName;
		this.password = password == null ? "" : password;
		try {
			this.repository = setUpRepository(Objects.requireNonNull(baseDirectory));
		} catch (GitAPIException | IOException e) {
			throw new RuntimeException(e);
		}
		this.defaultRef = Objects.requireNonNull(defaultRef, "defaultBranch cannot be null");
		pollAndCheckRemote();
		final Exception exception = faultRef.get();
		if (exception != null) {
			throw new RuntimeException(exception);
		}
		checkStorage(defaultRef);
		this.extractor = new SourceExtractor(this.repository);
	}

	private void checkStorage(final String defaultRef) throws CorruptedSourceException, IOException {
		try (SourceChecker sc = new SourceChecker(this.repository)) {
			sc.checkIfDefaultBranchExists(defaultRef);
			final List<Pair<Set<Ref>, List<Pair<FileObjectIdStore, Exception>>>> checked = sc.check();
			if (!checked.isEmpty()) {
				throw new CorruptedSourceException(checked);
			}
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

	public void addListeners(final SourceEventListener listener) {
		this.listeners.add(listener);
	}

	public Runnable checkRemote() {
		return () -> {
			final Object obj = lock.getAndSet(new Object());
			if (obj == null) {
				pollAndCheckRemote();
			}
			lock.set(null);
		};
	}

	private void pollAndCheckRemote() {
		try {
			final Collection<TrackingRefUpdate> trackingRefUpdates = fetchRemote();

			long defaultrefs = trackingRefUpdates.stream().filter(tru -> defaultRef.equals(tru.getRemoteName()))
					.count();
			if (trackingRefUpdates.size() > 0 && defaultrefs != 1) {
				setFault(new RepositoryIsMissingIntendedBranch(defaultRef));
			} else {
				final ReceivePack receivePack = new ReceivePack(repository);

				final List<Pair<Pair<ReceiveCommand, ReceiveCommand>, Exception>> executedCommands = trackingRefUpdates
						.stream().map(tru -> extractCommands(receivePack, tru)).parallel().map(this::checkBranchSource)
						.sequential().collect(Collectors.toList());
				final LinkedException storageErrors = tryUpdate(receivePack, executedCommands);
				if (storageErrors.isEmpty()) {
					setFault(null);
				} else {
					setFault(storageErrors);
				}
			}
		} catch (final Exception e) {
			setFault(e);
		}
	}

	private Collection<TrackingRefUpdate> fetchRemote()
			throws GitAPIException, InvalidRemoteException, TransportException {
		final Git git = Git.wrap(repository);
		final FetchCommand fetch = git.fetch();
		if (userName != null) {
			fetch.setCredentialsProvider(new UsernamePasswordCredentialsProvider(userName, password));
		}
		final FetchResult fetchResult = fetch.call();

		final Collection<TrackingRefUpdate> trackingRefUpdates = fetchResult.getTrackingRefUpdates();
		return trackingRefUpdates;
	}

	private LinkedException tryUpdate(final ReceivePack rp,
			final List<Pair<Pair<ReceiveCommand, ReceiveCommand>, Exception>> executedCommands) {
		final LinkedException storageErrors = new LinkedException();
		try {
			final List<Exception> errors = executedCommands.stream().filter(p -> p.getRight() != null)
					.map(p -> p.getRight()).collect(Collectors.toList());
			if (errors.isEmpty()) {
				updateLocalData(executedCommands, storageErrors);
			} else {
				storageErrors.addAll(errors);
			}
		} finally {
			cleanRepository(rp, executedCommands, storageErrors);
		}
		return storageErrors;
	}

	private Pair<Pair<ReceiveCommand, ReceiveCommand>, Exception> extractCommands(final ReceivePack receivePack,
			final TrackingRefUpdate trackingRefUpdate) {
		final ReceiveCommand receiveCommand = trackingRefUpdate.asReceiveCommand();
		final String testBranchName = JitStaticConstants.REF_JISTSTATIC + UUID.randomUUID();

		try {
			final ReceiveCommand testCommand = new ReceiveCommand(receiveCommand.getOldId(), receiveCommand.getNewId(),
					testBranchName);
			if (trackingRefUpdate.getRemoteName().equals(defaultRef)
					&& ObjectId.zeroId().equals(trackingRefUpdate.getNewObjectId())) {
				return new Pair<>(new Pair<>(receiveCommand, null), new RepositoryIsMissingIntendedBranch(defaultRef));
			}
			createTmpBranch(repository, testBranchName, trackingRefUpdate); // Cheating here
			testCommand.execute(receivePack);
			return new Pair<Pair<ReceiveCommand, ReceiveCommand>, Exception>(new Pair<>(receiveCommand, testCommand),
					null);
		} catch (final IOException e) {
			return new Pair<Pair<ReceiveCommand, ReceiveCommand>, Exception>(new Pair<>(receiveCommand, null), e);
		}
	}

	private void cleanRepository(final ReceivePack receivePack,
			final List<Pair<Pair<ReceiveCommand, ReceiveCommand>, Exception>> executedCommands,
			final LinkedException storageErrors) {
		executedCommands.stream().forEach(pair -> {
			try {
				deleteTempBranch(receivePack, pair.getLeft().getRight(), repository);
			} catch (final IOException e) {
				storageErrors.add(e);
			}
		});
	}

	private void updateLocalData(final List<Pair<Pair<ReceiveCommand, ReceiveCommand>, Exception>> executedCommands,
			final LinkedException storageErrors) {
		final RemoteConfig remoteConfig = getRemoteConfig();
		final List<String> refsToBeUpdated = executedCommands.stream().map(pair -> {
			final ReceiveCommand orig = pair.getLeft().getLeft();
			final ReceiveCommand test = pair.getLeft().getRight();

			final String remoteRefName = orig.getRefName();

			final RefSpec spec = getRefSpec(remoteConfig.getFetchRefSpecs(), remoteRefName);
			try {
				final RefUpdate updateRef = repository.updateRef(spec.getDestination());
				updateRef.setForceUpdate(true);
				updateRef.setNewObjectId(test.getNewId());
				checkResult(spec.getDestination(), updateRef.forceUpdate());
				orig.setResult(test.getResult(), test.getMessage());
				return spec.getDestination();
			} catch (final IOException e) {
				storageErrors.add(e);
				return null;
			}
		}).filter(Objects::nonNull).collect(Collectors.toList());
		if (storageErrors.isEmpty()) {
			listeners.forEach(l -> l.onEvent(refsToBeUpdated));
		}
	}

	private Pair<Pair<ReceiveCommand, ReceiveCommand>, Exception> checkBranchSource(
			Pair<Pair<ReceiveCommand, ReceiveCommand>, Exception> commandResult) {
		if (commandResult.getRight() != null) {
			return commandResult;
		}
		final Pair<ReceiveCommand, ReceiveCommand> receiveCommands = commandResult.getLeft();
		final ReceiveCommand testReceiveCommand = receiveCommands.getRight();
		try (final SourceChecker sc = new SourceChecker(repository)) {
			final String testBranchName = testReceiveCommand.getRefName();
			final List<Pair<Set<Ref>, List<Pair<FileObjectIdStore, Exception>>>> checkTest = sc
					.checkTestBranchForErrors(testBranchName);
			// Only one branch so 1 element
			final Pair<Set<Ref>, List<Pair<FileObjectIdStore, Exception>>> refAndErrorPair = checkTest.get(0);
			final List<Pair<FileObjectIdStore, Exception>> refErrors = refAndErrorPair.getRight();
			if (!refErrors.isEmpty()) {
				final ReceiveCommand actualRecieveCommand = receiveCommands.getLeft();
				final Ref realRef = repository.findRef(actualRecieveCommand.getRefName());
				final Set<Ref> refSet = new HashSet<>();
				refSet.add(realRef);
				testReceiveCommand.setResult(Result.REJECTED_OTHER_REASON,
						"Error in branch " + actualRecieveCommand.getRefName());
				return new Pair<Pair<ReceiveCommand, ReceiveCommand>, Exception>(receiveCommands,
						new CorruptedSourceException(Arrays.asList(new Pair<>(refSet, refErrors))));
			}
		} catch (final RefNotFoundException | IOException e) {
			testReceiveCommand.setResult(Result.REJECTED_OTHER_REASON, e.getMessage());
			return new Pair<Pair<ReceiveCommand, ReceiveCommand>, Exception>(receiveCommands, e);
		}
		return new Pair<Pair<ReceiveCommand, ReceiveCommand>, Exception>(receiveCommands, null);
	}

	private RefSpec getRefSpec(final List<RefSpec> fetchRefSpecs, final String refName) {
		// TODO Manage this with wildcards
		for (final RefSpec refSpec : fetchRefSpecs) {
			if (refSpec.getSource().equals(refName)) {
				return refSpec;
			}
		}
		final RefSpec refSpec = new RefSpec();
		final String branch = refName.replaceAll(Constants.R_REMOTES + "origin/", "");
		return refSpec.setSourceDestination(refName, Constants.R_HEADS + branch);
	}

	private RemoteConfig getRemoteConfig() {
		try {
			return new RemoteConfig(repository.getConfig(), "origin");
		} catch (final URISyntaxException e) {
			throw new RevisionSyntaxException("");
		}
	}

	private void checkResult(final String testBranchName, final org.eclipse.jgit.lib.RefUpdate.Result result)
			throws IOException {
		switch (result) {
		case FAST_FORWARD:
		case FORCED:
		case NEW:
		case RENAMED:
		case NO_CHANGE:
			break;
		case IO_FAILURE:
		case LOCK_FAILURE:
		case NOT_ATTEMPTED:
		case REJECTED:
		case REJECTED_CURRENT_BRANCH:
		default:
			throw new IOException("Created branch " + testBranchName + " failed with " + result);
		}
	}

	private void createTmpBranch(final Repository repository, final String testBranchName, TrackingRefUpdate tru)
			throws IOException {
		final RefUpdate updateRef = repository.updateRef(testBranchName);
		updateRef.setExpectedOldObjectId(ObjectId.zeroId());
		updateRef.setNewObjectId(tru.getOldObjectId());
		updateRef.setForceUpdate(true);
		updateRef.disableRefLog();
		checkResult(testBranchName, updateRef.forceUpdate());
	}

	private void deleteTempBranch(final ReceivePack rp, final ReceiveCommand rc, final Repository repository)
			throws IOException {
		if (rc != null) {
			final RefUpdate ru = repository.updateRef(rc.getRefName());
			ru.disableRefLog();
			ru.setForceUpdate(true);
			checkResult(rc.getRefName(), ru.delete());
		}
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

	public URI repositoryURI() {
		return remoteRepo;
	}

	@Override
	public void close() {
		if (this.repository != null) {
			this.repository.close();
		}
	}

	public InputStream getStorageInputStream(final String key, String ref) throws RefNotFoundException {
		Objects.requireNonNull(key);
		Objects.requireNonNull(ref);
		try {
			if (ref.startsWith(Constants.R_HEADS)) {
				return extractor.openBranch(ref, key);
			} else if (ref.startsWith(Constants.R_TAGS)) {
				return extractor.openTag(ref, key);
			}			
		} catch (final Exception e) {
			throw new RuntimeException(e);
		}
		throw new RefNotFoundException(ref);
	}
}
