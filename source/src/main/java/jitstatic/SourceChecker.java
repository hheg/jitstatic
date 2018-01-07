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
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;

import jitstatic.hosted.InputStreamHolder;
import jitstatic.remote.RepositoryIsMissingIntendedBranch;
import jitstatic.util.Pair;

public class SourceChecker implements AutoCloseable {

	private final Repository repository;
	private final SourceExtractor extractor;
	private static final SourceJSONParser DATA_PARSER = new SourceJSONParser();

	public SourceChecker(final Repository repository) {
		this(repository, new SourceExtractor(repository));
	}

	SourceChecker(final Repository repository, final SourceExtractor extractor) {
		this.repository = Objects.requireNonNull(repository);
		this.extractor = extractor;
		repository.incrementOpen();
	}

	public List<Pair<Set<Ref>, List<Pair<FileObjectIdStore, Exception>>>> checkTestBranchForErrors(final String branch)
			throws RefNotFoundException, IOException {
		Objects.requireNonNull(branch);
		return check(branch, extractor.sourceTestBranchExtractor(branch));
	}

	public List<Pair<Set<Ref>, List<Pair<FileObjectIdStore, Exception>>>> checkBranchForErrors(final String branch)
			throws IOException, RefNotFoundException {
		Objects.requireNonNull(branch);
		return check(branch, extractor.sourceBranchExtractor(branch));
	}

	private List<Pair<Set<Ref>, List<Pair<FileObjectIdStore, Exception>>>> check(final String branch,
			final Pair<Pair<AnyObjectId, Set<Ref>>, List<Pair<FileObjectIdStore, InputStreamHolder>>> branchSource)
			throws RefNotFoundException {
		if (!branchSource.isPresent()) {
			throw new RefNotFoundException(branch);
		}		
		final Pair<AnyObjectId, Set<Ref>> revCommit = branchSource.getLeft();
		final List<Pair<FileObjectIdStore, InputStreamHolder>> branchData = branchSource.getRight();
		final List<Pair<FileObjectIdStore, Exception>> branchErrors = branchData.stream().parallel().map(this::read)
				.filter(Pair::isPresent).sequential().collect(Collectors.toList());

		return Arrays.asList(Pair.of(revCommit.getRight(), branchErrors));
	}

	@Override
	public void close() {
		this.repository.close();
	}

	public List<Pair<Set<Ref>, List<Pair<FileObjectIdStore, Exception>>>> check() {
		final Map<Pair<AnyObjectId, Set<Ref>>, List<Pair<FileObjectIdStore, InputStreamHolder>>> sources = extractor
				.extractAll();
		return sources.entrySet().stream().parallel().map(e -> {
			final Set<Ref> refs = e.getKey().getRight();
			final List<Pair<FileObjectIdStore, Exception>> fileStores = e.getValue().stream().map(this::read).filter(Pair::isPresent)
					.filter(p -> p.getRight() != null).collect(Collectors.toList());
			return Pair.of(refs, fileStores);
		}).filter(p -> !p.getRight().isEmpty()).collect(Collectors.toList());
	}

	private Pair<FileObjectIdStore, Exception> read(final Pair<FileObjectIdStore, InputStreamHolder> data) {
		final InputStreamHolder inputStreamHolder = data.getRight();
		final FileObjectIdStore fileObject = data.getLeft();
		if (inputStreamHolder == null) {
			// File is removed
			return Pair.of(fileObject, null);
		}
		if (inputStreamHolder.isPresent()) {
			try (final InputStream is = inputStreamHolder.inputStream()) {
				DATA_PARSER.parse(is);
			} catch (final IOException e) {
				// File had errors
				return Pair.of(fileObject, e);
			}
			// File is OK
			return new Pair<>();
		}
		// File had an exception at repository level
		return Pair.of(fileObject, inputStreamHolder.exception());
	}

	public void checkIfDefaultBranchExists(final String defaultRef) throws IOException {
		final Map<AnyObjectId, Set<Ref>> allRefsByPeeledObjectId = repository.getAllRefsByPeeledObjectId();
		if (!allRefsByPeeledObjectId.isEmpty()) {
			final Ref ref = repository.findRef(defaultRef);
			if (ref == null) {
				throw new RepositoryIsMissingIntendedBranch(defaultRef);
			}
		}
	}
}
