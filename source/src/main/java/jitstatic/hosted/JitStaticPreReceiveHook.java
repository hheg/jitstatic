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
import java.util.Collection;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.PreReceiveHook;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceiveCommand.Result;
import org.eclipse.jgit.transport.ReceivePack;

public class JitStaticPreReceiveHook implements PreReceiveHook {

	private static final Logger LOG = LogManager.getLogger(JitStaticPreReceiveHook.class);

	private final String store;
	private final String branch;

	public JitStaticPreReceiveHook(final String store, final String branch) {
		this.store = store;
		this.branch = branch;
	}

	@Override
	public void onPreReceive(final ReceivePack rp, final Collection<ReceiveCommand> commands) {
		final Optional<ReceiveCommand> cmd = commands.stream().filter(rc -> branch.equals(rc.getRefName())).findFirst();

		if (cmd.isPresent()) {
			final ReceiveCommand rc = cmd.get();

			rp.sendMessage("Reading " + branch + "...");
			rc.execute(rp);

			if (rc.getResult() != Result.OK) {
				return;
			}
			final Repository repository = rp.getRepository();
			try (StorageChecker sc = new StorageChecker(repository)) {
				rp.sendMessage("Checking " + store + "...");
				sc.check(store, branch);
				rp.sendMessage("Storage " + store + " OK!");
			} catch (final IOException storageIsCorrupt) {
				rp.sendError("Couldn't resolve " + branch + " because " + storageIsCorrupt.getMessage());
				final Result r = (storageIsCorrupt instanceof MissingObjectException ? Result.REJECTED_MISSING_OBJECT
						: Result.REJECTED_OTHER_REASON);
				rc.setResult(r, storageIsCorrupt.getMessage());
				LOG.error(storageIsCorrupt.getMessage(), storageIsCorrupt);
			} catch (final Exception unexpected) {
				rp.sendError("General error " + unexpected.getMessage());
				rc.setResult(Result.REJECTED_OTHER_REASON, unexpected.getMessage());
				LOG.error(unexpected.getMessage(), unexpected);
			}
			if (rc.getResult() != Result.OK) {
				rollbackCommit(rp, rc, repository);
			} else {
				rp.sendMessage(branch +" OK!");
			}
		} else {
			rp.sendMessage("Branch " + branch + " is not present in this push. Not checking.");
		}
	}

	private void rollbackCommit(final ReceivePack rp, final ReceiveCommand rc, final Repository repository) {
		try {
			rp.sendMessage("Reverting commit " + rc.getNewId().getName() + "...");
			final RefUpdate ru = repository.updateRef(rc.getRefName());
			ru.disableRefLog();
			ru.setExpectedOldObjectId(rc.getNewId());
			ru.setNewObjectId(rc.getOldId());
			ru.setForceUpdate(true);
			org.eclipse.jgit.lib.RefUpdate.Result result = ru.update();
			rp.sendMessage("Reverted operation completed with " + result);
		} catch (IOException e) {
			rp.sendError("General error " + e.getMessage());
			rp.sendError("Please clean up repository manually");
		}
	}
}
