package io.jitstatic.hosted;

/*-
 * #%L
 * jitstatic
 * %%
 * Copyright (C) 2017 - 2018 H.Hegardt
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
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.BatchRefUpdate;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceiveCommand.Result;
import org.eclipse.jgit.transport.ReceiveCommand.Type;
import org.eclipse.jgit.transport.ReceivePack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.spencerwi.either.Either;

import io.jitstatic.JitStaticConstants;
import io.jitstatic.check.CorruptedSourceException;
import io.jitstatic.check.FileObjectIdStore;
import io.jitstatic.check.SourceChecker;
import io.jitstatic.hosted.events.AddRefEvent;
import io.jitstatic.hosted.events.DeleteRefEvent;
import io.jitstatic.hosted.events.ReloadRefEvent;
import io.jitstatic.utils.ErrorReporter;
import io.jitstatic.utils.Pair;

public class JitStaticReceivePack extends ReceivePack {

    private static final Logger LOG = LoggerFactory.getLogger(JitStaticReceivePack.class);

    private final String defaultRef;
    private final RefLockHolderManager reflockHolder;
    private final ErrorReporter errorReporter;
    private final SourceChecker sourceChecker;
    private final UserExtractor userExtractor;
    private final boolean canForceUpdate;

    private final RepoInserter repoInserter;

    private final ExecutorService repoSerializer;

    public JitStaticReceivePack(final Repository into, final String defaultRef, final ErrorReporter errorReporter, final RefLockHolderManager bus,
            final SourceChecker sourceChecker, final UserExtractor userExtractor, final boolean canForceUpdate, final RepoInserter inserter,
            final ExecutorService repoWriter) {
        super(into);
        this.defaultRef = Objects.requireNonNull(defaultRef);
        this.reflockHolder = Objects.requireNonNull(bus);
        this.errorReporter = Objects.requireNonNull(errorReporter);
        this.sourceChecker = Objects.requireNonNull(sourceChecker);
        this.userExtractor = Objects.requireNonNull(userExtractor);
        this.repoInserter = Objects.requireNonNull(inserter);
        this.repoSerializer = Objects.requireNonNull(repoWriter);
        this.canForceUpdate = canForceUpdate;
    }

    // TODO Refactor into its own xUpdate class
    @Override
    protected void executeCommands() {
        ProgressMonitor updating = getProgressBar();
        final List<ReceiveCommand> originalCommands = filterCommands(Result.NOT_ATTEMPTED);
        if (Objects.requireNonNull(originalCommands).isEmpty()) {
            return;
        }
        final List<ReceiveCommand> commandsToBeBatched = new ArrayList<>(originalCommands.size());
        final List<Pair<ReceiveCommand, ReceiveCommand>> indexPairs = createTempBranchesAndIndex(originalCommands, commandsToBeBatched);
        try {
            updateAndCommit(updating, commandsToBeBatched, indexPairs);
        } finally {
            final List<String> refs = commandsToBeBatched.stream()
                    .filter(rc -> rc.getRefName().startsWith(JitStaticConstants.REFS_JITSTATIC))
                    .map(ReceiveCommand::getRefName)
                    .collect(Collectors.toList());
            CompletableFuture.runAsync(() -> refs.forEach(ref -> {
                try {
                    final RefUpdate ru = getRepository().updateRef(ref);
                    ru.setForceUpdate(true);
                    ru.delete();
                } catch (IOException e) {
                    LOG.info(e.getLocalizedMessage());
                }
            }), repoSerializer);
        }
    }

    private ProgressMonitor getProgressBar() {
        if (isSideBand()) {
            final SideBandProgressMonitor pm = new SideBandProgressMonitor(msgOut);
            pm.setDelayStart(250, TimeUnit.MILLISECONDS);
            return pm;
        }
        return NullProgressMonitor.INSTANCE;
    }

    private void updateAndCommit(final ProgressMonitor updating, final List<ReceiveCommand> commandsToBeExecuted, final List<Pair<ReceiveCommand, ReceiveCommand>> indexPairs) {
        try {
            batchUpdate(updating, commandsToBeExecuted);
            if (commandsToBeExecuted.stream().anyMatch(rc -> rc.getResult() != Result.OK)) {
                throw new IOException("Error applying commands");
            }
            commitCommands(checkBranches(updating, commandsToBeExecuted, indexPairs).stream().filter(p -> {
                if (p.isPresent()) {
                    return p.getRight().getResult() == Result.OK;
                }
                return true;
            }).collect(Collectors.toList()), updating);
        } catch (IOException err) {
            for (Pair<ReceiveCommand, ReceiveCommand> cmd : indexPairs) {
                final ReceiveCommand rc = cmd.getLeft();
                final Result result = rc.getResult();
                if (result == Result.NOT_ATTEMPTED || result == Result.OK) {
                    rc.setResult(Result.REJECTED_OTHER_REASON, MessageFormat.format(JGitText.get().lockError, err.getMessage()));
                }
            }
        }
    }

    private List<Pair<ReceiveCommand, ReceiveCommand>> createTempBranchesAndIndex(final List<ReceiveCommand> originalCommands, final List<ReceiveCommand> commandsToBeChecked) {
        final List<Pair<ReceiveCommand, ReceiveCommand>> indexPairs = new ArrayList<>(originalCommands.size());
        for (ReceiveCommand original : originalCommands) {
            if (original.getType() != Type.DELETE) {
                final String branch = original.getRefName();
                try {
                    final String testBranchName = createTmpBranch(branch);
                    final ReceiveCommand testReceiveCommand = new ReceiveCommand(original.getOldId(), original.getNewId(), testBranchName, original.getType());
                    commandsToBeChecked.add(testReceiveCommand);
                    indexPairs.add(Pair.of(original, testReceiveCommand));
                } catch (IOException e) {
                    original.setResult(Result.REJECTED_NOCREATE, "Couldn't create test branch for " + branch + " because " + e.getLocalizedMessage());
                } catch (UpdateResultException updateResult) {
                    interpretResult(original, updateResult, original.getRefName());
                }
            } else {
                if (defaultRef.equals(original.getRefName())) {
                    original.setResult(Result.REJECTED_NODELETE, "Cannot delete default branch " + defaultRef);
                } else {
                    indexPairs.add(Pair.of(original, null));
                }
            }
        }
        return indexPairs;
    }

    private List<Pair<ReceiveCommand, ReceiveCommand>> checkBranches(final ProgressMonitor updating, final List<ReceiveCommand> batchedCommands, final List<Pair<ReceiveCommand, ReceiveCommand>> index) {
        updating.beginTask("Checking branches", batchedCommands.size());
        for (Pair<ReceiveCommand, ReceiveCommand> pair : index) {
            final ReceiveCommand testRc = pair.getRight();
            final ReceiveCommand originalRc = pair.getLeft();
            if (testRc != null) {
                final String testBranchName = testRc.getRefName();
                final String branch = originalRc.getRefName();
                if (testRc.getResult() == Result.OK) {
                    checkBranchData(branch, testBranchName, testRc);
                }
                if (testRc.getResult() != Result.OK) {
                    originalRc.setResult(testRc.getResult(), testRc.getMessage());
                    sendMessage(branch + " failed with " + originalRc.getResult() + " " + originalRc.getMessage());
                }
            }
            updating.update(1);
        }
        updating.endTask();
        return index;
    }

    private void batchUpdate(final ProgressMonitor monitor, final List<ReceiveCommand> cmds) throws IOException {
        if (!cmds.isEmpty()) {
            final BatchRefUpdate batch = getRepository().getRefDatabase().newBatchUpdate();
            batch.setAllowNonFastForwards(isAllowNonFastForwards());
            batch.setAtomic(isAtomic());
            batch.disableRefLog();
            batch.addCommand(cmds);
            batch.setPushCertificate(getPushCertificate());
            batch.execute(getRevWalk(), monitor);
        }
    }

    private void checkBranchData(final String branch, final String testBranchName, final ReceiveCommand testRc) {
        try {
            sendMessage("Checking " + branch + " branch.");
            final List<Pair<Set<Ref>, List<Pair<FileObjectIdStore, Exception>>>> mergedData = checkAndMerge(branch, testBranchName);
            final Pair<List<String>, List<String>> interpretedErrorMessages = CorruptedSourceException.interpreteMessages(mergedData, testBranchName, branch);
            final List<String> errors = new ArrayList<>(interpretedErrorMessages.getLeft());
            final List<String> warnings = new ArrayList<>(interpretedErrorMessages.getRight());

            warnings.forEach(this::sendMessage);

            if (!errors.isEmpty()) {
                errors.forEach(this::sendError);
                testRc.setResult(Result.REJECTED_OTHER_REASON, errors.get(0));
            } else {
                sendMessage(branch + " OK!");
            }
        } catch (final IOException storageIsCorrupt) {
            sendError("Couldn't resolve " + branch + " because " + storageIsCorrupt.getLocalizedMessage());
            testRc.setResult(Result.REJECTED_OTHER_REASON, storageIsCorrupt.getMessage());
            setFault(new RepositoryException(storageIsCorrupt.getMessage(), storageIsCorrupt));
        } catch (final Exception unexpected) {
            final String msg = "General error " + unexpected.getMessage();
            sendError(msg);
            testRc.setResult(Result.REJECTED_OTHER_REASON, msg);
            setFault(new RepositoryException(msg, unexpected));
        }
    }

    private List<Pair<Set<Ref>, List<Pair<FileObjectIdStore, Exception>>>> checkAndMerge(final String branch, final String testBranchName)
            throws RefNotFoundException, IOException {
        return Stream.concat(sourceChecker.checkTestBranchForErrors(testBranchName).stream(), userExtractor.checkOnTestBranch(testBranchName, branch).stream()).collect(Collectors.toList());
    }

    private void commitCommands(final List<Pair<ReceiveCommand, ReceiveCommand>> cmds, final ProgressMonitor monitor) {
        final Repository repository = getRepository();
        monitor.beginTask("Committing branches", cmds.size());
        cmds.stream().map(commitCommand(monitor, repository)).filter(Either::isRight).forEach(e -> sendError(e.getRight().toString()));
        monitor.endTask();
    }

    private Function<? super Pair<ReceiveCommand, ReceiveCommand>, ? extends Either<String, FailedToLock>> commitCommand(final ProgressMonitor monitor, final Repository repository) {
        return cmd -> {
            final String refName = cmd.getLeft().getRefName();
            try {
                if (cmd.getLeft().getType() == Type.CREATE) {
                    sendMessage("Adding " + refName);
                    getRepository().fireEvent(new AddRefEvent(refName));
                }
                final RefLockHolder refHolder = reflockHolder.getRefHolder(refName);
                final ReceiveCommand orig = cmd.getLeft();
                final ReceiveCommand test = cmd.getRight();
                final Either<String, FailedToLock> lock = enqueueOnRef(repository, refName, refHolder, orig, test);

                if (lock.isRight()) {
                    final FailedToLock ftl = lock.getRight();
                    final Result result = cmd.getLeft().getResult();
                    if (result == Result.OK || result == Result.NOT_ATTEMPTED) {
                        cmd.getLeft().setResult(Result.LOCK_FAILURE, ftl.getLocalizedMessage());
                    }
                }
                return lock;
            } finally {
                monitor.update(1);
            }
        };
    }

    private Either<String, FailedToLock> enqueueOnRef(final Repository repository, final String refName, final RefLockHolder refHolder, final ReceiveCommand orig, final ReceiveCommand test) {
        return refHolder.enqueueAndBlock(() -> tryCommit(repository, refName, orig, test), () -> {
            final ObjectId newId = orig.getNewId();
            final ObjectId oldId = orig.getOldId();
            return new DistributedData(os -> {
                if (!ObjectId.zeroId().equals(newId)) {
                    repoInserter.packData(Set.of(), Set.of(newId), Set.of(oldId)).accept(os);
                }
            }, newId.name(), oldId.name());
        }, e -> {
            switch (orig.getType()) {
            case CREATE:
                if (e != null) {
                    getRepository().fireEvent(new DeleteRefEvent(refName));
                }
                break;
            case DELETE:
                sendMessage("Deleting " + refName);
                getRepository().fireEvent(new DeleteRefEvent(refName));
                break;
            case UPDATE:
            case UPDATE_NONFASTFORWARD:
                sendMessage("Reloading " + refName);
                getRepository().fireEvent(new ReloadRefEvent(refName));
                break;
            default:
                break;
            }
        }).exceptionally(t -> {
            LOG.warn("Failed when locking branch {}", refName, t);
            return Either.right(FailedToLock.create(t, refName));
        }).join();
    }

    @Nullable
    private Exception tryCommit(final Repository repository, final String refName, final ReceiveCommand orig, final ReceiveCommand test) {
        try {
            final Ref ref = repository.findRef(refName);
            checkForRef(orig, refName, ref);
            checkForBranchStaleness(orig, refName, ref);
            final RefUpdate updateRef = repository.updateRef(refName);
            updateRef.disableRefLog();
            updateRef.setPushCertificate(getPushCertificate());
            updateRef.setForceUpdate(canForceUpdate);
            if (orig.getType() == Type.DELETE) {
                orig.setResult(updateRef.delete(getRevWalk()));
            } else {
                updateRef.setNewObjectId(orig.getNewId());
                updateRef.setExpectedOldObjectId(orig.getOldId());
                checkResult(refName, updateRef.update());
                orig.setResult(test.getResult(), test.getMessage());
            }
            return null;
        } catch (final CommandIsStale e11) {
            orig.setResult(Result.REJECTED_NONFASTFORWARD, e11.getLocalizedMessage());
            return e11;
        } catch (final RefNotFoundException e12) {
            orig.setResult(Result.REJECTED_MISSING_OBJECT, e12.getLocalizedMessage());
            return e12;
        } catch (final UpdateResultException updateResult) {
            interpretResult(orig, updateResult, orig.getRefName());
            return updateResult;
        } catch (final IOException e1) {
            orig.setResult(Result.REJECTED_OTHER_REASON, e1.getLocalizedMessage());
            final String msg = "Error while writing commit, repo is in an unknown state";
            final RepositoryException repoException = new RepositoryException(msg, e1);
            setFault(repoException);
            return repoException;
        }
    }

    private void interpretResult(final ReceiveCommand rc, final UpdateResultException updateResult, final String refName) {
        switch (updateResult.getResult()) {
        case LOCK_FAILURE:
            rc.setResult(Result.LOCK_FAILURE, "Failed to lock " + refName);
            break;
        case NOT_ATTEMPTED:
        case REJECTED:
            rc.setResult(Result.REJECTED_NONFASTFORWARD, refName);
            break;
        case REJECTED_CURRENT_BRANCH:
        default:
            rc.setResult(Result.REJECTED_CURRENT_BRANCH, refName);
            break;
        }
    }

    private void checkForBranchStaleness(final ReceiveCommand orig, final String refName, final Ref ref) throws CommandIsStale {
        if (ref != null && !ObjectId.isEqual(ref.getObjectId(), orig.getOldId())) {
            throw new CommandIsStale(refName, orig.getOldId(), ref.getObjectId());
        }
    }

    private void checkForRef(final ReceiveCommand orig, final String refName, final Ref ref) throws RefNotFoundException {
        if (!ObjectId.isEqual(orig.getOldId(), ObjectId.zeroId()) && ref == null) {
            throw new RefNotFoundException(refName);
        }
    }

    private void setFault(final Exception e) {
        errorReporter.setFault(e);
        LOG.error("Error occourred ", e);
    }

    private String createTmpBranch(final String branch) throws IOException, UpdateResultException {
        final String testBranchName = JitStaticConstants.REFS_JITSTATIC + UUID.randomUUID();
        final RefUpdate updateRef = getRepository().updateRef(testBranchName);
        ObjectId base = getRepository().resolve(branch);
        if (base == null) {
            base = ObjectId.zeroId();
        }
        updateRef.setExpectedOldObjectId(ObjectId.zeroId());
        updateRef.setNewObjectId(base);
        updateRef.setForceUpdate(true);
        updateRef.disableRefLog();
        checkResult(testBranchName, updateRef.forceUpdate());
        return testBranchName;
    }

    private void checkResult(final String refName, final RefUpdate.Result result) throws IOException, UpdateResultException {
        switch (result) {
        case FAST_FORWARD:
        case FORCED:
        case NEW:
        case RENAMED:
        case NO_CHANGE:
            break;
        case LOCK_FAILURE:
        case NOT_ATTEMPTED:
        case REJECTED:
        case REJECTED_CURRENT_BRANCH:
            throw new UpdateResultException(result);
        case IO_FAILURE:
        default:
            throw new IOException("Created branch " + refName + " failed with " + result);
        }
    }
}
