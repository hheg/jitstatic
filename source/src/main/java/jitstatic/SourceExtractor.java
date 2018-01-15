package jitstatic;

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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;

import jitstatic.hosted.InputStreamHolder;
import jitstatic.source.SourceInfo;
import jitstatic.utils.Pair;

public class SourceExtractor {

	private final Repository repository;

	public SourceExtractor(final Repository repository) {
		this.repository = repository;
	}

	public SourceInfo openTag(final String tagName, final String file) throws RefNotFoundException, IOException {
		if (!Objects.requireNonNull(tagName).startsWith(Constants.R_TAGS)) {
			throw new RefNotFoundException(tagName);
		}
		return sourceExtractor(tagName, file);
	}

	public SourceInfo openBranch(final String branchName, final String file) throws RefNotFoundException, IOException {
		if (!Objects.requireNonNull(branchName).startsWith(Constants.R_HEADS)) {
			throw new RefNotFoundException(branchName);
		}
		return sourceExtractor(branchName, file);
	}

	private SourceInfo sourceExtractor(final String refName, final String file) throws RefNotFoundException, IOException {
		final Ref branchRef = findBranch(refName);
		if (ObjectId.zeroId().equals(branchRef.getObjectId())) {
			return null;
		}
		final Pair<Pair<AnyObjectId, Set<Ref>>, List<Pair<FileObjectIdStore, InputStreamHolder>>> source = fileLoader(
				Pair.of(branchRef.getObjectId(), Collections.singleton(branchRef)), file);
		final List<Pair<FileObjectIdStore, InputStreamHolder>> sourceInfo = source.getRight();
		if (sourceInfo.size() == 1) {
			final Pair<FileObjectIdStore, InputStreamHolder> element = sourceInfo.get(0);
			final InputStreamHolder inputStreamHolder = element.getRight();
			return new SourceInfo(element.getLeft(), inputStreamHolder);
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

	public Pair<Pair<AnyObjectId, Set<Ref>>, List<Pair<FileObjectIdStore, InputStreamHolder>>> sourceTestBranchExtractor(
			final String branchName) throws IOException, RefNotFoundException {
		if (!Objects.requireNonNull(branchName).startsWith(JitStaticConstants.REFS_JISTSTATIC)) {
			throw new RefNotFoundException(branchName);
		}
		return sourceGeneralExtractor(branchName);
	}

	private Pair<Pair<AnyObjectId, Set<Ref>>, List<Pair<FileObjectIdStore, InputStreamHolder>>> sourceGeneralExtractor(
			final String branchName) throws IOException {
		final Ref branchRef = repository.findRef(branchName);
		if (branchRef == null) {
			return Pair.ofNothing();
		}
		return this.fileLoader(Pair.of(branchRef.getObjectId(), Collections.singleton(branchRef)));
	}

	public Pair<Pair<AnyObjectId, Set<Ref>>, List<Pair<FileObjectIdStore, InputStreamHolder>>> sourceBranchExtractor(
			final String branchName) throws IOException, RefNotFoundException {
		if (!Objects.requireNonNull(branchName).startsWith(Constants.R_HEADS)) {
			throw new RefNotFoundException(branchName);
		}
		return sourceGeneralExtractor(branchName);
	}

	private Pair<Pair<AnyObjectId, Set<Ref>>, List<Pair<FileObjectIdStore, InputStreamHolder>>> fileLoader(
			final Pair<AnyObjectId, Set<Ref>> referencePoint, final String key) {
		final List<Pair<FileObjectIdStore, InputStreamHolder>> files = new ArrayList<>();
		final AnyObjectId reference = referencePoint.getLeft();
		try (final RevWalk rev = new RevWalk(repository)) {
			final RevCommit parsedCommit = rev.parseCommit(reference);
			final RevTree currentTree = rev.parseTree(parsedCommit.getTree());
			files.addAll(walkTree(currentTree, key));
			rev.dispose();
		} catch (final IOException e) {
			files.add(Pair.of(new FileObjectIdStore(null, reference.toObjectId()), new InputStreamHolder(e)));
		}
		return Pair.of(referencePoint, files);
	}

	private List<Pair<FileObjectIdStore, InputStreamHolder>> walkTree(final RevTree tree, final String key) {
		final List<Pair<FileObjectIdStore, InputStreamHolder>> files = new ArrayList<>();
		try (final TreeWalk treeWalker = new TreeWalk(repository)) {
			treeWalker.addTree(tree);
			treeWalker.setRecursive(false);
			treeWalker.setPostOrderTraversal(false);
			if (key != null) {
				treeWalker.setFilter(PathFilter.create(key));
			}
			while (treeWalker.next()) {
				if (!treeWalker.isSubtree()) {
					final FileMode mode = treeWalker.getFileMode();
					if (mode == FileMode.REGULAR_FILE || mode == FileMode.EXECUTABLE_FILE) {
						final ObjectId objectId = treeWalker.getObjectId(0);
						final String path = treeWalker.getPathString();
						try {
							final ObjectLoader loader = repository.open(objectId);
							files.add(Pair.of(new FileObjectIdStore(path, objectId), new InputStreamHolder(loader)));
						} catch (final IOException e) {
							files.add(Pair.of(new FileObjectIdStore(path, objectId), new InputStreamHolder(e)));
						}
					}
				} else {
					treeWalker.enterSubtree();
				}
			}
		} catch (final IOException e) {
			files.add(Pair.of(new FileObjectIdStore(null, tree.getId()), new InputStreamHolder(e)));
		}
		return files;
	}

	private Pair<Pair<AnyObjectId, Set<Ref>>, List<Pair<FileObjectIdStore, InputStreamHolder>>> fileLoader(
			final Pair<AnyObjectId, Set<Ref>> referencePoint) {
		return fileLoader(referencePoint, null);
	}

	public Map<Pair<AnyObjectId, Set<Ref>>, List<Pair<FileObjectIdStore, InputStreamHolder>>> extractAll() {
		final Map<AnyObjectId, Set<Ref>> allRefs = repository.getAllRefsByPeeledObjectId();
		return allRefs.entrySet().stream().map(e -> {
			final Set<Ref> refs = e.getValue().stream().filter(ref -> !ref.isSymbolic())
					.filter(ref -> ref.getName().startsWith(Constants.R_HEADS)).collect(Collectors.toSet());
			return Pair.of(e.getKey(), refs);
		}).parallel().map(this::fileLoader)
				.collect(Collectors.toConcurrentMap(branchErrors -> branchErrors.getLeft(), branchErrors -> branchErrors.getRight()));
	}
}
