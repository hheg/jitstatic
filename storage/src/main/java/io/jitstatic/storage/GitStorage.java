package io.jitstatic.storage;

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
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.lib.Constants;

import com.spencerwi.either.Either;

import io.jitstatic.CommitMetaData;
import io.jitstatic.MetaData;
import io.jitstatic.auth.UserData;
import io.jitstatic.hosted.FailedToLock;
import io.jitstatic.hosted.KeyAlreadyExist;
import io.jitstatic.hosted.LoadException;
import io.jitstatic.hosted.RefHolderLock;
import io.jitstatic.hosted.StoreInfo;
import io.jitstatic.source.Source;
import io.jitstatic.source.SourceInfo;
import io.jitstatic.utils.Pair;
import io.jitstatic.utils.Path;
import io.jitstatic.utils.ShouldNeverHappenException;
import io.jitstatic.utils.WrappingAPIException;

public class GitStorage implements Storage {

    private static final String DATA_CANNOT_BE_NULL = "data cannot be null";
    private static final String KEY_CANNOT_BE_NULL = "key cannot be null";
    private static final Logger LOG = LogManager.getLogger(GitStorage.class);
    private final Map<String, RefHolder> cache = new ConcurrentHashMap<>();
    private final AtomicReference<Exception> fault = new AtomicReference<>();
    private final Source source;
    private final String defaultRef;

    public GitStorage(final Source source, final String defaultRef) {
        this.source = Objects.requireNonNull(source, "Source cannot be null");
        this.defaultRef = defaultRef == null ? Constants.R_HEADS + Constants.MASTER : defaultRef;
    }

    public RefHolderLock getRefHolderLock(final String ref) {
        return getRefHolder(ref);
    }

    public void reload(final List<String> refsToReload) {
        Objects.requireNonNull(refsToReload);
        refsToReload.stream().forEach(ref -> {
            final RefHolder refHolder = cache.get(ref);
            if (refHolder != null) {
                if (!refHolder.reloadAll(() -> {
                    if (!refHolder.refresh()) {
                        cache.remove(ref);
                    }
                })) {
                    final String msg = String.format("Failed to reload %s because couldn't aquire lock", ref);
                    LOG.info(msg);
                    throw new ShouldNeverHappenException(msg);
                }
            }
        });
    }

    private void consumeError(final Exception e) {
        fault.getAndSet(e);
        LOG.warn("Error occourred ", e);
    }

    private String checkRef(final String ref) {
        return ref == null ? defaultRef : ref;
    }

    @Override
    public Optional<StoreInfo> getKey(final String key, final String ref) {
        try {
            if (checkKeyIsDotFile(key)) {
                return Optional.empty();
            }
            final String finalRef = checkRef(ref);
            final RefHolder refHolder = getRefHolder(finalRef);
            final Optional<StoreInfo> storeInfo = refHolder.getKey(key);
            if (storeInfo == null) {
                return refHolder.loadAndStore(key);
            }
            return storeInfo;
        } catch (final LoadException e) {
            removeCacheRef(ref);
        } catch (final Exception e) {
            consumeError(e);
        }
        return Optional.empty();
    }

    private boolean checkKeyIsDotFile(final String key) {
        return Path.of(key).getLastElement().startsWith(".");
    }

    private RefHolder getRefHolder(final String finalRef) {
        return cache.computeIfAbsent(finalRef, r -> new RefHolder(r, new ConcurrentHashMap<>(), source));
    }

    @Override
    public void close() {
        try {
            source.close();
        } catch (final Exception ignore) {
        }
    }

    @Override
    public void checkHealth() throws Exception {
        final Exception old = fault.getAndSet(null);
        if (old != null) {
            throw old;
        }
    }

    @Override
    public Either<String, FailedToLock> put(final String key, String ref, final byte[] data, final String oldVersion, final CommitMetaData commitMetaData) {
        Objects.requireNonNull(key, KEY_CANNOT_BE_NULL);
        Objects.requireNonNull(data, DATA_CANNOT_BE_NULL);
        Objects.requireNonNull(oldVersion, "oldVersion cannot be null");
        final String finalRef = checkRef(ref);
        final RefHolder refHolder = cache.get(finalRef);
        if (refHolder == null) {
            throw new WrappingAPIException(new RefNotFoundException(finalRef));
        }
        return refHolder.modifyKey(key, finalRef, data, oldVersion, commitMetaData);
    }

    @Override
    public String addKey(final String key, String branch, final byte[] data, final MetaData metaData, final CommitMetaData commitMetaData) {
        Objects.requireNonNull(key, KEY_CANNOT_BE_NULL);
        Objects.requireNonNull(data, DATA_CANNOT_BE_NULL);
        Objects.requireNonNull(metaData, "metaData cannot be null");

        final String finalRef = checkRef(branch);
        isRefATag(finalRef);
        final RefHolder refStore = getRefHolder(finalRef);

        final Either<String, FailedToLock> result = refStore.lockWrite(() -> {
            checkIfKeyIsPresent(key, finalRef, refStore);
            return refStore.addKey(key, finalRef, data, metaData, commitMetaData);
        }, key);
        if (result.isRight()) {
            throw new WrappingAPIException(new KeyAlreadyExist(key, finalRef));
        }
        CompletableFuture.runAsync(() -> removeCacheRef(finalRef));
        return result.getLeft();
    }

