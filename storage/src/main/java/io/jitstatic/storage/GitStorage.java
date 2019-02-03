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

import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.lib.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.spencerwi.either.Either;

import io.jitstatic.CommitMetaData;
import io.jitstatic.MetaData;
import io.jitstatic.auth.UserData;
import io.jitstatic.hosted.FailedToLock;
import io.jitstatic.hosted.KeyAlreadyExist;
import io.jitstatic.hosted.LoadException;
import io.jitstatic.hosted.RefLockHolder;
import io.jitstatic.hosted.StoreInfo;
import io.jitstatic.hosted.events.DeleteRef;
import io.jitstatic.hosted.events.Reloader;
import io.jitstatic.source.ObjectStreamProvider;
import io.jitstatic.source.Source;
import io.jitstatic.source.SourceInfo;
import io.jitstatic.utils.Pair;
import io.jitstatic.utils.ShouldNeverHappenException;
import io.jitstatic.utils.WrappingAPIException;

public class GitStorage implements Storage, Reloader, DeleteRef {

    private static final String DATA_CANNOT_BE_NULL = "data cannot be null";
    private static final String KEY_CANNOT_BE_NULL = "key cannot be null";
    private static final Logger LOG = LoggerFactory.getLogger(GitStorage.class);
    private final Map<String, RefHolder> cache = new ConcurrentHashMap<>();
    private final AtomicReference<Exception> fault = new AtomicReference<>();
    private final Source source;
    private final String defaultRef;
    private final HashService hashService;
    private final String rootUser;

    public GitStorage(final Source source, final String defaultRef, final HashService hashService, String rootUser) {
        this.source = Objects.requireNonNull(source, "Source cannot be null");
        this.defaultRef = defaultRef == null ? Constants.R_HEADS + Constants.MASTER : defaultRef;
        this.hashService = Objects.requireNonNull(hashService);
        this.rootUser = rootUser;
    }

