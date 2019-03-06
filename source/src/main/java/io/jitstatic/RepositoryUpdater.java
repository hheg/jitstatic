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
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEditor;
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
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.RefUpdate.Result;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.jitstatic.source.ObjectStreamProvider;
import io.jitstatic.utils.Pair;
// TODO Remove this SpotBugs Error
@SuppressFBWarnings(value="RCN_REDUNDANT_NULLCHECK_WOULD_HAVE_BEEN_A_NPE",justification="This is a false positive in Java 11, should be removed")
public class RepositoryUpdater {

    private final Repository repository;

    public RepositoryUpdater(final Repository repository) {
        this.repository = repository;
    }
    
    public List<Pair<String, ObjectId>> commit(final Ref ref, final CommitMetaData commitMetaData, final String method,
            final List<Pair<String, ObjectStreamProvider>> files)
            throws IOException, MissingObjectException, IncorrectObjectTypeException, CorruptObjectException, UnmergedPathException {        
        final List<Pair<String, ObjectId>> fileVersions = new ArrayList<>(files.size());
        try (final RevWalk rw = new RevWalk(repository); final ObjectInserter objectInserter = repository.newObjectInserter()) {
            final DirCache inCoreIndex = getCurrentDirCache(rw, ref);
            final DirCacheEditor editor = inCoreIndex.editor();
            for (Pair<String, ObjectStreamProvider> pair : files) {
                if (pair.isPresent()) {
                    final String keyName = pair.getLeft();
                    final ObjectStreamProvider data = pair.getRight();
                    try (InputStream is = data.getInputStream()) {
                        final ObjectId blobId = objectInserter.insert(Constants.OBJ_BLOB, data.getSize(), is);
                        editor.add(new DirCacheEditor.PathEdit(keyName) {
                            @Override
                            public void apply(DirCacheEntry ent) {
                                ent.setFileMode(FileMode.REGULAR_FILE);                                
                                ent.setObjectId(blobId);
                            }
                        });
                        fileVersions.add(Pair.of(pair.getLeft(), blobId));
                    }
                }
            }
            editor.finish();
            final ObjectId fullTree = inCoreIndex.writeTree(objectInserter);
            final PersonIdent commiter = new PersonIdent(commitMetaData.getProxyUser(), commitMetaData.getProxyUserMail());
            buildCommit(ref, commitMetaData, method, rw, objectInserter, fullTree, commiter);
            return fileVersions;
        }
    }

    private DirCache getCurrentDirCache(final RevWalk rw, Ref head) throws MissingObjectException, IncorrectObjectTypeException, IOException {
        RevCommit revision = head.getObjectId() != null ? rw.parseCommit(head.getObjectId()) : null;
        RevTree tree = revision != null ? rw.parseTree(revision) : null;
        return getIndex(tree);
    }
    
    private DirCache getIndex(RevTree tree) throws IOException {
        if(tree == null) {
            return DirCache.newInCore();
        }
        try (ObjectReader reader = repository.newObjectReader()) {
            return DirCache.read(reader, tree);
        }
    }

    private void buildCommit(final Ref ref, final CommitMetaData commitMetaData, final String method, final RevWalk rw, final ObjectInserter objectInserter,
            final ObjectId fullTree, final PersonIdent commiter) throws IOException, MissingObjectException, IncorrectObjectTypeException {
        final CommitBuilder commitBuilder = new CommitBuilder();
        commitBuilder.setAuthor(new PersonIdent(commitMetaData.getUserInfo(), (commitMetaData.getUserMail() != null ? commitMetaData.getUserMail() : "")));
        commitBuilder.setMessage(commitMetaData.getMessage());
        commitBuilder.setCommitter(commiter);
        commitBuilder.setTreeId(fullTree);
        commitBuilder.setParentId(ref.getObjectId());
        final ObjectId insertedCommit = objectInserter.insert(commitBuilder);
        objectInserter.flush();
        updateRef(ref, method, rw, commiter, insertedCommit);
    }

    private void updateRef(final Ref ref, final String method, final RevWalk rw, final PersonIdent commiter, final ObjectId insertedCommit)
            throws MissingObjectException, IncorrectObjectTypeException, IOException {
        final RevCommit newCommit = rw.parseCommit(insertedCommit);
        final RefUpdate ru = repository.updateRef(ref.getName());
        ru.setRefLogIdent(commiter);
        ru.setNewObjectId(newCommit);
        ru.setForceRefLog(false);
        ru.setRefLogMessage("jitstatic " + method, true);
        ru.setExpectedOldObjectId(ref.getObjectId());
        checkResult(ru.update(rw), ref.getName());
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
        try (final RevWalk rw = new RevWalk(repository); final ObjectInserter objectInserter = repository.newObjectInserter()) {
            final DirCache inCoreIndex = getCurrentDirCache(rw, ref);
            final DirCacheEditor editor = inCoreIndex.editor();
            for (String file : filesToDelete) {
                editor.add(new DirCacheEditor.DeletePath(file));
            }
            editor.finish();
            final ObjectId fullTree = inCoreIndex.writeTree(objectInserter);
            final PersonIdent commiter = new PersonIdent(commitMetaData.getProxyUser(),commitMetaData.getProxyUserMail());
            buildCommit(ref, commitMetaData, method, rw, objectInserter, fullTree, commiter);
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
