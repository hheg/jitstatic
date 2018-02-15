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
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.eclipse.jgit.api.CreateBranchCommand.SetupUpstreamMode;
import org.eclipse.jgit.api.FetchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand.ListMode;
import org.eclipse.jgit.api.LsRemoteCommand;
import org.eclipse.jgit.api.errors.CheckoutConflictException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRefNameException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.RefAlreadyExistsException;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.errors.RevisionSyntaxException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.FetchResult;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceiveCommand.Result;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.TrackingRefUpdate;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

import jitstatic.CorruptedSourceException;
import jitstatic.FileObjectIdStore;
import jitstatic.JitStaticConstants;
import jitstatic.RepositoryIsMissingIntendedBranch;
import jitstatic.SourceChecker;
import jitstatic.SourceExtractor;
import jitstatic.SourceUpdater;
import jitstatic.source.SourceEventListener;
import jitstatic.source.SourceInfo;
import jitstatic.utils.ErrorConsumingThreadFactory;
import jitstatic.utils.LinkedException;
import jitstatic.utils.Pair;
import jitstatic.utils.VersionIsNotSameException;
import jitstatic.utils.WrappingAPIException;

public class RemoteRepositoryManager implements AutoCloseable {

	private static final ObjectWriter MAPPER = new ObjectMapper().writerFor(JsonNode.class).withDefaultPrettyPrinter();
	private static final Logger LOG = LoggerFactory.getLogger(RemoteRepositoryManager.class);
	private static final String REMOTE_PREFIX = Constants.R_REMOTES + Constants.DEFAULT_REMOTE_NAME + "/";
	private final URI remoteRepo;
	private final List<SourceEventListener> listeners = new ArrayList<>();
	private final SourceExtractor extractor;
	private final String userName;
	private final String password;

	private final AtomicReference<Exception> faultRef = new AtomicReference<>();
	private final AtomicReference<Object> lock = new AtomicReference<>();
	private final Repository repository;
	private final String defaultRef;
	private final SourceUpdater updater;
	private final ExecutorService repoExecutor;

	public RemoteRepositoryManager(final URI remoteRepo, final String userName, final String password, final Path baseDirectory,
			final String defaultRef) throws CorruptedSourceException, IOException {
		this.remoteRepo = Objects.requireNonNull(remoteRepo, "remote endpoint cannot be null");

		this.userName = userName;
		this.password = password == null ? "" : password;

		try {
			this.repository = setUpRepository(Objects.requireNonNull(baseDirectory));
		} catch (final GitAPIException | IOException e) {
			throw new RuntimeException(e);
		}
		this.defaultRef = Objects.requireNonNull(defaultRef, "defaultRef cannot be null");
		pollAndCheckRemote();
		final Exception exception = faultRef.get();
		if (exception != null) {
			throw new RuntimeException(exception);
		}
		checkStorage(defaultRef);
		this.extractor = new SourceExtractor(this.repository);
		this.updater = new SourceUpdater(this.repository);
		this.repoExecutor = Executors.newSingleThreadExecutor(new ErrorConsumingThreadFactory("repo", this::setFault));
	}

	private void checkStorage(final String defaultRef) throws CorruptedSourceException, IOException {
		try (final SourceChecker sc = new SourceChecker(this.repository)) {
			final List<Pair<Set<Ref>, List<Pair<FileObjectIdStore, Exception>>>> checked = sc.check();
			if (!checked.isEmpty()) {
				throw new CorruptedSourceException(checked);
			}
		}
	}

