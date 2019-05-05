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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.BatchRefUpdate;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceiveCommand.Result;
import org.eclipse.jgit.transport.ReceivePack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.spencerwi.either.Either;

import io.jitstatic.JitStaticConstants;
import io.jitstatic.check.CorruptedSourceException;
import io.jitstatic.check.SourceChecker;
import io.jitstatic.hosted.events.DeleteRefEvent;
import io.jitstatic.hosted.events.ReloadRefEvent;
import io.jitstatic.utils.Pair;

public class JitStaticReceivePack extends ReceivePack {

    private static final PersonIdent JITSTATIC_SYSTEM = new PersonIdent("jitstatic maintenance", "none@nowhere");

    private static final Logger LOG = LoggerFactory.getLogger(JitStaticReceivePack.class);

    private final String defaultRef;
    private final RefLockHolderManager bus;
    private final ErrorReporter errorReporter;
    private final SourceChecker sourceChecker;
    private final UserExtractor userExtractor;
    private final boolean canForceUpdate;

    public JitStaticReceivePack(final Repository into, final String defaultRef, final ErrorReporter errorReporter, final RefLockHolderManager bus,
            final SourceChecker sourceChecker, final UserExtractor userExtractor, boolean canForceUpdate) {
        super(into);
        this.defaultRef = Objects.requireNonNull(defaultRef);
        this.bus = Objects.requireNonNull(bus);
        this.errorReporter = Objects.requireNonNull(errorReporter);
        this.sourceChecker = Objects.requireNonNull(sourceChecker);
        this.userExtractor = Objects.requireNonNull(userExtractor);
        this.canForceUpdate = canForceUpdate;
    }

    // TODO Refactor into its own xUpdate class
    @Override
    protected void executeCommands() {
        ProgressMonitor updating = getProgressBar();
        final List<ReceiveCommand> commands = filterCommands(Result.NOT_ATTEMPTED);
        if (Objects.requireNonNull(commands).isEmpty())
            return;

        final List<ReceiveCommand> cmds = new ArrayList<>(commands.size());
        final Map<String, ReceiveCommand> index = createTempBranchesAndIndex(commands, cmds);
        try {
            updateAndCommit(updating, cmds, index);
        } finally {
            CompletableFuture.runAsync(() -> {
                List<ReceiveCommand> tmpRefs = cmds.stream()
                        .filter(rc -> rc.getRefName().startsWith(JitStaticConstants.REFS_JITSTATIC))
                        .map(rc -> new ReceiveCommand(rc.getNewId(), ObjectId.zeroId(), rc.getRefName(), ReceiveCommand.Type.DELETE))
                        .collect(Collectors.toList());
                maintenanceBatchUpdate(tmpRefs);
            });
        }
    }

    private ProgressMonitor getProgressBar() {
        if (isSideBand()) {
            SideBandProgressMonitor pm = new SideBandProgressMonitor(msgOut);
            pm.setDelayStart(250, TimeUnit.MILLISECONDS);
            return pm;
        }
        return NullProgressMonitor.INSTANCE;
    }

    private void updateAndCommit(ProgressMonitor updating, final List<ReceiveCommand> cmds, final Map<String, ReceiveCommand> index) {
        if (!cmds.isEmpty()) {
            try {
                batchUpdate(updating, cmds);
                commitCommands(checkBranches(updating, cmds, index).stream().filter(p -> {
                    if (p.isPresent()) {
                        return p.getRight().getResult() == Result.OK;
                    }
                    // Deleted Branch
                    return p.getLeft().getResult() == Result.OK;
                }).collect(Collectors.toList()), updating);
            } catch (IOException err) {
                for (ReceiveCommand cmd : cmds) {
                    ReceiveCommand rc = index.get(cmd.getRefName());
                    if (rc == null) {
                        rc = cmd;
                    }
                    if (rc.getResult() == Result.NOT_ATTEMPTED)
                        cmd.setResult(Result.REJECTED_OTHER_REASON, MessageFormat.format(
                                JGitText.get().lockError, err.getMessage()));
                }
            }
        }
    }