    private void checkIfKeyIsPresent(final String key, final String finalRef, final RefHolder refholder) {
        SourceInfo sourceInfo = null;
        final Optional<StoreInfo> storeInfo = refholder.getKey(key);
        if (storeInfo != null && storeInfo.isPresent()) {
            throw new WrappingAPIException(new KeyAlreadyExist(key, finalRef));
        }
        try {
            sourceInfo = source.getSourceInfo(key, finalRef);
        } catch (final RefNotFoundException e) {
            if (!defaultRef.equals(finalRef)) {
                try {
                    sourceInfo = source.getSourceInfo(key, defaultRef);
                } catch (final RefNotFoundException e1) {
                    throw new ShouldNeverHappenException("Default branch " + defaultRef + " is not found");
                }
                if (sourceInfo == null) {
                    branchFromRef(finalRef);
                }
            }
        }
        if (sourceInfo != null && !sourceInfo.isMetaDataSource()) {
            throw new WrappingAPIException(new KeyAlreadyExist(key, finalRef));
        }
    }

    private void branchFromRef(final String finalRef) {
        try {
            source.createRef(finalRef);
        } catch (final IOException e1) {
            consumeError(e1);
            throw new UncheckedIOException(e1);
        }
    }

    private void removeCacheRef(final String ref) {
        if (ref == null) {
            return;
        }
        cache.computeIfPresent(ref, (a, b) -> b.isEmpty() ? null : b);
    }

    @Override
    public Either<String, FailedToLock> putMetaData(final String key, String ref, final MetaData metaData, final String oldMetaDataVersion,
            final CommitMetaData commitMetaData) {
        Objects.requireNonNull(key, KEY_CANNOT_BE_NULL);
        Objects.requireNonNull(metaData, "metaData cannot be null");
        Objects.requireNonNull(oldMetaDataVersion, "metaDataVersion cannot be null");

        final String finalRef = checkRef(ref);
        isRefATag(finalRef);

        final RefHolder refHolder = cache.get(finalRef);
        if (refHolder == null) {
            throw new WrappingAPIException(new RefNotFoundException(finalRef));
        }

        return refHolder.modifyMetadata(metaData, oldMetaDataVersion, commitMetaData, key, finalRef);
    }

    @Override
    public void delete(final String key, final String ref, final CommitMetaData commitMetaData) {
        Objects.requireNonNull(key);

        final String finalRef = checkRef(ref);
        isRefATag(finalRef);
        if (key.endsWith("/")) {
            // We don't support deleting master .metadata files right now
            throw new WrappingAPIException(new UnsupportedOperationException(key));
        }
        final RefHolder refHolder = getRefHolder(finalRef);
        if (refHolder != null) {
            try {
                refHolder.deleteKey(key, finalRef, commitMetaData);
            } catch (final UncheckedIOException ioe) {
                consumeError(ioe);
            }
        }
        removeCacheRef(finalRef);
    }

    private void isRefATag(final String finalRef) {
        if (finalRef.startsWith(Constants.R_TAGS)) {
            throw new UnsupportedOperationException("Tags cannot be modified");
        }
    }

    @Override
    public List<Pair<String, StoreInfo>> getListForRef(final List<Pair<String, Boolean>> keyPairs, final String ref) {
        Objects.requireNonNull(keyPairs);
        final String finalRef = checkRef(ref);
        return Tree.of(keyPairs).accept(new PathBuilderVisitor()).parallelStream().map(pair -> {
            final String key = pair.getLeft();
            if (key.endsWith("/")) {
                try {
                    return source.getList(key, finalRef, pair.getRight()).parallelStream().map(k -> Pair.of(k, getKey(k, finalRef))).filter(Pair::isPresent)
                            .filter(pa -> pa.getRight().isPresent()).map(pa -> Pair.of(pa.getLeft(), pa.getRight().get())).collect(Collectors.toList());
                } catch (final RefNotFoundException rnfe) {
                    return List.<Pair<String, StoreInfo>>of();
                } catch (final IOException e) {
                    consumeError(e);
                    return List.<Pair<String, StoreInfo>>of();
                }
            }
            final Optional<StoreInfo> keyContent = getKey(key, finalRef);
            if (keyContent.isPresent()) {
                return List.of(Pair.of(key, keyContent.get()));
            }
            return List.<Pair<String, StoreInfo>>of();
        }).flatMap(List::stream).collect(Collectors.toList());
    }

    @Override
    public List<Pair<List<Pair<String, StoreInfo>>, String>> getList(final List<Pair<List<Pair<String, Boolean>>, String>> input) {
        return input.stream().map(p -> Pair.of(getListForRef(p.getLeft(), p.getRight()), p.getRight())).collect(Collectors.toList());
    }

    @Override
    public UserData getUser(final String username, String ref, final String realm) throws RefNotFoundException {
        ref = checkRef(ref);
        final RefHolder refHolder = getRefHolder(ref);
        try {
            return refHolder.getUser(realm + "/" + username);
        } catch (WrappingAPIException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof RefNotFoundException) {
                throw (RefNotFoundException) cause;
            }
            if (cause instanceof IOException) {
                throw new UncheckedIOException((IOException) cause);
            }
            throw e;
        }
    }
}
