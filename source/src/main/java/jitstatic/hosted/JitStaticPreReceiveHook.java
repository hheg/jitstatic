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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.PreReceiveHook;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceiveCommand.Result;
import org.eclipse.jgit.transport.ReceivePack;

import jitstatic.CorruptedSourceException;
import jitstatic.JitStaticConstants;
import jitstatic.SourceChecker;
import jitstatic.util.Pair;

class JitStaticPreReceiveHook implements PreReceiveHook {

	private static final Logger LOG = LogManager.getLogger(JitStaticPreReceiveHook.class);
	private final AtomicReference<Exception> fault = new AtomicReference<>();
	private final String defaultRef;

	public JitStaticPreReceiveHook(final String defaultRef) {
		this.defaultRef = Objects.requireNonNull(defaultRef);
	}

	@Override
	public void onPreReceive(final ReceivePack rp, final Collection<ReceiveCommand> commands) {
		Objects.requireNonNull(rp);
		final Repository repository = rp.getRepository();
		final List<Pair<ReceiveCommand, ReceiveCommand>> cmds = new ArrayList<>();
		for (final ReceiveCommand rc : Objects.requireNonNull(commands)) {
			if (!ObjectId.equals(ObjectId.zeroId(), rc.getNewId())) {
				final String branch = rc.getRefName();
				final String testBranchName = JitStaticConstants.REF_JISTSTATIC + UUID.randomUUID();
				try {
					createTmpBranch(repository, testBranchName, branch);

					final ReceiveCommand testRc = new ReceiveCommand(rc.getOldId(), rc.getNewId(), testBranchName,
							rc.getType());
					testRc.execute(rp);
					cmds.add(new Pair<>(rc, testRc));
					if (testRc.getResult() == Result.OK) {
						checkBranchData(rp, repository, branch, testBranchName, testRc);
					}
					if (testRc.getResult() != Result.OK) {
						rc.setResult(testRc.getResult(), testRc.getMessage());
						rp.sendMessage(branch + " failed with " + rc.getResult() + " "+ rc.getMessage());
					}
				} catch (final IOException e) {
					rc.setResult(Result.REJECTED_NOCREATE,
							"Couldn't create test branch for " + branch + " because " + e.getMessage());
				}
			} else if (rc.getRefName().equals(defaultRef)) {
				rc.setResult(Result.REJECTED_NODELETE, "Cannot delete default branch " + defaultRef);
			}
		}
		tryAndCommit(rp, repository, cmds);
	}

	private void checkBranchData(final ReceivePack rp, final Repository repository, final String branch,
			final String testBranchName, final ReceiveCommand testRc) {
		try (final SourceChecker sc = new SourceChecker(repository)) {
			rp.sendMessage("Checking " + branch + " branch.");
			final List<Pair<Set<Ref>, List<Pair<FileObjectIdStore, Exception>>>> check = sc
					.checkTestBranchForErrors(testBranchName);
			if (!check.get(0).getRight().isEmpty()) {
				final String[] message = CorruptedSourceException.compileMessage(check).split(System.lineSeparator());
				message[0] = message[0].replace(testBranchName, branch);
				for (String s : message) {
					rp.sendError(s);
				}
				testRc.setResult(Result.REJECTED_OTHER_REASON, message[0]);
			} else {
				rp.sendMessage(branch + " OK!");
			}
		} catch (final IOException storageIsCorrupt) {
			rp.sendError("Couldn't resolve " + branch + " because " + storageIsCorrupt.getMessage());
			final Result r = (storageIsCorrupt instanceof MissingObjectException ? Result.REJECTED_MISSING_OBJECT
					: Result.REJECTED_OTHER_REASON);
			testRc.setResult(r, storageIsCorrupt.getMessage());
			LOG.error(storageIsCorrupt.getMessage(), storageIsCorrupt);
		} catch (final Exception unexpected) {
			rp.sendError("General error " + unexpected.getMessage());
			testRc.setResult(Result.REJECTED_OTHER_REASON, unexpected.getMessage());
			LOG.error(unexpected.getMessage(), unexpected);
		}
	}

	private void tryAndCommit(final ReceivePack rp, final Repository repository,
			final List<Pair<ReceiveCommand, ReceiveCommand>> cmds) {
		try {
			final boolean allOK = cmds.stream().allMatch(p -> p.getRight().getResult() == Result.OK);
			if (allOK) {
				cmds.stream().forEach(p -> {
					final ReceiveCommand orig = p.getLeft();
					final ReceiveCommand test = p.getRight();
					final String refName = orig.getRefName();
					try {
						final RefUpdate updateRef = repository.updateRef(refName);
						updateRef.setForceUpdate(true);
						updateRef.setNewObjectId(test.getNewId());
						checkResult(refName, updateRef.forceUpdate());
						orig.setResult(test.getResult(), test.getMessage());
					} catch (final IOException e) {
						final Exception repoException = new Exception(
								"Error while writing commit, repo is in unknown state ", e);
						setFault(repoException);
						LOG.error("Error while writing commit, repo is in unknown state " + e.getMessage(), e);
						rp.sendError("Error while writing commit, repo is in unknown state " + e.getMessage());
					}
				});
			}
		} finally {
			cleanUpRepository(rp, repository, cmds);
		}
	}

	private void setFault(Exception e) {
		final Exception unregistered = fault.getAndSet(e);
		if (unregistered != null) {
			LOG.error("Unregistered error ", e);
		}
	}

	private void cleanUpRepository(final ReceivePack rp, final Repository repository,
			final List<Pair<ReceiveCommand, ReceiveCommand>> cmds) {
		cmds.stream().forEach(p -> deleteTempBranch(rp, p.getRight(), repository));
	}

	private void createTmpBranch(final Repository repository, final String testBranchName, final String branch)
			throws IOException {
		final RefUpdate updateRef = repository.updateRef(testBranchName);
		ObjectId base = repository.resolve(branch);
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

	private void deleteTempBranch(final ReceivePack rp, final ReceiveCommand rc, final Repository repository) {
		try {
			final RefUpdate ru = repository.updateRef(rc.getRefName());
			ru.disableRefLog();
			ru.setForceUpdate(true);
			checkResult(rc.getRefName(), ru.delete());
		} catch (final Exception e) {
			LOG.error("General error " + e.getMessage(), e);
			rp.sendError("General error " + e.getMessage());
			rp.sendError("Please clean up repository manually");
		}
	}

	public Exception getFault() {
		return fault.getAndSet(null);
	}
}
