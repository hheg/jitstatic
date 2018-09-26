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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceiveCommand.Result;
import org.eclipse.jgit.transport.ReceivePack;

import com.spencerwi.either.Either;

import io.jitstatic.JitStaticConstants;
import io.jitstatic.check.CorruptedSourceException;
import io.jitstatic.check.FileObjectIdStore;
import io.jitstatic.check.SourceChecker;
import io.jitstatic.utils.Pair;

public class JitStaticReceivePack extends ReceivePack {

    private static final Logger LOG = LogManager.getLogger(JitStaticReceivePack.class);

    private final String defaultRef;
    private final RepositoryBus bus;
    private final ErrorReporter errorReporter;

    public JitStaticReceivePack(final Repository into, final String defaultRef, final ErrorReporter errorReporter,
            final RepositoryBus bus) {
        super(into);
        this.defaultRef = Objects.requireNonNull(defaultRef);
        this.bus = Objects.requireNonNull(bus);
        this.errorReporter = errorReporter;
    }

    // TODO Check branches format and refactor into its own xUpdate class
    @Override
    protected void executeCommands() {
        ProgressMonitor updating = NullProgressMonitor.INSTANCE;
        if (isSideBand()) {
            SideBandProgressMonitor pm = new SideBandProgressMonitor(msgOut);
            pm.setDelayStart(250, TimeUnit.MILLISECONDS);
            updating = pm;
        }
        final List<ReceiveCommand> commands = filterCommands(Result.NOT_ATTEMPTED);
        if (Objects.requireNonNull(commands).isEmpty())
            return;

        final List<Pair<ReceiveCommand, ReceiveCommand>> cmdsToBeExecuted = new ArrayList<>(commands.size());
        updating.beginTask("Checking branches", cmdsToBeExecuted.size());
        for (final ReceiveCommand rc : commands) {
            if (!ObjectId.equals(ObjectId.zeroId(), rc.getNewId())) {
                checkBranch(cmdsToBeExecuted, rc, updating);
            } else {
                if (defaultRef.equals(rc.getRefName())) {
                    rc.setResult(Result.REJECTED_NODELETE, "Cannot delete default branch " + defaultRef);
                }
                cmdsToBeExecuted.add(Pair.of(rc, null)); // Deleted branches
            }
            updating.update(1);
        }
        updating.endTask();
        tryAndCommit(cmdsToBeExecuted, updating);
    }

    private void checkBranch(final List<Pair<ReceiveCommand, ReceiveCommand>> cmdsToBeExecuted, final ReceiveCommand rc,
            final  ProgressMonitor monitor) {
        final String branch = rc.getRefName();
        final String testBranchName = JitStaticConstants.REFS_JISTSTATIC + UUID.randomUUID();
        try {
            createTmpBranch(testBranchName, branch);
            final ReceiveCommand testRc = new ReceiveCommand(rc.getOldId(), rc.getNewId(), testBranchName, rc.getType());
            testRc.setRefLogMessage(rc.getRefLogMessage(), true);
            testRc.execute(this);
            cmdsToBeExecuted.add(Pair.of(rc, testRc));
            if (testRc.getResult() == Result.OK) {
                checkBranchData(branch, testBranchName, testRc);
            }
            if (testRc.getResult() != Result.OK) {
                rc.setResult(testRc.getResult(), testRc.getMessage());
                sendMessage(branch + " failed with " + rc.getResult() + " " + rc.getMessage());
            }
        } catch (final IOException e) {
            rc.setResult(Result.REJECTED_NOCREATE, "Couldn't create test branch for " + branch + " because " + e.getLocalizedMessage());
        } catch (final UpdateResultException updateResult) {
            interpretResult(rc, updateResult, rc.getRefName());
        }
    }

    private void checkBranchData(final String branch, final String testBranchName, final ReceiveCommand testRc) {
        try (final SourceChecker sc = getSourceChecker()) {
            sendMessage("Checking " + branch + " branch.");
            final List<Pair<Set<Ref>, List<Pair<FileObjectIdStore, Exception>>>> foundErrors = sc.checkTestBranchForErrors(testBranchName);
            // Only one branch
            final Pair<Set<Ref>, List<Pair<FileObjectIdStore, Exception>>> branchErrors = foundErrors.get(0);
            if (!branchErrors.getRight().isEmpty()) {
                final String[] message = CorruptedSourceException.compileMessage(foundErrors).split(System.lineSeparator());
                message[0] = message[0].replace(testBranchName, branch);
                for (String s : message) {
                    sendError(s);
                }
                testRc.setResult(Result.REJECTED_OTHER_REASON, message[0]);
            } else {
                sendMessage(branch + " OK!");
            }
        } catch (final IOException storageIsCorrupt) {
            sendError("Couldn't resolve " + branch + " because " + storageIsCorrupt.getLocalizedMessage());
            final Result result = (storageIsCorrupt instanceof MissingObjectException ? Result.REJECTED_MISSING_OBJECT
                    : Result.REJECTED_OTHER_REASON);
            testRc.setResult(result, storageIsCorrupt.getMessage());
            setFault(new RepositoryException(storageIsCorrupt.getMessage(), storageIsCorrupt));
        } catch (final Exception unexpected) {
            final String msg = "General error " + unexpected.getMessage();
            sendError(msg);
            testRc.setResult(Result.REJECTED_OTHER_REASON, msg);
            setFault(new RepositoryException(msg, unexpected));
        }
    }

