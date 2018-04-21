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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceiveCommand.Result;
import org.eclipse.jgit.transport.ReceivePack;

import com.spencerwi.either.Either;

import io.jitstatic.CorruptedSourceException;
import io.jitstatic.FileObjectIdStore;
import io.jitstatic.JitStaticConstants;
import io.jitstatic.SourceChecker;
import io.jitstatic.utils.Pair;

public class JitStaticReceivePack extends ReceivePack {

    private static final Logger LOG = LogManager.getLogger(JitStaticReceivePack.class);
    private final AtomicReference<Exception> fault = new AtomicReference<>();
    private final String defaultRef;
    private final ExecutorService repoExecutor;
    private final RepositoryBus bus;

    public JitStaticReceivePack(final Repository into, final String defaultRef, final ExecutorService service, final ErrorReporter errorReporter,
            final RepositoryBus bus) {
        super(into);
        this.defaultRef = Objects.requireNonNull(defaultRef);
        this.bus = Objects.requireNonNull(bus);
        this.repoExecutor = Objects.requireNonNull(service);
    }

    // TODO do the checks the original code does
    @Override
    protected void executeCommands() {
        final List<ReceiveCommand> commands = filterCommands(Result.NOT_ATTEMPTED);
        final List<Pair<ReceiveCommand, ReceiveCommand>> cmdsToBeExecuted = new ArrayList<>(commands.size());
        for (final ReceiveCommand rc : Objects.requireNonNull(commands)) {
            if (!ObjectId.equals(ObjectId.zeroId(), rc.getNewId())) {
                checkBranch(cmdsToBeExecuted, rc);
            } else {
                if (rc.getRefName().equals(defaultRef)) {
                    rc.setResult(Result.REJECTED_NODELETE, "Cannot delete default branch " + defaultRef);
                }
                cmdsToBeExecuted.add(Pair.of(rc, null)); // Deleted branches
            }
        }
        tryAndCommit(cmdsToBeExecuted);
    }

    private void checkBranch(final List<Pair<ReceiveCommand, ReceiveCommand>> cmdsToBeExecuted, final ReceiveCommand rc) {
        final String branch = rc.getRefName();
        final String testBranchName = JitStaticConstants.REFS_JISTSTATIC + UUID.randomUUID();
        try {
            createTmpBranch(testBranchName, branch);
            final ReceiveCommand testRc = new ReceiveCommand(rc.getOldId(), rc.getNewId(), testBranchName, rc.getType());
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
            final Result result = (storageIsCorrupt instanceof MissingObjectException ? Result.REJECTED_MISSING_OBJECT : Result.REJECTED_OTHER_REASON);
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

    private void tryAndCommit(final List<Pair<ReceiveCommand, ReceiveCommand>> cmds) {
        try {
            if (ifAllOk(cmds)) {
                commitCommands(cmds);
                signalReload(cmds);
            }
        } catch (final InterruptedException | ExecutionException e) {
            setFault(new RepositoryException("Writing to repo was interrupted", e));
        } finally {
            cleanUpRepository(cmds);
        }
    }

    private void signalReload(List<Pair<ReceiveCommand, ReceiveCommand>> cmds) {
        final List<String> refsToUpdate = cmds.stream().filter(p -> p.getLeft().getResult() == Result.OK).map(p -> p.getLeft().getRefName())
                .collect(Collectors.toList());
        bus.process(refsToUpdate);
    }

    private void commitCommands(final List<Pair<ReceiveCommand, ReceiveCommand>> cmds) throws InterruptedException, ExecutionException {
        final Repository repository = getRepository();
        repoExecutor.submit(() -> {
            return cmds.stream().map(p -> {
                final ReceiveCommand orig = p.getLeft();
                final ReceiveCommand test = p.getRight();
                try {
                    final String refName = orig.getRefName();
                    final Ref ref = repository.findRef(refName);
                    if (!ObjectId.equals(orig.getOldId(), ObjectId.zeroId()) && ref == null) {
                        throw new RefNotFoundException(refName);
                    }
                    if (ref != null && !ObjectId.equals(ref.getObjectId(), orig.getOldId())) {
                        throw new CommandIsStale(refName, orig.getOldId(), ref.getObjectId());
                    }
                    final RefUpdate updateRef = repository.updateRef(refName);
                    updateRef.setRefLogMessage("JitStatic Git push", true);
                    updateRef.setRefLogIdent(getRefLogIdent());
                    updateRef.setPushCertificate(getPushCertificate());
                    if (test == null) { // Deleted branch
                        updateRef.setNewObjectId(orig.getNewId());
                        orig.setResult(updateRef.forceUpdate());
                    } else {
                        updateRef.setNewObjectId(test.getNewId());
                        checkResult(refName, updateRef.forceUpdate());
                        orig.setResult(test.getResult(), test.getMessage());
                    }
                    return Either.<Exception, Void>right(null);
                } catch (final CommandIsStale e1) {
                    orig.setResult(Result.REJECTED_NONFASTFORWARD, e1.getLocalizedMessage());
                    return Either.<Exception, Void>left(e1);
                } catch (final RefNotFoundException e1) {
                    orig.setResult(Result.REJECTED_MISSING_OBJECT, e1.getLocalizedMessage());
                    return Either.<Exception, Void>left(e1);
                } catch (final IOException e) {
                    orig.setResult(Result.REJECTED_OTHER_REASON, e.getLocalizedMessage());
                    final String msg = "Error while writing commit, repo is in an unknown state ";
                    final RepositoryException repoException = new RepositoryException(msg, e);
                    setFault(repoException);
                    return Either.<Exception, Void>left(repoException);
                }
            }).filter(Either::isLeft).collect(Collectors.toList());
        }).get().stream().forEach(e -> sendError(e.getLeft().getLocalizedMessage()));
    }

    private boolean ifAllOk(final List<Pair<ReceiveCommand, ReceiveCommand>> cmds) {
        return cmds.stream().allMatch(p -> {
            if (p.getRight() == null) {
                return p.getLeft().getResult() == Result.NOT_ATTEMPTED;
            }
            return p.getRight().getResult() == Result.OK;
        });
    }

    private void setFault(final Exception e) {
        final Exception unregistered = fault.getAndSet(e);
        if (unregistered != null) {
            LOG.error("Unregistered error ", unregistered);
        }
    }

    private void cleanUpRepository(final List<Pair<ReceiveCommand, ReceiveCommand>> cmds) {
        cmds.stream().forEach(p -> deleteTempBranch(p.getRight()));
    }

    private void createTmpBranch(final String testBranchName, final String branch) throws IOException {
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

    private void checkResult(final String testBranchName, final org.eclipse.jgit.lib.RefUpdate.Result result) throws IOException {
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
            final String msg = "General error while deleting branches " + e.getLocalizedMessage();
            setFault(new RepositoryException(msg, e));
            sendError(msg);
            sendError("Please clean up repository manually");
        }
    }

    public Exception getFault() {
        return fault.getAndSet(null);
    }

}