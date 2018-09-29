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
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.eclipse.jgit.lib.Ref;

import io.jitstatic.utils.Pair;

public class SourceUpdater {

    private final RepositoryUpdater repositoryUpdater;

    public SourceUpdater(final RepositoryUpdater repositoryUpdater) {
        this.repositoryUpdater = repositoryUpdater;
    }

    public Pair<String, String> addKey(final Pair<Pair<String, byte[]>, Pair<String, byte[]>> fileEntry, final Ref ref, final CommitMetaData commitMetaData)
            throws IOException {
        return addEntry(fileEntry, ref, commitMetaData, "add key");
    }

    public String updateMetaData(final String key, final Ref ref, final byte[] data, final CommitMetaData commitMetaData) throws IOException {
        return addEntry(Pair.of(Pair.ofNothing(), Pair.of(key, data)), ref, commitMetaData, "update metadata").getRight();
    }

    public String updateKey(final String key, final Ref ref, final byte[] data, final CommitMetaData commitMetaData) throws IOException {
        return addEntry(Pair.of(Pair.of(key, data), Pair.ofNothing()), ref, commitMetaData, "update key").getLeft();
    }

    private Pair<String, String> addEntry(final Pair<Pair<String, byte[]>, Pair<String, byte[]>> keyEntry, final Ref ref, final CommitMetaData commitMetaData,
            final String method) throws IOException {
        Objects.requireNonNull(keyEntry);
        Objects.requireNonNull(ref);

        final Pair<String, byte[]> file = Objects.requireNonNull(keyEntry.getLeft());
        final Pair<String, byte[]> fileMetadata = Objects.requireNonNull(keyEntry.getRight());
        if (!file.isPresent() && !fileMetadata.isPresent()) {
            throw new IllegalArgumentException("No entry data");
        }
        final List<Pair<String, String>> committed = repositoryUpdater.commit(ref, commitMetaData, method, List.of(file, fileMetadata));
        if (committed.size() == 2) {
            return Pair.of(committed.get(0).getRight(), committed.get(1).getRight());
        }
        if (file.isPresent()) {
            return Pair.of(committed.get(0).getRight(), null);
        }
        return Pair.of(null, committed.get(0).getRight());
    }

    public void deleteKey(final String file, final Ref ref, final CommitMetaData commitMetaData, final boolean hasKeyMetaFile)
            throws IOException {
        repositoryUpdater.deleteKeys(hasKeyMetaFile ? Set.of(file, file + JitStaticConstants.METADATA) : Set.of(file), ref, commitMetaData);
    }

    public void createRef(final String baseRef, final String finalRef) throws IOException {
        repositoryUpdater.createRef(baseRef, finalRef);
    }

    public void deleteRef(final String finalRef) throws IOException {
        repositoryUpdater.deleteRefs(List.of(finalRef));
    }
}