    SourceChecker getSourceChecker() {
        return new SourceChecker(getRepository());
    }

    private void tryAndCommit(final List<Pair<ReceiveCommand, ReceiveCommand>> cmds, final ProgressMonitor monitor) {
        try {
            if (ifAllOk(cmds)) {
                commitCommands(cmds, monitor);
            }
        } finally {
            cleanUpRepository(cmds);
        }
    }

    private void signalReload(final List<Pair<ReceiveCommand, ReceiveCommand>> cmds) {
        final List<String> refsToUpdate = cmds.stream().filter(p -> p.getLeft().getResult() == Result.OK).map(p -> p.getLeft()).map(ref -> {
            final String refName = ref.getRefName();
            sendMessage("Reloading " + refName);
            return refName;
        }).collect(Collectors.toList());
        bus.process(refsToUpdate);
    }

    private void commitCommands(final List<Pair<ReceiveCommand, ReceiveCommand>> cmds, final ProgressMonitor monitor) {
        final Repository repository = getRepository();
        monitor.beginTask("Commiting branches", cmds.size());
        cmds.stream().map(cmd -> {
            final String refName = cmd.getLeft().getRefName();
            try {
                final Either<Exception, FailedToLock> lock = bus.getRefHolder(refName)
                        .lockWriteAll(() -> commitBranch(refName, cmd, repository, monitor));
                if (lock.isRight()) {
                    FailedToLock ftl = lock.getRight();
                    cmd.getLeft().setResult(Result.LOCK_FAILURE, ftl.getLocalizedMessage());
                    return ftl;
                }
                return lock.getLeft();
            } finally {
                monitor.endTask();
            }
        }).filter(Objects::nonNull).forEach(e -> sendError(e.getLocalizedMessage()));
    }

    private Exception commitBranch(final String refName, final Pair<ReceiveCommand, ReceiveCommand> p, final Repository repository,
            final ProgressMonitor monitor) {
        final ReceiveCommand orig = p.getLeft();
        final ReceiveCommand test = p.getRight();
        try {
            final Ref ref = repository.findRef(refName);
            checkForRef(orig, refName, ref);
            checkForBranchStaleness(orig, refName, ref);
            final RefUpdate updateRef = repository.updateRef(refName);
            updateRef.setRefLogMessage("JitStatic Git push", true);
            updateRef.setRefLogIdent(getRefLogIdent());
            updateRef.setPushCertificate(getPushCertificate());
            if (test == null) { // Deleted branch
                updateRef.setNewObjectId(orig.getNewId());
                if (!ObjectId.zeroId().equals(orig.getOldId()))
                    updateRef.setExpectedOldObjectId(orig.getOldId());
                updateRef.setForceUpdate(true);
                orig.setResult(updateRef.delete());
            } else {
                updateRef.setNewObjectId(test.getNewId());
                checkResult(refName, updateRef.forceUpdate());
                orig.setResult(test.getResult(), test.getMessage());
            }
            signalReload(List.of(p));
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
            final String msg = "Error while writing commit, repo is in an unknown state ";
            final RepositoryException repoException = new RepositoryException(msg, e);
            setFault(repoException);
            return repoException;
        } finally {
            monitor.update(1);
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

    private boolean ifAllOk(final List<Pair<ReceiveCommand, ReceiveCommand>> cmds) {
        return cmds.stream().allMatch(
                p -> (p.getRight() == null ? p.getLeft().getResult() == Result.NOT_ATTEMPTED : p.getRight().getResult() == Result.OK));
    }

    private void setFault(final Exception e) {
        errorReporter.setFault(e);
        LOG.error("Error occourred ", e);
    }

    private void cleanUpRepository(final List<Pair<ReceiveCommand, ReceiveCommand>> cmds) {
        cmds.stream().forEach(p -> deleteTempBranch(p.getRight()));
    }

    private void createTmpBranch(final String testBranchName, final String branch) throws IOException, UpdateResultException {
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
    }

    private void checkResult(final String testBranchName, final org.eclipse.jgit.lib.RefUpdate.Result result)
            throws IOException, UpdateResultException {
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

    private void deleteTempBranch(final ReceiveCommand rc) {
        if (rc == null) {
            return;
        }
        try {
            final RefUpdate ru = getRepository().updateRef(rc.getRefName());
            ru.disableRefLog();
            ru.setForceUpdate(true);
            checkResult(rc.getRefName(), ru.delete());
        } catch (final Exception e) {
            final String msg = "General error while deleting branches. Error is: " + e.getLocalizedMessage();
            setFault(new RepositoryException(msg, e));
            sendError(msg);
            sendError("Please clean up repository manually");
        }
    }
}
