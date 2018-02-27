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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;

import com.spencerwi.either.Either;

import jitstatic.hosted.InputStreamHolder;
import jitstatic.utils.Pair;

public class SourceChecker implements AutoCloseable {

	private static final SourceJSONParser PARSER = new SourceJSONParser();
	private final Repository repository;
	private final SourceExtractor extractor;

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
			final Pair<Pair<AnyObjectId, Set<Ref>>, List<BranchData>> branchSource) throws RefNotFoundException {
		if (!branchSource.isPresent()) {
			throw new RefNotFoundException(branch);
		}
		final Pair<AnyObjectId, Set<Ref>> revCommit = branchSource.getLeft();
		final List<BranchData> branchData = branchSource.getRight();
		final List<Pair<FileObjectIdStore, Exception>> branchErrors = branchData.stream().parallel().map(this::readRepositoryData)
				.flatMap(List::stream).filter(Pair::isPresent).collect(Collectors.toList());

		return Arrays.asList(Pair.of(revCommit.getRight(), branchErrors));
	}

	@Override
	public void close() {
		this.repository.close();
	}

	public List<Pair<Set<Ref>, List<Pair<FileObjectIdStore, Exception>>>> check() {
		final Map<Pair<AnyObjectId, Set<Ref>>, List<BranchData>> sources = extractor.extractAll();
		return sources.entrySet().stream().parallel().map(e -> {
			return Pair.of(e.getKey().getRight(), e.getValue().stream().map(this::readRepositoryData).flatMap(List::stream)
					.filter(Pair::isPresent).collect(Collectors.toList()));
		}).filter(p -> !p.getRight().isEmpty()).collect(Collectors.toList());
	}

	private List<Pair<FileObjectIdStore, Exception>> readRepositoryData(final BranchData data) {
		final List<Pair<FileObjectIdStore, Exception>> fileErrors = data.pair().stream().map(this::parseErrors).flatMap(s -> s)
				.collect(Collectors.toCollection(() -> new ArrayList<>()));
		final RepositoryDataError fileDataError = data.getFileDataError();
		if (fileDataError != null) {
			fileErrors.add(Pair.of(fileDataError.getFileInfo(), fileDataError.getInputStreamHolder().exception()));
		}
		return fileErrors;
	}

	private Stream<Pair<FileObjectIdStore, Exception>> parseErrors(final Pair<MetaFileData, SourceFileData> data) {
		final SourceFileData sourceFileData = data.getRight();
		final MetaFileData metaFileData = data.getLeft();
		if (metaFileData == null) {
			final FileObjectIdStore fileInfo = sourceFileData.getFileInfo();
			return Stream.of(Pair.of(fileInfo, new FileIsMissingMetaData(fileInfo.getFileName())));
		}
		if (sourceFileData == null) {
			final FileObjectIdStore fileInfo = metaFileData.getFileInfo();
			return Stream.of(Pair.of(fileInfo, new MetaDataFileIsMissingSourceFile(fileInfo.getFileName())));
		}
		final Pair<FileObjectIdStore, Either<String, Exception>> check = readAndCheckMetaFileData(data.getLeft());
		final Either<String, Exception> fileMetaDataResult = check.getRight();
		if (fileMetaDataResult.isLeft()) {
			return Stream.of(Pair.ofNothing(), readAndCheckSourceFileData(sourceFileData, fileMetaDataResult.getLeft()));
		}
		return Stream.of(Pair.of(check.getLeft(), fileMetaDataResult.getRight()));
	}

	private Pair<FileObjectIdStore, Exception> readAndCheckSourceFileData(final SourceFileData data, final String contentType) {
		final InputStreamHolder inputStreamHolder = data.getInputStreamHolder();
		if (inputStreamHolder.isPresent()) {
			try (InputStream is = inputStreamHolder.inputStream()) {
				if (JitStaticConstants.APPLICATION_JSON.equals(contentType)) {
					PARSER.parseJson(is);
				}
			} catch (final IOException e) {
				return Pair.of(data.getFileInfo(), e);
			}
			// File OK
			return Pair.ofNothing();
		}
		// File had a repository exception
		return Pair.of(data.getFileInfo(), inputStreamHolder.exception());
	}

	private Pair<FileObjectIdStore, Either<String, Exception>> readAndCheckMetaFileData(final MetaFileData data) {
		final InputStreamHolder inputStreamHolder = data.getInputStreamHolder();
		final FileObjectIdStore fileObject = data.getFileInfo();
		if (inputStreamHolder == null) {
			// File is removed
			return Pair.of(fileObject, null);
		}
		if (inputStreamHolder.isPresent()) {
			try (final InputStream is = inputStreamHolder.inputStream()) {
				return Pair.of(fileObject, Either.left(PARSER.parse(is)));
			} catch (final IOException e) {
				// File had errors
				return Pair.of(fileObject, Either.right(e));
			}
		}
		// File had an exception at repository level
		return Pair.of(fileObject, Either.right(inputStreamHolder.exception()));
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
