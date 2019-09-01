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
import java.util.stream.Collectors;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;

import io.jitstatic.source.ObjectStreamProvider;
import io.jitstatic.source.SmallObjectStreamProvider;
import io.jitstatic.utils.Functions.ThrowingSupplier;
import io.jitstatic.utils.Pair;

public class SourceUpdater {

    private final RepositoryUpdater repositoryUpdater;

    public SourceUpdater(final RepositoryUpdater repositoryUpdater) {
        this.repositoryUpdater = repositoryUpdater;
    }

    public Pair<Pair<ThrowingSupplier<ObjectLoader, IOException>, String>, String> addKey(
            final Pair<Pair<String, ObjectStreamProvider>, Pair<String, byte[]>> fileEntry, final CommitMetaData commitMetaData, final String ref)
            throws IOException {
        final List<Pair<String, ObjectId>> addedEntry = addEntry(fileEntry, commitMetaData, ref);
        final Pair<String, ObjectId> file = addedEntry.get(0);
        final Pair<String, ObjectId> metadata = addedEntry.get(1);
        return Pair.of(Pair.of(getObjectLoaderFactory(file), file.getRight().name()), metadata.getRight().name());
    }

    public String updateMetaData(final String key, final byte[] data, final CommitMetaData commitMetaData, final String ref)
            throws IOException {
        final Pair<String, ObjectId> updateMetaDataEntry = addEntry(Pair.of(Pair.ofNothing(), Pair.of(key, data)), commitMetaData, ref)
                .get(0);
        return updateMetaDataEntry.getRight().name();
    }

    public Pair<String, ThrowingSupplier<ObjectLoader, IOException>> modifyKey(final String key, final ObjectStreamProvider data,
            final CommitMetaData commitMetaData, final String ref) throws IOException {
        final Pair<String, ObjectId> updatedKeyEntry = addEntry(Pair.of(Pair.of(key, data), Pair.ofNothing()), commitMetaData, ref).get(0);
        return Pair.of(updatedKeyEntry.getRight().name(), getObjectLoaderFactory(updatedKeyEntry));
    }

    private ThrowingSupplier<ObjectLoader, IOException> getObjectLoaderFactory(final Pair<String, ObjectId> updatedKeyEntry) {
        final Repository repository = repositoryUpdater.getRepository();
        final ObjectId objectId = updatedKeyEntry.getRight();
        return () -> repository.open(objectId);
    }

    private List<Pair<String, ObjectId>> addEntry(final Pair<Pair<String, ObjectStreamProvider>, Pair<String, byte[]>> keyEntry,
            final CommitMetaData commitMetaData, final String ref) throws IOException {
        Objects.requireNonNull(keyEntry);

        final Pair<String, ObjectStreamProvider> file = Objects.requireNonNull(keyEntry.getLeft());
        final Pair<String, byte[]> fileMetadata = Objects.requireNonNull(keyEntry.getRight());
        if (!file.isPresent() && !fileMetadata.isPresent()) {
            throw new IllegalArgumentException("No entry data");
        }
        byte[] fileMetaDataArray = fileMetadata.getRight();
        return repositoryUpdater.buildDirCache(commitMetaData,
                List.of(file, Pair.of(fileMetadata.getLeft(), fileMetaDataArray == null ? null : new SmallObjectStreamProvider(fileMetaDataArray))), ref);
    }

    public void deleteKey(final String file, final CommitMetaData commitMetaData, final boolean hasKeyMetaFile, final String ref)
            throws IOException {
        List<Pair<String, ObjectStreamProvider>> files = (hasKeyMetaFile ? Set.of(file, file + JitStaticConstants.METADATA) : Set.of(file)).stream()
                .map(f -> Pair.of(f, (ObjectStreamProvider) null))
                .collect(Collectors.toList());
        repositoryUpdater.buildDirCache(commitMetaData, files, ref);
    }

    public void createRef(final String baseRef, final String finalRef) throws IOException {
        repositoryUpdater.createRef(baseRef, finalRef);
    }

    public void deleteRef(final String finalRef) throws IOException {
        repositoryUpdater.deleteRefs(List.of(finalRef));
    }
}
