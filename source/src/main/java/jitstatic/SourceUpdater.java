package jitstatic;

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

import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.lib.RefUpdate.Result;
import org.eclipse.jgit.revwalk.RevWalk;

public class SourceUpdater {

	private final Repository repository;

	public SourceUpdater(final Repository repository) {
		this.repository = repository;
	}

	public String updateKey(final String key, final Ref ref, final byte[] data, final String message, final String userInfo,
			final String userMail) throws IOException {
		try (final RevWalk rw = new RevWalk(repository)) {
			ObjectInserter objectInserter = repository.newObjectInserter();
			final ObjectId blob = objectInserter.insert(Constants.OBJ_BLOB, data);

			final String[] path = key.split("/");

			FileMode mode = FileMode.REGULAR_FILE;
			objectInserter = repository.newObjectInserter();
			ObjectId id = blob;

			for (int i = path.length - 1; i >= 0; --i) {
				final TreeFormatter treeFormatter = new TreeFormatter();
				treeFormatter.append(path[i], mode, id);
				id = objectInserter.insert(treeFormatter);
				mode = FileMode.TREE;
			}

			final CommitBuilder commitBuilder = new CommitBuilder();
			commitBuilder.setAuthor(new PersonIdent(userInfo, (userMail != null ? userMail : "")));

			commitBuilder.setMessage(message);
			// TODO fix this
			PersonIdent commiter = new PersonIdent("JitStatic API put operation", "none@nowhere.org");
			commitBuilder.setCommitter(commiter);
			commitBuilder.setTreeId(id);
			commitBuilder.setParentId(ref.getObjectId());
			final ObjectId inserted = objectInserter.insert(commitBuilder);
			objectInserter.flush();

			rw.parseCommit(inserted);

			final RefUpdate ru = repository.updateRef(ref.getName());
			ru.setRefLogIdent(commiter);
			ru.setNewObjectId(inserted);
			ru.setForceRefLog(true);
			ru.setRefLogMessage("jitstatic modify", false);
			ru.setExpectedOldObjectId(ref.getObjectId());
			checkResult(ru.update(rw));
			return blob.name();
		}
	}

	private void checkResult(Result update) {
		switch (update) {
		case FAST_FORWARD:
		case FORCED:
		case NEW:
		case NO_CHANGE:
			break;
		case IO_FAILURE:
		case LOCK_FAILURE:
		case NOT_ATTEMPTED:
		case REJECTED:
		case REJECTED_CURRENT_BRANCH:
		case REJECTED_MISSING_OBJECT:
		case REJECTED_OTHER_REASON:
		case RENAMED:
		default:
			throw new UpdateFailedException(update);
		}
	}
}
