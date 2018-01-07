package jitstatic.hosted;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceiveCommand.Result;
import org.eclipse.jgit.transport.ReceivePack;

import com.spencerwi.either.Either;

import jitstatic.CorruptedSourceException;
import jitstatic.FileObjectIdStore;
import jitstatic.JitStaticConstants;
import jitstatic.SourceChecker;
import jitstatic.util.Pair;

public class JitstaticReceivePack extends ReceivePack {

	private static final Logger LOG = LogManager.getLogger(JitstaticReceivePack.class);
	private final AtomicReference<Exception> fault = new AtomicReference<>();
	private final String defaultRef;
	private final ExecutorService repoExecutor;

	public JitstaticReceivePack(final Repository into, final String defaultRef, ExecutorService service, ErrorReporter errorReporter) {
		super(into);
		this.defaultRef = Objects.requireNonNull(defaultRef);
		this.repoExecutor = Executors.newSingleThreadExecutor();
	}
	// TODO do the checks the original code does
	@Override
	protected void executeCommands() {
		final List<ReceiveCommand> commands = filterCommands(Result.NOT_ATTEMPTED);
		final List<Pair<ReceiveCommand, ReceiveCommand>> cmdsToBeExecuted = new ArrayList<>();
		for (final ReceiveCommand rc : Objects.requireNonNull(commands)) {
			if (!ObjectId.equals(ObjectId.zeroId(), rc.getNewId())) {
				final String branch = rc.getRefName();
				final String testBranchName = JitStaticConstants.REF_JISTSTATIC + UUID.randomUUID();
				try {
					createTmpBranch(testBranchName, branch);
					final ReceiveCommand testRc = new ReceiveCommand(rc.getOldId(), rc.getNewId(), testBranchName,
							rc.getType());
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
					rc.setResult(Result.REJECTED_NOCREATE,
							"Couldn't create test branch for " + branch + " because " + e.getMessage());
				}
			} else {
				if (rc.getRefName().equals(defaultRef)) {
					rc.setResult(Result.REJECTED_NODELETE, "Cannot delete default branch " + defaultRef);
				}
				cmdsToBeExecuted.add(Pair.of(rc, null));
			}
		}
		tryAndCommit(cmdsToBeExecuted);
	}

	private void checkBranchData(final String branch, final String testBranchName, final ReceiveCommand testRc) {
		try (final SourceChecker sc = new SourceChecker(getRepository())) {
			sendMessage("Checking " + branch + " branch.");
			final List<Pair<Set<Ref>, List<Pair<FileObjectIdStore, Exception>>>> foundErrors = sc
					.checkTestBranchForErrors(testBranchName);
			// Only one branch
			final Pair<Set<Ref>, List<Pair<FileObjectIdStore, Exception>>> branchErrors = foundErrors.get(0);
			if (!branchErrors.getRight().isEmpty()) {
				final String[] message = CorruptedSourceException.compileMessage(foundErrors)
						.split(System.lineSeparator());
				message[0] = message[0].replace(testBranchName, branch);
				for (String s : message) {
					sendError(s);
				}
				testRc.setResult(Result.REJECTED_OTHER_REASON, message[0]);
			} else {
				sendMessage(branch + " OK!");
			}
		} catch (final IOException storageIsCorrupt) {
			sendError("Couldn't resolve " + branch + " because " + storageIsCorrupt.getMessage());
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
		// TODO Auto-generated method stub
		
	}
	private void commitCommands(final List<Pair<ReceiveCommand, ReceiveCommand>> cmds)
			throws InterruptedException, ExecutionException {
		final Repository repository = getRepository();
		repoExecutor.submit(() -> {
			return cmds.stream().map(p -> {
				try {
					final ReceiveCommand orig = p.getLeft();
					final ReceiveCommand test = p.getRight();
					final String refName = orig.getRefName();
					final RefUpdate updateRef = repository.updateRef(refName);
					updateRef.setForceUpdate(true);
					updateRef.setRefLogMessage("push", true);
					updateRef.setRefLogIdent(getRefLogIdent());
					updateRef.setPushCertificate(getPushCertificate());
					if (test == null) {
						updateRef.setNewObjectId(orig.getNewId());
						orig.setResult(updateRef.forceUpdate());
					} else {
						updateRef.setNewObjectId(test.getNewId());
						checkResult(refName, updateRef.forceUpdate());
						orig.setResult(test.getResult(), test.getMessage());
					}
					return Either.<Exception, Void>right(null);
				} catch (final IOException e) {
					final String msg = "Error while writing commit, repo is in unknown state ";
					final RepositoryException repoException = new RepositoryException(msg, e);
					setFault(repoException);
					return Either.<Exception, Void>left(repoException);
				}
			});
		}).get().filter(Either::isLeft).forEach(e -> sendError(e.getLeft().getMessage()));
	}

	private boolean ifAllOk(final List<Pair<ReceiveCommand, ReceiveCommand>> cmds) {
		final boolean allOK = cmds.stream().allMatch(p -> {
			if (p.getRight() == null) {
				if(p.getLeft().getResult() != Result.NOT_ATTEMPTED) {
					return false;
				}
				return true;
			}
			return p.getRight().getResult() == Result.OK;
		});
		return allOK;
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
			final String msg = "General error while deleting branches " + e.getMessage();
			setFault(new RepositoryException(msg, e));
			sendError(msg);
			sendError("Please clean up repository manually");
		}
	}

	public Exception getFault() {
		return fault.getAndSet(null);
	}

}