	private Repository setUpRepository(final Path baseDirectory)
			throws InvalidRemoteException, TransportException, GitAPIException, IOException {
		LOG.info("Mounting repository on " + baseDirectory);
		Repository r = getRepository(baseDirectory);
		if (r == null) {
			Files.createDirectories(baseDirectory);
			final Git git = Git.cloneRepository().setDirectory(baseDirectory.toFile()).setURI(remoteRepo.toString())
					.setCredentialsProvider(getRemoteCredentials()).call();
			r = git.getRepository();
			final List<Ref> remoteRefs = git.branchList().setListMode(ListMode.REMOTE).call();
			if (remoteRefs.isEmpty()) {
				throw new RefNotFoundException("Remote contains no branches. Need at least " + defaultRef);
			}
			for (Ref ref : remoteRefs) {
				final String localRefName = getRefName(ref.getName());
				if (!Constants.MASTER.equals(localRefName)) {
					git.checkout().setName(localRefName).setCreateBranch(true).setUpstreamMode(SetupUpstreamMode.TRACK).call();
				}
			}
		}
		r.incrementOpen();
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
				try {
					LOG.info("Checking remote " + remoteRepo);
					repoExecutor.submit(this::pollAndCheckRemote).get();
				} catch (final InterruptedException | ExecutionException e) {
					setFault(e);
				} finally {
					LOG.info("Remote checked");
					lock.set(null);
				}
			}
		};
	}

	private void pollAndCheckRemote() {
		try {
			final Collection<String> deletedBranches = checkRemoteForNewAndDeletedBranches();
			final Collection<TrackingRefUpdate> trackingRefUpdates = fetchRemote();

			if (deletedBranches.contains(defaultRef)) {
				setFault(new RepositoryIsMissingIntendedBranch(defaultRef));
			} else {
				final ReceivePack receivePack = new ReceivePack(repository);

				final List<Pair<Pair<ReceiveCommand, ReceiveCommand>, Exception>> executedCommands = trackingRefUpdates.stream()
						.map(tru -> extractCommands(receivePack, tru)).parallel().map(this::checkBranchSource)
						.collect(Collectors.toList());
				final LinkedException storageErrors = tryUpdate(executedCommands, deletedBranches);
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

	private void checkForNewBranches(final Set<String> remoteRefs) throws IOException, RefAlreadyExistsException, RefNotFoundException,
			InvalidRefNameException, CheckoutConflictException, GitAPIException {
		final Git git = Git.wrap(repository);
		for (final String remoteRef : remoteRefs) {
			final Ref localRef = repository.findRef(remoteRef);
			if (localRef == null) {
				git.checkout().setName(Repository.shortenRefName(remoteRef)).setCreateBranch(true).setUpstreamMode(SetupUpstreamMode.TRACK)
						.call();
			}
		}
		if (remoteRefs.size() > 0) {
			git.checkout().setName(defaultRef).call();
		}
	}

	private Collection<String> checkRemoteForNewAndDeletedBranches()
			throws IOException, InvalidRemoteException, TransportException, GitAPIException {
		final Git git = Git.wrap(repository);
		final LsRemoteCommand lsRemote = git.lsRemote();
		lsRemote.setCredentialsProvider(getRemoteCredentials());

		final Set<String> remoteRefs = lsRemote.callAsMap().entrySet().stream()
				.filter(e -> e.getValue().getName().startsWith(Constants.R_HEADS)).map(Map.Entry::getKey).collect(Collectors.toSet());
		checkForNewBranches(remoteRefs);
		return checkForDeletedBranches(remoteRefs);
	}

	private Collection<String> checkForDeletedBranches(Set<String> remoteRefs) throws IOException {
		final Set<String> localRefs = repository.getRefDatabase().getRefs(Constants.R_HEADS).values().stream().map(r -> r.getName())
				.collect(Collectors.toSet());
		localRefs.removeAll(remoteRefs);
		return localRefs;
	}

	private UsernamePasswordCredentialsProvider getRemoteCredentials() {
		if (userName == null) {
			return null;
		}
		return new UsernamePasswordCredentialsProvider(userName, password);
	}

	private Collection<TrackingRefUpdate> fetchRemote() throws GitAPIException, InvalidRemoteException, TransportException, IOException {
		final Git git = Git.wrap(repository);
		final FetchCommand fetch = git.fetch();
		fetch.setCredentialsProvider(getRemoteCredentials());
		final FetchResult fetchResult = fetch.call();
		return fetchResult.getTrackingRefUpdates();
	}

	private String getRefName(final String key) {
		if (key.startsWith(REMOTE_PREFIX)) {
			return key.substring(REMOTE_PREFIX.length(), key.length());
		}
		if (key.startsWith(Constants.R_TAGS)) {
			return key.substring(Constants.R_TAGS.length(), key.length());
		}
		throw new RuntimeException(new RefNotFoundException(key));
	}

	private LinkedException tryUpdate(final List<Pair<Pair<ReceiveCommand, ReceiveCommand>, Exception>> executedCommands,
			final Collection<String> deletedBranches) {
		final LinkedException storageErrors = new LinkedException();
		try {
			final List<Exception> errors = executedCommands.stream().filter(p -> p.getRight() != null).map(p -> p.getRight())
					.collect(Collectors.toList());
			if (errors.isEmpty()) {
				updateLocalData(executedCommands, storageErrors, deletedBranches);
			} else {
				storageErrors.addAll(errors);
			}
		} finally {
			cleanRepository(executedCommands, storageErrors);
		}
		return storageErrors;
	}

	private Pair<Pair<ReceiveCommand, ReceiveCommand>, Exception> extractCommands(final ReceivePack receivePack,
			final TrackingRefUpdate trackingRefUpdate) {
		final ReceiveCommand receiveCommand = trackingRefUpdate.asReceiveCommand();
		final String testBranchName = JitStaticConstants.REFS_JISTSTATIC + UUID.randomUUID();

		try {
			final ReceiveCommand testCommand = new ReceiveCommand(receiveCommand.getOldId(), receiveCommand.getNewId(), testBranchName);
			createTmpBranch(repository, testBranchName, trackingRefUpdate); // Cheating here
			testCommand.execute(receivePack);
			return Pair.of(Pair.of(receiveCommand, testCommand), null);
		} catch (final IOException e) {
			return Pair.of(Pair.of(receiveCommand, null), e);
		}
	}

	private void cleanRepository(final List<Pair<Pair<ReceiveCommand, ReceiveCommand>, Exception>> executedCommands,
			final LinkedException storageErrors) {
		executedCommands.stream().forEach(pair -> {
			try {
				deleteTempBranch(pair.getLeft().getRight(), repository);
			} catch (final IOException e) {
				storageErrors.add(e);
			}
		});
	}

	private void updateLocalData(final List<Pair<Pair<ReceiveCommand, ReceiveCommand>, Exception>> executedCommands,
			final LinkedException storageErrors, final Collection<String> deletedBranches) {
		final RemoteConfig remoteConfig = getRemoteConfig();
		final List<String> refsToBeUpdated = executedCommands.stream().map(pair -> {
			final Pair<ReceiveCommand, ReceiveCommand> receiveCommands = pair.getLeft();
			final ReceiveCommand orig = receiveCommands.getLeft();
			final ReceiveCommand test = receiveCommands.getRight();

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
			deletedBranches.stream().forEach(this::deleteBranch);
			listeners.forEach(l -> l.onEvent(refsToBeUpdated));
		}
	}

	private void deleteBranch(final String branch) {
		try {
			final RefUpdate updateRef = repository.updateRef(branch);
			updateRef.setForceUpdate(true);
			checkResult(branch, updateRef.delete());
		} catch (final IOException e) {
			throw new UncheckedIOException("Delete " + branch + " failed", e);
		}
	}

	private Pair<Pair<ReceiveCommand, ReceiveCommand>, Exception> checkBranchSource(
			final Pair<Pair<ReceiveCommand, ReceiveCommand>, Exception> commandResult) {
		if (commandResult.getRight() != null) {
			return commandResult;
		}
		final Pair<ReceiveCommand, ReceiveCommand> receiveCommands = commandResult.getLeft();
		final ReceiveCommand testReceiveCommand = receiveCommands.getRight();
		try (final SourceChecker sc = new SourceChecker(repository)) {
			final String testBranchName = testReceiveCommand.getRefName();
			final List<Pair<Set<Ref>, List<Pair<FileObjectIdStore, Exception>>>> checkTest = sc.checkTestBranchForErrors(testBranchName);
			// Only one branch so 1 element
			final Pair<Set<Ref>, List<Pair<FileObjectIdStore, Exception>>> refAndErrorPair = checkTest.get(0);
			final List<Pair<FileObjectIdStore, Exception>> refErrors = refAndErrorPair.getRight();
			if (!refErrors.isEmpty()) {
				final ReceiveCommand actualReceiveCommand = receiveCommands.getLeft();
				final Ref realRef = repository.findRef(actualReceiveCommand.getRefName());
				final Set<Ref> refSet = new HashSet<>();
				refSet.add(realRef);
				testReceiveCommand.setResult(Result.REJECTED_OTHER_REASON, "Error in branch " + actualReceiveCommand.getRefName());
				return Pair.of(receiveCommands, new CorruptedSourceException(Arrays.asList(Pair.of(refSet, refErrors))));
			}
		} catch (final RefNotFoundException | IOException e) {
			testReceiveCommand.setResult(Result.REJECTED_OTHER_REASON, e.getMessage());
			return Pair.of(receiveCommands, e);
		}
		return Pair.of(receiveCommands, null);
	}

	private RefSpec getRefSpec(final List<RefSpec> fetchRefSpecs, final String refName) {
		// TODO Manage this with wildcards
		for (final RefSpec refSpec : fetchRefSpecs) {
			if (refSpec.getSource().equals(refName)) {
				return refSpec;
			}
		}
		final RefSpec refSpec = new RefSpec();
		final String branch = getRefName(refName);
		return refSpec.setSourceDestination(refName, Constants.R_HEADS + branch);
	}

	private RemoteConfig getRemoteConfig() {
		try {
			return new RemoteConfig(repository.getConfig(), Constants.DEFAULT_REMOTE_NAME);
		} catch (final URISyntaxException e) {
			throw new RevisionSyntaxException("");
		}
	}

	private void checkResult(final String branchName, final org.eclipse.jgit.lib.RefUpdate.Result result) throws IOException {
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
			throw new IOException("Action on branch " + branchName + " failed with " + result);
		}
	}

	private void createTmpBranch(final Repository repository, final String testBranchName, TrackingRefUpdate tru) throws IOException {
		final RefUpdate updateRef = repository.updateRef(testBranchName);
		updateRef.setExpectedOldObjectId(ObjectId.zeroId());
		updateRef.setNewObjectId(tru.getOldObjectId());
		updateRef.setForceUpdate(true);
		updateRef.disableRefLog();
		checkResult(testBranchName, updateRef.forceUpdate());
	}

	private void deleteTempBranch(final ReceiveCommand rc, final Repository repository) throws IOException {
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
			LOG.warn("Unregistered exception", old);
		}
	}

	public URI repositoryURI() {
		return remoteRepo;
	}

	@Override
	public void close() {
		this.repoExecutor.shutdown();
		try {
			this.repoExecutor.awaitTermination(10, TimeUnit.SECONDS);
		} catch (InterruptedException ignore) {
		}
		if (this.repository != null) {
			this.repository.close();
		}
	}

	public SourceInfo getSourceInfo(final String key, String ref) throws RefNotFoundException {
		Objects.requireNonNull(key);
		Objects.requireNonNull(ref);
		try {
			if (ref.startsWith(Constants.R_HEADS)) {
				return extractor.openBranch(ref, key);
			} else if (ref.startsWith(Constants.R_TAGS)) {
				return extractor.openTag(ref, key);
			}
		} catch (final IOException e) {
			throw new UncheckedIOException(e);
		}
		throw new RefNotFoundException(ref);
	}

	public CompletableFuture<String> modify(final JsonNode data, final String version, final String message, final String userInfo,
			final String userMail, final String key, String ref) {
		if (ref == null) {
			ref = defaultRef;
		}
		if (ref.startsWith(Constants.R_TAGS)) {
			throw new UnsupportedOperationException("Tags cannot be modified");
		}
		final String finalRef = ref;
		return CompletableFuture.supplyAsync(() -> {
			try {
				if (!checkVersion(version, key, finalRef)) {
					throw new WrappingAPIException(new VersionIsNotSameException());
				}
				final Ref actualRef = repository.findRef(finalRef);
				if (actualRef == null) {
					throw new WrappingAPIException(new RefNotFoundException(finalRef));
				}
				final String newVersion = this.updater.updateKey(key, actualRef, MAPPER.writeValueAsBytes(data), message, userInfo,
						userMail);
				pushChangesToRemote();
				return newVersion;
			} catch (final IOException | RefNotFoundException e) {
				throw new WrappingAPIException(e);
			}
		}, repoExecutor);
	}

	// TODO Better fault handling here if remote somehow fails
	private void pushChangesToRemote() {
		try (Git git = Git.wrap(repository)) {
			try {
				final Iterable<PushResult> results = git.push().setRemote(Constants.DEFAULT_REMOTE_NAME).setPushAll().call();
				final List<String> updateErrors = new ArrayList<>();
				for (final PushResult pr : results) {
					updateErrors.addAll(pr.getRemoteUpdates().stream()
							.filter(rru -> org.eclipse.jgit.transport.RemoteRefUpdate.Status.OK != rru.getStatus())
							.map(rru -> rru.getRemoteName() + " isn't updated due to " + rru.getStatus() + ":" + rru.getMessage())
							.collect(Collectors.toList()));
				}
				if (!updateErrors.isEmpty()) {
					setFault(new RemoteUpdateException(updateErrors));
				}
			} catch (final GitAPIException e) {
				setFault(e);
			}
		}
	}

	private boolean checkVersion(final String version, final String key, final String ref) throws RefNotFoundException {
		final SourceInfo sourceInfo = getSourceInfo(key, ref);
		if (sourceInfo == null) {
			return false;
		}
		return version.equals(sourceInfo.getSourceVersion());
	}
}
