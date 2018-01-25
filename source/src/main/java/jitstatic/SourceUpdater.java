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
import java.time.Instant;

import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RefUpdate.Result;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.TreeWalk;

public class SourceUpdater {

	private final Repository repository;

	public SourceUpdater(final Repository repository) {
		this.repository = repository;
	}

	public String updateKey(final String key, final Ref ref, final byte[] data, final String message, final String userInfo,
			final String userMail) throws IOException {
		final DirCache inCoreIndex = DirCache.newInCore();
		try (final RevWalk rw = new RevWalk(repository); final ObjectInserter objectInserter = repository.newObjectInserter();) {
			final ObjectId headRef = ref.getObjectId();
			final DirCacheBuilder dirCacheBuilder = inCoreIndex.builder();
			final ObjectId blob = addBlob(key, data, objectInserter, dirCacheBuilder);
			
			buildTreeIndex(key, rw, headRef, dirCacheBuilder);
			
			final ObjectId fullTree = inCoreIndex.writeTree(objectInserter);
			// TODO fix this
			final PersonIdent commiter = new PersonIdent("JitStatic API put operation", "none@nowhere.org");
			final ObjectId inserted = buildCommit(ref, message, userInfo, userMail, objectInserter, fullTree, commiter);

			insertCommit(ref, rw, commiter, inserted);
			return blob.name();
		}
	}

	private void insertCommit(final Ref ref, final RevWalk rw, final PersonIdent commiter, final ObjectId inserted)
			throws MissingObjectException, IncorrectObjectTypeException, IOException {
		final RevCommit newCommit = rw.parseCommit(inserted);
		final RefUpdate ru = repository.updateRef(ref.getName());
		ru.setRefLogIdent(commiter);
		ru.setNewObjectId(newCommit);
		ru.setForceRefLog(true);
		ru.setRefLogMessage("jitstatic modify", false);
		ru.setExpectedOldObjectId(ref.getObjectId());
		checkResult(ru.update(rw));
	}

	private ObjectId buildCommit(final Ref ref, final String message, final String userInfo, final String userMail,
			final ObjectInserter objectInserter, final ObjectId fullTree, final PersonIdent commiter) throws IOException {
		final CommitBuilder commitBuilder = new CommitBuilder();
		commitBuilder.setAuthor(new PersonIdent(userInfo, (userMail != null ? userMail : "")));
		commitBuilder.setMessage(message);
		commitBuilder.setCommitter(commiter);
		commitBuilder.setTreeId(fullTree);
		commitBuilder.setParentId(ref.getObjectId());
		final ObjectId inserted = objectInserter.insert(commitBuilder);
		objectInserter.flush();
		return inserted;
	}

	private void buildTreeIndex(final String key, final RevWalk rw, final ObjectId headRef, final DirCacheBuilder dcBuilder)
			throws MissingObjectException, IncorrectObjectTypeException, CorruptObjectException, IOException {
		try (final TreeWalk treeWalk = new TreeWalk(repository);) {
			final int hIdx = treeWalk.addTree(rw.parseTree(headRef));
			treeWalk.setRecursive(true);	
			while (treeWalk.next()) {
				final String entryPath = treeWalk.getPathString();
				final CanonicalTreeParser hTree = treeWalk.getTree(hIdx, CanonicalTreeParser.class);
				if (!entryPath.equals(key)) {
					final DirCacheEntry dcEntry = new DirCacheEntry(entryPath);
					dcEntry.setObjectId(hTree.getEntryObjectId());
					dcEntry.setFileMode(hTree.getEntryFileMode());
					dcBuilder.add(dcEntry);
				}
			}
		}
		dcBuilder.finish();
	}

	private ObjectId addBlob(final String key, final byte[] data, final ObjectInserter objectInserter, final DirCacheBuilder dcBuilder)
			throws IOException {
		final DirCacheEntry changedFileEntry = new DirCacheEntry(key);
		changedFileEntry.setLength(data.length);
		changedFileEntry.setLastModified(Instant.now().getEpochSecond());
		changedFileEntry.setFileMode(FileMode.REGULAR_FILE);
		changedFileEntry.setObjectId(objectInserter.insert(Constants.OBJ_BLOB, data));
		final ObjectId blob = changedFileEntry.getObjectId();
		dcBuilder.add(changedFileEntry);
		return blob;
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