    private Map<String, ReceiveCommand> createTempBranchesAndIndex(final List<ReceiveCommand> commands, final List<ReceiveCommand> cmds) {
        final Map<String, ReceiveCommand> map = new HashMap<>();
        for (ReceiveCommand rc : commands) {
            if (!ObjectId.equals(ObjectId.zeroId(), rc.getNewId())) {
                final String branch = rc.getRefName();
                try {
                    final String testBranchName = createTmpBranch(branch);
                    cmds.add(new ReceiveCommand(rc.getOldId(), rc.getNewId(), testBranchName, rc.getType()));
                    map.put(testBranchName, rc);
                } catch (IOException e) {
                    rc.setResult(Result.REJECTED_NOCREATE, "Couldn't create test branch for " + branch + " because " + e.getLocalizedMessage());
                } catch (UpdateResultException updateResult) {
                    interpretResult(rc, updateResult, rc.getRefName());
                }
            } else {
                if (defaultRef.equals(rc.getRefName())) {
                    rc.setResult(Result.REJECTED_NODELETE, "Cannot delete default branch " + defaultRef);
                } else {
                    cmds.add(rc);
                }
            }
        }
        return map;
    }

    private void maintenanceBatchUpdate(List<ReceiveCommand> tmpRefs) {
        try {
            final BatchRefUpdate batch = getRepository().getRefDatabase().newBatchUpdate();
            batch.setAllowNonFastForwards(true);
            batch.setAtomic(true);
            batch.setRefLogIdent(JITSTATIC_SYSTEM);
            batch.setRefLogMessage("delete tmp branches", true);
            batch.addCommand(tmpRefs);
            batch.setPushCertificate(null);
            batch.execute(new RevWalk(getRepository()), NullProgressMonitor.INSTANCE);
        } catch (IOException err) {
            setFault(new RepositoryException("General error while deleting branches.", err));
        }
    }

    private List<Pair<ReceiveCommand, ReceiveCommand>> checkBranches(ProgressMonitor updating, final List<ReceiveCommand> cmds,
            final Map<String, ReceiveCommand> map) {
        final List<Pair<ReceiveCommand, ReceiveCommand>> checkedBranches = new ArrayList<>(cmds.size());
        updating.beginTask("Checking branches", cmds.size());
        for (ReceiveCommand testRc : cmds) {
            final String testBranchName = testRc.getRefName();
            final ReceiveCommand rc = map.get(testBranchName);
            Pair<ReceiveCommand, ReceiveCommand> p;
            if (rc != null) {
                final String branch = rc.getRefName();
                if (testRc.getResult() == Result.OK) {
                    checkBranchData(branch, testBranchName, testRc);
                }
                if (testRc.getResult() != Result.OK) {
                    rc.setResult(testRc.getResult(), testRc.getMessage());
                    sendMessage(branch + " failed with " + rc.getResult() + " " + rc.getMessage());
                }
                p = Pair.of(rc, testRc);
            } else {
                p = Pair.of(testRc, null);
            }
            checkedBranches.add(p);
            updating.update(1);
        }
        updating.endTask();
        return checkedBranches;
    }

    private void batchUpdate(final ProgressMonitor monitor, final List<ReceiveCommand> cmds) throws IOException {
        final BatchRefUpdate batch = getRepository().getRefDatabase().newBatchUpdate();
        batch.setAllowNonFastForwards(isAllowNonFastForwards());
        batch.setAtomic(isAtomic());
        batch.setRefLogIdent(getRefLogIdent());
        batch.setRefLogMessage("test push", true);
        batch.addCommand(cmds);
        batch.setPushCertificate(getPushCertificate());
        batch.execute(getRevWalk(), monitor);
    }

