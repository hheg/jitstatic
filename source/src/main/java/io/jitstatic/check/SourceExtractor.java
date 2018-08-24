package io.jitstatic.check;

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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;
import org.eclipse.jgit.treewalk.filter.TreeFilter;

import io.jitstatic.JitStaticConstants;
import io.jitstatic.hosted.InputStreamHolder;
import io.jitstatic.source.SourceInfo;
import io.jitstatic.utils.Pair;
import io.jitstatic.utils.Path;

public class SourceExtractor {

    private static final int METADATA_LENGTH = JitStaticConstants.METADATA.length();
    private final Repository repository;

    public SourceExtractor(final Repository repository) {
        this.repository = repository;
    }

    public SourceInfo openTag(final String tagName, final String key) throws RefNotFoundException, IOException {
        if (!Objects.requireNonNull(tagName).startsWith(Constants.R_TAGS)) {
            throw new RefNotFoundException(tagName);
        }
        return sourceExtractor(tagName, key);
    }

    public SourceInfo openBranch(final String branchName, final String key) throws RefNotFoundException, IOException {
        if (!Objects.requireNonNull(branchName).startsWith(Constants.R_HEADS)) {
            throw new RefNotFoundException(branchName);
        }
        return sourceExtractor(branchName, key);
    }

    private SourceInfo sourceExtractor(final String refName, final String key) throws RefNotFoundException, IOException {
        final Ref branchRef = findBranch(refName);
        if (ObjectId.zeroId().equals(branchRef.getObjectId())) {
            return null;
        }
        final Pair<Pair<AnyObjectId, Set<Ref>>, List<BranchData>> source = fileLoader(Pair.of(branchRef.getObjectId(), Set.of(branchRef)),
                key);
        final List<BranchData> sourceInfo = source.getRight();
        final BranchData repositoryData = sourceInfo.get(0);
        final Pair<MetaFileData, SourceFileData> pair = repositoryData.getFirstPair(key);
        if (repositoryData.getFileDataError() != null) {
            throw new RuntimeException(repositoryData.getFileDataError().getInputStreamHolder().exception());
        }
        if (pair.isPresent()) {
            return new SourceInfo(pair.getLeft(), pair.getRight());
        }
        if (pair.getLeft() != null && pair.getLeft().isMasterMetaData()) {
            return new SourceInfo(pair.getLeft(), null);
        }
        return null;
    }

    private Ref findBranch(final String refName) throws IOException, RefNotFoundException {
        final Ref branchRef = repository.findRef(refName);
        if (branchRef == null) {
            throw new RefNotFoundException(refName);
        }
        return branchRef;
    }

    public Pair<Pair<AnyObjectId, Set<Ref>>, List<BranchData>> sourceTestBranchExtractor(final String branchName)
            throws IOException, RefNotFoundException {
        if (!Objects.requireNonNull(branchName).startsWith(JitStaticConstants.REFS_JISTSTATIC)) {
            throw new RefNotFoundException(branchName);
        }
        return sourceGeneralExtractor(branchName);
    }

    private Pair<Pair<AnyObjectId, Set<Ref>>, List<BranchData>> sourceGeneralExtractor(final String branchName)
            throws IOException, RefNotFoundException {
        final Ref branchRef = findBranch(branchName);
        return fileLoader(Pair.of(branchRef.getObjectId(), Set.of(branchRef)));
    }

    public Pair<Pair<AnyObjectId, Set<Ref>>, List<BranchData>> sourceBranchExtractor(final String branchName)
            throws IOException, RefNotFoundException {
        if (!Objects.requireNonNull(branchName).startsWith(Constants.R_HEADS)) {
            throw new RefNotFoundException(branchName);
        }
        return sourceGeneralExtractor(branchName);
    }

    private Pair<Pair<AnyObjectId, Set<Ref>>, List<BranchData>> fileLoader(final Pair<AnyObjectId, Set<Ref>> referencePoint,
            final String key) {
        final List<BranchData> files = new ArrayList<>();
        final AnyObjectId reference = referencePoint.getLeft();
        try (final RevWalk rev = new RevWalk(repository)) {
            final RevCommit parsedCommit = rev.parseCommit(reference);
            final RevTree currentTree = rev.parseTree(parsedCommit.getTree());
            files.add(walkTree(currentTree, key));
            rev.dispose();
        } catch (final IOException e) {
            files.add(
                    new BranchData(new RepositoryDataError(new FileObjectIdStore(key, reference.toObjectId()), new InputStreamHolder(e))));
        }
        return Pair.of(referencePoint, files);
    }

    private BranchData walkTree(final RevTree tree, final String key) {
        final Map<String, MetaFileData> metaFiles = new HashMap<>();
        final Map<String, SourceFileData> dataFiles = new HashMap<>();
        RepositoryDataError error = null;
        try (final TreeWalk treeWalker = new TreeWalk(repository)) {
            treeWalker.addTree(tree);
            treeWalker.setRecursive(false);
            treeWalker.setPostOrderTraversal(false);
            if (key != null) {
                treeWalker.setFilter(getTreeFilter(key));
            }
            while (treeWalker.next()) {
                if (!treeWalker.isSubtree()) {
                    final FileMode mode = treeWalker.getFileMode();
                    if (mode == FileMode.REGULAR_FILE || mode == FileMode.EXECUTABLE_FILE) {
                        final ObjectId objectId = treeWalker.getObjectId(0);
                        final String path = new String(treeWalker.getRawPath(), StandardCharsets.UTF_8);
                        final InputStreamHolder inputStreamHolder = getInputStreamFor(objectId);
                        final FileObjectIdStore fileObjectIdStore = new FileObjectIdStore(path, objectId);
                        matchKeys(metaFiles, dataFiles, path, inputStreamHolder, fileObjectIdStore);
                    }
                } else {
                    treeWalker.enterSubtree();
                }
            }
        } catch (final IOException e) {
            error = new RepositoryDataError(new FileObjectIdStore(key, tree.getId()), new InputStreamHolder(e));
        }
        return new BranchData(metaFiles, dataFiles, error);
    }

