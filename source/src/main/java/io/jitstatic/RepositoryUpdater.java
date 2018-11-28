package io.jitstatic;

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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.errors.UnmergedPathException;
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

import io.jitstatic.utils.Pair;

public class RepositoryUpdater {

    private final Repository repository;

    public RepositoryUpdater(final Repository repository) {
        this.repository = repository;
    }

    public List<Pair<String, ObjectId>> commit(final Ref ref, final CommitMetaData commitMetaData, final String method, final List<Pair<String, byte[]>> files)
            throws IOException, MissingObjectException, IncorrectObjectTypeException, CorruptObjectException, UnmergedPathException {
        final DirCache inCoreIndex = DirCache.newInCore();
        final DirCacheBuilder dirCacheBuilder = inCoreIndex.builder();
        final ObjectId headRef = ref.getObjectId();
        final List<Pair<String, ObjectId>> fileVersions = new ArrayList<>(files.size());
        try (final RevWalk rw = new RevWalk(repository); final ObjectInserter objectInserter = repository.newObjectInserter()) {
            final Set<String> filesAddedToTree = new HashSet<>(files.size());
            for (Pair<String, byte[]> pair : files) {
                if (pair.isPresent()) {
                    final ObjectId blob = createBlob(pair, dirCacheBuilder, objectInserter, filesAddedToTree);
                    fileVersions.add(Pair.of(pair.getLeft(), blob));
                }
            }
            buildTreeIndex(filesAddedToTree, rw, headRef, dirCacheBuilder);
            final ObjectId fullTree = inCoreIndex.writeTree(objectInserter);
            final PersonIdent commiter = new PersonIdent("JitStatic API " + method + " operation", "none@nowhere.org");
            final ObjectId inserted = buildCommit(ref, commitMetaData, objectInserter, fullTree, commiter);
            commitRef(ref, rw, commiter, inserted, method);
            return fileVersions;
        }
    }

    private ObjectId createBlob(final Pair<String, byte[]> file, final DirCacheBuilder dirCacheBuilder, final ObjectInserter objectInserter,
            final Set<String> filesAddedToTree) throws IOException {
        final String keyName = file.getLeft();
        final ObjectId blob = addBlob(keyName, file.getRight(), objectInserter, dirCacheBuilder);
        filesAddedToTree.add(keyName);
        return blob;
    }

    private void commitRef(final Ref ref, final RevWalk rw, final PersonIdent commiter, final ObjectId inserted, final String method)
            throws MissingObjectException, IncorrectObjectTypeException, IOException {
        final RevCommit newCommit = rw.parseCommit(inserted);
        final RefUpdate ru = repository.updateRef(ref.getName());
        ru.setRefLogIdent(commiter);
        ru.setNewObjectId(newCommit);
        ru.setForceRefLog(true);
        ru.setRefLogMessage("jitstatic " + method, true);
        ru.setExpectedOldObjectId(ref.getObjectId());
        checkResult(ru.update(rw), ref.getName());
    }

    private ObjectId buildCommit(final Ref ref, final CommitMetaData commitMetaData, final ObjectInserter objectInserter, final ObjectId fullTree,
            final PersonIdent commiter) throws IOException {
        final CommitBuilder commitBuilder = new CommitBuilder();
        commitBuilder.setAuthor(new PersonIdent(commitMetaData.getUserInfo(), (commitMetaData.getUserMail() != null ? commitMetaData.getUserMail() : "")));
        commitBuilder.setMessage(commitMetaData.getMessage());
        commitBuilder.setCommitter(commiter);
        commitBuilder.setTreeId(fullTree);
        commitBuilder.setParentId(ref.getObjectId());
        final ObjectId inserted = objectInserter.insert(commitBuilder);
        objectInserter.flush();
        return inserted;
    }

    private void buildTreeIndex(final Set<String> list, final RevWalk rw, final ObjectId headRef, final DirCacheBuilder dcBuilder)
            throws MissingObjectException, IncorrectObjectTypeException, CorruptObjectException, IOException {
        try (final TreeWalk treeWalk = new TreeWalk(repository)) {
            final int hIdx = treeWalk.addTree(rw.parseTree(headRef));
            treeWalk.setRecursive(true);
            while (treeWalk.next()) {
                final String entryPath = treeWalk.getPathString();
                final CanonicalTreeParser hTree = treeWalk.getTree(hIdx, CanonicalTreeParser.class);
                if (!list.contains(entryPath)) {
                    final DirCacheEntry dcEntry = new DirCacheEntry(entryPath);
                    dcEntry.setObjectId(hTree.getEntryObjectId());
                    dcEntry.setFileMode(hTree.getEntryFileMode());
                    dcBuilder.add(dcEntry);
                }
            }
        }
        dcBuilder.finish();
    }

    private ObjectId addBlob(final String fileEntry, final byte[] data, final ObjectInserter objectInserter, final DirCacheBuilder dcBuilder)
            throws IOException {
        final DirCacheEntry changedFileEntry = new DirCacheEntry(fileEntry);
        changedFileEntry.setLength(data.length);
        changedFileEntry.setLastModified(System.currentTimeMillis());
        changedFileEntry.setFileMode(FileMode.REGULAR_FILE);
        changedFileEntry.setObjectId(objectInserter.insert(Constants.OBJ_BLOB, data));
        final ObjectId blob = changedFileEntry.getObjectId();
        dcBuilder.add(changedFileEntry);
        return blob;
    }

    private void checkResult(final Result update, final String ref) {
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
            throw new UpdateFailedException(update, ref);
        }
    }

    public void deleteKeys(final Set<String> filesToDelete, final Ref ref, final CommitMetaData commitMetaData) throws IOException {
        final String method = "delete";
        final DirCache inCoreIndex = DirCache.newInCore();
        final DirCacheBuilder dirCacheBuilder = inCoreIndex.builder();
        final ObjectId headRef = ref.getObjectId();
        try (final RevWalk rw = new RevWalk(repository); final ObjectInserter objectInserter = repository.newObjectInserter()) {
            buildTreeIndex(filesToDelete, rw, headRef, dirCacheBuilder);
            final ObjectId fullTree = inCoreIndex.writeTree(objectInserter);
            final PersonIdent commiter = new PersonIdent("JitStatic API " + method + " operation", "none@nowhere.org");
            final ObjectId inserted = buildCommit(ref, commitMetaData, objectInserter, fullTree, commiter);
            commitRef(ref, rw, commiter, inserted, method);
            rw.dispose();
        }
    }

    public void createRef(final String baseRef, final String finalRef) throws IOException {
        final Ref existingRef = repository.findRef(finalRef);
        if (existingRef == null) {
            final RefUpdate updateRef = repository.updateRef(finalRef);
            ObjectId base = repository.resolve(baseRef);
            if (base == null) {
                base = ObjectId.zeroId();
            }
            updateRef.setExpectedOldObjectId(ObjectId.zeroId());
            updateRef.setNewObjectId(base);
            updateRef.setForceUpdate(true);
            updateRef.disableRefLog();
            checkResult(updateRef.forceUpdate(), finalRef);
        }
    }

    public void deleteRefs(List<String> refs) throws IOException {
        for (String ref : refs) {
            final RefUpdate ru = repository.updateRef(ref);
            ru.setForceUpdate(true);
            checkResult(ru.delete(), ref);
        }
    }

    public Repository getRepository() {
        return repository;
    }
}
