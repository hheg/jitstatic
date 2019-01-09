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

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Ref;
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
            final Pair<Pair<String, ObjectStreamProvider>, Pair<String, byte[]>> fileEntry,
            final Ref ref, final CommitMetaData commitMetaData)
            throws IOException {
        final List<Pair<String, ObjectId>> addedEntry = addEntry(fileEntry, ref, commitMetaData, "add key");
        final Pair<String, ObjectId> file = addedEntry.get(0);
        final Pair<String, ObjectId> metadata = addedEntry.get(1);
        return Pair.of(Pair.of(getObjectLoaderFactory(file), file.getRight().name()), metadata.getRight().name());
    }

    public String updateMetaData(final String key, final Ref ref, final byte[] data, final CommitMetaData commitMetaData) throws IOException {
        final Pair<String, ObjectId> updateMetaDataEntry = addEntry(Pair.of(Pair.ofNothing(), Pair.of(key, data)), ref, commitMetaData, "update metadata")
                .get(0);
        return updateMetaDataEntry.getRight().name();
    }

    public Pair<String, ThrowingSupplier<ObjectLoader, IOException>> updateKey(final String key, final Ref ref, final ObjectStreamProvider data,
            final CommitMetaData commitMetaData) throws IOException {
        final Pair<String, ObjectId> updatedKeyEntry = addEntry(Pair.of(Pair.of(key, data), Pair.ofNothing()), ref, commitMetaData, "update key").get(0);
        return Pair.of(updatedKeyEntry.getRight().name(), getObjectLoaderFactory(updatedKeyEntry));
    }

    private ThrowingSupplier<ObjectLoader, IOException> getObjectLoaderFactory(final Pair<String, ObjectId> updatedKeyEntry) {
        final Repository repository = repositoryUpdater.getRepository();
        final ObjectId objectId = updatedKeyEntry.getRight();
        return () -> repository.open(objectId);
    }

    private List<Pair<String, ObjectId>> addEntry(final Pair<Pair<String, ObjectStreamProvider>, Pair<String, byte[]>> keyEntry, final Ref ref,
            final CommitMetaData commitMetaData,
            final String method) throws IOException {
        Objects.requireNonNull(keyEntry);
        Objects.requireNonNull(ref);

        final Pair<String, ObjectStreamProvider> file = Objects.requireNonNull(keyEntry.getLeft());
        final Pair<String, byte[]> fileMetadata = Objects.requireNonNull(keyEntry.getRight());
        if (!file.isPresent() && !fileMetadata.isPresent()) {
            throw new IllegalArgumentException("No entry data");
        }
        byte[] fileMetaDataArray = fileMetadata.getRight();
        return repositoryUpdater.commit(ref, commitMetaData, method,
                List.of(file, Pair.of(fileMetadata.getLeft(), fileMetaDataArray == null ? null : new SmallObjectStreamProvider(fileMetaDataArray))));
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