    private void checkBranchData(final String branch, final String testBranchName, final ReceiveCommand testRc) {
        try {
            sendMessage("Checking " + branch + " branch.");
            final Pair<List<String>, List<String>> interpretedErrorMessages = CorruptedSourceException
                    .interpreteMessages(sourceChecker.checkTestBranchForErrors(testBranchName), testBranchName, branch);
            final Pair<List<String>, List<String>> interpretedMessages = CorruptedSourceException
                    .interpreteMessages(userExtractor.checkOnTestBranch(testBranchName, branch), testBranchName, branch);
            final List<String> errors = new ArrayList<>(interpretedErrorMessages.getLeft());
            final List<String> warnings = new ArrayList<>(interpretedErrorMessages.getRight());
            
            errors.addAll(interpretedMessages.getLeft());
            warnings.addAll(interpretedMessages.getRight());
            
            for (String w : warnings) {
                sendMessage(w);
            }
            if (!errors.isEmpty()) {               
                for (String s : errors) {
                    sendError(s);
                }
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

    private void signalReload(final Pair<ReceiveCommand, ReceiveCommand> cmds) {
        final ReceiveCommand actualRef = cmds.getLeft();
        final ReceiveCommand testRef = cmds.getRight();
        final String refName = actualRef.getRefName();
        if (testRef != null) {
            sendMessage("Reloading " + refName);
            getRepository().fireEvent(new ReloadRefEvent(refName));
        }
    }

    private void commitCommands(final List<Pair<ReceiveCommand, ReceiveCommand>> cmds, final ProgressMonitor monitor) {
        final Repository repository = getRepository();
        monitor.beginTask("Commiting branches", cmds.size());
        // This should be done when the lock is done on the ref when updating it...
        cmds.stream().map(cmd -> {
            final String refName = cmd.getLeft().getRefName();
            try {
                if (cmd.getRight() == null) {
                    sendMessage("Deleting " + refName);
                    getRepository().fireEvent(new DeleteRefEvent(refName));
                    return null;
                } else {
                    final Either<Exception, FailedToLock> lock = bus.getRefHolder(refName).lockWriteAll(() -> commitBranch(refName, cmd, repository));
                    if (lock.isRight()) {
                        FailedToLock ftl = lock.getRight();
                        cmd.getLeft().setResult(Result.LOCK_FAILURE, ftl.getLocalizedMessage());
                        return ftl;
                    }
                    return lock.getLeft();
                }
            } finally {
                monitor.update(1);
            }            
        }).filter(Objects::nonNull).forEach(e -> sendError(e.getLocalizedMessage()));
        monitor.endTask();
    }

    private Exception commitBranch(final String refName, final Pair<ReceiveCommand, ReceiveCommand> receiveCommandsPair, final Repository repository) {
        final ReceiveCommand orig = receiveCommandsPair.getLeft();
        final ReceiveCommand test = receiveCommandsPair.getRight();
        try {
            if (receiveCommandsPair.isPresent()) {
                final Ref ref = repository.findRef(refName);
                checkForRef(orig, refName, ref);
                checkForBranchStaleness(orig, refName, ref);
                final RefUpdate updateRef = repository.updateRef(refName);
                updateRef.setRefLogMessage("JitStatic Git push", true);
                updateRef.setRefLogIdent(getRefLogIdent());
                updateRef.setPushCertificate(getPushCertificate());
                updateRef.setNewObjectId(test.getNewId());
                updateRef.setForceUpdate(canForceUpdate);
                checkResult(refName, updateRef.update());
                orig.setResult(test.getResult(), test.getMessage());
            }
            signalReload(receiveCommandsPair);
            return null;
        } catch (final CommandIsStale e1) {
            orig.setResult(Result.REJECTED_NONFASTFORWARD, e1.getLocalizedMessage());
            return e1;
        } catch (final RefNotFoundException e1) {
            orig.setResult(Result.REJECTED_MISSING_OBJECT, e1.getLocalizedMessage());
            return e1;
        } catch (final UpdateResultException updateResult) {
            interpretResult(orig, updateResult, orig.getRefName());
            return updateResult;
        } catch (final IOException e) {
            orig.setResult(Result.REJECTED_OTHER_REASON, e.getLocalizedMessage());
            final String msg = "Error while writing commit, repo is in an unknown state";
            final RepositoryException repoException = new RepositoryException(msg, e);
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
        if (ref != null && !ObjectId.equals(ref.getObjectId(), orig.getOldId())) {
            throw new CommandIsStale(refName, orig.getOldId(), ref.getObjectId());
        }
    }

    private void checkForRef(final ReceiveCommand orig, final String refName, final Ref ref) throws RefNotFoundException {
        if (!ObjectId.equals(orig.getOldId(), ObjectId.zeroId()) && ref == null) {
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

    private void checkResult(final String testBranchName, final org.eclipse.jgit.lib.RefUpdate.Result result) throws IOException, UpdateResultException {
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
            throw new IOException("Created branch " + testBranchName + " failed with " + result);
        }
    }
}