    private void matchKeys(final Map<String, MetaFileData> metaFiles, final Map<String, SourceFileData> dataFiles, final String path,
            final InputStreamHolder inputStreamHolder, final FileObjectIdStore fileObjectIdStore) {
        final Path p = Path.of(path);
        if (p.getLastElement().equals(JitStaticConstants.METADATA) || !p.getLastElement().startsWith(".")) {
            if (path.endsWith(JitStaticConstants.METADATA)) {
                if (p.getLastElement().equals(JitStaticConstants.METADATA)) {
                    metaFiles.put(path, new MetaFileData(fileObjectIdStore, inputStreamHolder));
                } else {
                    metaFiles.put(path.substring(0, path.length() - METADATA_LENGTH),
                            new MetaFileData(fileObjectIdStore, inputStreamHolder, true));
                }
            } else {
                dataFiles.put(path, new SourceFileData(fileObjectIdStore, inputStreamHolder));
            }
        }
    }

    private TreeFilter getTreeFilter(final String key) {
        final Path path = Path.of(key);
        final TreeFilter pfg;
        if (path.isDirectory()) {
            pfg = PathFilterGroup.createFromStrings(key + JitStaticConstants.METADATA);
        } else {
            pfg = PathFilterGroup.createFromStrings(key, key + JitStaticConstants.METADATA,
                    path.getParentElements() + JitStaticConstants.METADATA);
        }
        return pfg;
    }

    private InputStreamHolder getInputStreamFor(final ObjectId objectId) {
        try {
            return new InputStreamHolder(repository.open(objectId));
        } catch (final IOException e) {
            return new InputStreamHolder(e);
        }
    }

    private Pair<Pair<AnyObjectId, Set<Ref>>, List<BranchData>> fileLoader(final Pair<AnyObjectId, Set<Ref>> referencePoint) {
        return fileLoader(referencePoint, null);
    }

    public Map<Pair<AnyObjectId, Set<Ref>>, List<BranchData>> extractAll() {
        final Map<AnyObjectId, Set<Ref>> allRefs = repository.getAllRefsByPeeledObjectId();
        return allRefs.entrySet().stream().map(e -> {
            final Set<Ref> refs = e.getValue().stream().filter(ref -> !ref.isSymbolic())
                    .filter(ref -> ref.getName().startsWith(Constants.R_HEADS)).collect(Collectors.toSet());
            return Pair.of(e.getKey(), refs);
        }).map(this::fileLoader)
                .collect(Collectors.toConcurrentMap(branchErrors -> branchErrors.getLeft(), branchErrors -> branchErrors.getRight()));
    }

    public Ref getRef(final String ref) throws IOException {
        return repository.findRef(ref);
    }

    public List<String> getListForKey(final String key, final String ref, boolean recursive) throws RefNotFoundException, IOException {
        final Ref findBranch = findBranch(ref);
        final AnyObjectId reference = findBranch.getObjectId();
        final List<String> keys;
        try (final RevWalk rev = new RevWalk(repository)) {
            final RevCommit parsedCommit = rev.parseCommit(reference);
            final RevTree currentTree = rev.parseTree(parsedCommit.getTree());
            try (final TreeWalk treeWalker = new TreeWalk(repository)) {
                treeWalker.addTree(currentTree);
                treeWalker.setRecursive(recursive);
                if (!key.equals("/")) {
                    treeWalker.setFilter(PathFilterGroup.createFromStrings(key));
                }
                keys = walkTree(key, treeWalker);
            }
            rev.dispose();
        }
        return keys;
    }

    private List<String> walkTree(final String key, final TreeWalk treeWalker)
            throws MissingObjectException, IncorrectObjectTypeException, CorruptObjectException, IOException {
        final List<String> keys = new ArrayList<>();
        final byte[] keyData = key.getBytes(StandardCharsets.UTF_8);
        while (treeWalker.next()) {
            if (treeWalker.isSubtree()) {
                byte[] rawPath = treeWalker.getRawPath();
                int rawPathLength = rawPath.length;
                if (rawPathLength > keyData.length) {
                    continue;
                }

                if (Arrays.compare(rawPath, 0, rawPathLength, keyData, 0, rawPathLength) == 0) {
                    treeWalker.enterSubtree();
                }
            } else {
                final FileMode mode = treeWalker.getFileMode();
                if (mode == FileMode.REGULAR_FILE || mode == FileMode.EXECUTABLE_FILE) {
                    keys.add(new String(treeWalker.getRawPath(), StandardCharsets.UTF_8));
                }
            }
        }
        return keys;
    }
}