    public RefLockHolder getRefHolderLock(final String ref) {
        return getRefHolder(ref);
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
            if (key.endsWith("/")) {
                throw new WrappingAPIException(new UnsupportedOperationException(key));
            }
            return getKeyDirect(key, ref);
        } catch (LoadException e) {
            removeCacheRef(ref);
        } catch (WrappingAPIException e) {
            throw e;
        } catch (Exception e) {
            consumeError(e);
        }
        return Optional.empty();
    }

    private Optional<StoreInfo> getKeyDirect(final String key, final String ref) {
        if (checkKeyIsDotFile(key)) {
            return Optional.empty();
        }

        final String finalRef = checkRef(ref);
        final RefHolder refHolder = getRefHolder(finalRef);
        final Optional<StoreInfo> storeInfo = refHolder.readKey(key);
        if (storeInfo == null) {
            return refHolder.loadAndStore(key);
        }
        return storeInfo;
    }

    @Override
    public Pair<MetaData, String> getMetaKey(final String key, final String ref) {
        final Optional<StoreInfo> keyDirect = getKeyDirect(key, ref);

        if (!keyDirect.isPresent()) {
            return Pair.ofNothing();
        }
        final StoreInfo storeInfo = keyDirect.get();
        return Pair.of(storeInfo.getMetaData(), storeInfo.getMetaDataVersion());
    }

    private boolean checkKeyIsDotFile(final String key) {
        return Tree.of(List.of(Pair.of(key, false))).accept(new DotFinderVisitor());
    }

    private RefHolder getRefHolder(final String finalRef) {
        return cache.computeIfAbsent(finalRef, r -> new RefHolder(r, source, hashService));
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
    public Either<String, FailedToLock> put(final String key, String ref, final ObjectStreamProvider data, final String oldVersion,
            final CommitMetaData commitMetaData) {
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
    public String addKey(final String key, String branch, final ObjectStreamProvider data, final MetaData metaData, final CommitMetaData commitMetaData) {
        Objects.requireNonNull(key, KEY_CANNOT_BE_NULL);
        Objects.requireNonNull(data, DATA_CANNOT_BE_NULL);
        Objects.requireNonNull(metaData, "metaData cannot be null");

        if (checkKeyIsDotFile(key) || key.endsWith("/")) {
            throw new WrappingAPIException(new UnsupportedOperationException(key));
        }

        final String finalRef = checkRef(branch);
        isRefATag(finalRef);
        final RefHolder refStore = getRefHolder(finalRef);
        final Either<String, FailedToLock> result = refStore.lockWrite(() -> {
            SourceInfo sourceInfo = null;
            final Optional<StoreInfo> storeInfo = refStore.getKeyNoLock(key);
            if (storeInfo != null && storeInfo.isPresent()) {
                throw new WrappingAPIException(new KeyAlreadyExist(key, finalRef));
            }
            try {
                sourceInfo = source.getSourceInfo(key, finalRef);
            } catch (final RefNotFoundException e) {
                if (!defaultRef.equals(finalRef)) {
                    sourceInfo = checkBranch(key, finalRef, getRefHolder(defaultRef));
                }
            }
            if (sourceInfo != null && !sourceInfo.isMetaDataSource()) {
                throw new WrappingAPIException(new KeyAlreadyExist(key, finalRef));
            }
            try {
                return refStore.addKey(key, finalRef, data, metaData, commitMetaData);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, key);
        if (result.isRight()) {
            throw new WrappingAPIException(new KeyAlreadyExist(key, finalRef));
        }
        CompletableFuture.runAsync(() -> removeCacheRef(finalRef));
        return result.getLeft();
    }

    private SourceInfo checkBranch(final String key, final String ref, final RefHolder refHolder) {
        SourceInfo sourceInfo = null;
        try {
            final Optional<StoreInfo> defaultKey = refHolder.getKeyNoLock(key);
            if (defaultKey != null && defaultKey.isPresent()) {
                throw new WrappingAPIException(new KeyAlreadyExist(key, ref));
            }
            sourceInfo = source.getSourceInfo(key, defaultRef);
        } catch (final RefNotFoundException e1) {
            throw new ShouldNeverHappenException("Default branch " + defaultRef + " is not found");
        }
        if (sourceInfo == null) {
            try {
                source.createRef(ref);
            } catch (final IOException e1) {
                consumeError(e1);
                throw new UncheckedIOException(e1);
            }

        }
        return sourceInfo;
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

        if (checkKeyIsDotFile(key)) {
            throw new WrappingAPIException(new UnsupportedOperationException(key));
        }
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
        Objects.requireNonNull(commitMetaData);

        if (checkKeyIsDotFile(key)) {
            throw new WrappingAPIException(new UnsupportedOperationException(key));
        }

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
        CompletableFuture.runAsync(() -> removeCacheRef(finalRef));
    }

    private void isRefATag(final String finalRef) {
        if (finalRef.startsWith(Constants.R_TAGS)) {
            throw new WrappingAPIException(new UnsupportedOperationException("Tags cannot be modified"));
        }
    }

    @Override
    public List<Pair<String, StoreInfo>> getListForRef(final List<Pair<String, Boolean>> keyPairs, final String ref) {
        Objects.requireNonNull(keyPairs);
        final String finalRef = checkRef(ref);
        return Tree.of(keyPairs).accept(new PathBuilderVisitor())
                .parallelStream()
                .map(pair -> {
                    final String key = pair.getLeft();
                    if (key.endsWith("/")) {
                        try {
                            return source.getList(key, finalRef, pair.getRight()).parallelStream()
                                    .map(k -> Pair.of(k, getKey(k, finalRef)))
                                    .filter(Pair::isPresent)
                                    .filter(pa -> pa.getRight().isPresent())
                                    .map(pa -> Pair.of(pa.getLeft(), pa.getRight().get()))
                                    .collect(Collectors.toList());
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
        return input.stream()
                .map(p -> Pair.of(getListForRef(p.getLeft(), p.getRight()), p.getRight()))
                .collect(Collectors.toList());
    }

    @Override
    public UserData getUser(final String key, String ref, final String realm) throws RefNotFoundException {
        final Pair<String, UserData> userData = getUserData(key, ref, realm);
        if (userData != null && userData.isPresent()) {
            return userData.getRight();
        }
        return null;
    }

    @Override
    public Pair<String, UserData> getUserData(final String key, String ref, final String realm) throws RefNotFoundException {
        final RefHolder refHolder = getRefHolder(checkRef(ref));
        try {
            return refHolder.getUser(realm + "/" + key);
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

    @Override
    public Either<String, FailedToLock> updateUser(final String key, String ref, final String realm, final String creatorUserName, final UserData data,
            final String version) {
        ref = checkRef(ref);
        isRefATag(ref);
        final RefHolder refHolder = cache.get(ref);
        if (refHolder == null) {
            throw new UnsupportedOperationException(key);
        }
        return refHolder.updateUser(realm + "/" + key, creatorUserName, data, version);
    }

    @Override
    public String addUser(final String key, String ref, final String realm, final String creatorUserName, final UserData data) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(realm);
        Objects.requireNonNull(creatorUserName);
        Objects.requireNonNull(data);
        final String finalRef = checkRef(ref);
        isRefATag(finalRef);
        if (rootUser.equals(key)) {
            throw new WrappingAPIException(new KeyAlreadyExist(key, finalRef));
        }
        final RefHolder refHolder = getRefHolder(finalRef);        
        final Either<String, FailedToLock> postUser = refHolder.addUser(realm + "/" + key, creatorUserName, data);
        if (postUser.isRight()) {
            CompletableFuture.runAsync(() -> removeCacheRef(finalRef));
            throw new WrappingAPIException(new KeyAlreadyExist(key, finalRef));
        }
        return postUser.getLeft();
    }

    @Override
    public void deleteUser(final String key, String ref, final String realm, final String creatorUserName) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(realm);
        Objects.requireNonNull(creatorUserName);
        ref = checkRef(ref);
        isRefATag(ref);
        final RefHolder refHolder = cache.get(ref);
        if (refHolder != null) {
            refHolder.deleteUser(realm + "/" + key, creatorUserName);
        }
    }

    @Override
    public void reload(String ref) {
        final RefHolder refHolder = cache.get(ref);
        if (refHolder != null) {
            refHolder.reload();
        }
    }

    @Override
    public void deleteRef(String ref) {
        LOG.info("Deleting {}", ref);
        cache.remove(ref);
    }
}
