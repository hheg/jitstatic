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

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.jitstatic.utils.Pair;
import io.jitstatic.utils.Path;

class BranchData {

    private final Map<String, MetaFileData> metaFiles;
    private final Map<String, SourceFileData> sourceFiles;
    private final RepositoryDataError error;

    public BranchData(final Map<String, MetaFileData> metaFiles, final Map<String, SourceFileData> sourceFiles, final RepositoryDataError error) {
        this(error, Objects.requireNonNull(metaFiles), Objects.requireNonNull(sourceFiles));
    }

    public BranchData(final RepositoryDataError fileDataError) {
        this(fileDataError, null, null);
    }

    private BranchData(final RepositoryDataError error, final Map<String, MetaFileData> metaFiles, final Map<String, SourceFileData> sourceFiles) {
        this.metaFiles = metaFiles;
        this.sourceFiles = sourceFiles;
        this.error = error;
    }

    public RepositoryDataError getFileDataError() {
        return error;
    }

    public List<Pair<MetaFileData, SourceFileData>> pair() {
        Objects.requireNonNull(sourceFiles);
        Objects.requireNonNull(metaFiles);

        return Stream.concat(sourceFiles.entrySet().stream().map(e -> {
            final String key = e.getKey();
            MetaFileData metaFileData = metaFiles.get(key);
            if (metaFileData == null) {
                final Path path = Path.of(key);
                metaFileData = metaFiles.get(path.getParentElements() + JitStaticConstants.METADATA);
            }
            return Pair.of(metaFileData, e.getValue());
        }), metaFiles.entrySet().stream().filter(e -> {
            final String key = e.getKey();
            if (e.getValue().isMasterMetaData()) {
                return true;
            }
            return !sourceFiles.containsKey(key);
        }).map(e -> Pair.of(e.getValue(), (SourceFileData) null))).collect(Collectors.toList());
    }

    public Pair<MetaFileData, SourceFileData> getFirstPair(final String key) {
        final List<Pair<MetaFileData, SourceFileData>> data = pair();
        if (data.size() == 0) {
            return Pair.ofNothing();
        }
        Optional<Pair<MetaFileData, SourceFileData>> firstPair = data.stream().filter(Pair::isPresent).findFirst();
        if (firstPair.isPresent()) {
            return firstPair.get();
        }
        firstPair = data.stream().filter(p -> p.getLeft().isMasterMetaData()).findFirst();
        if (firstPair.isPresent()) {
            return firstPair.get();
        }
        return Pair.ofNothing();
    }

}
