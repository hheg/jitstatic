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
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.cache2k.Cache;
import org.cache2k.Cache2kBuilder;
import org.cache2k.integration.CacheLoader;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.lib.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.spencerwi.either.Either;

import io.jitstatic.CommitMetaData;
import io.jitstatic.MetaData;
import io.jitstatic.auth.UserData;
import io.jitstatic.hosted.FailedToLock;
import io.jitstatic.hosted.LoadException;
import io.jitstatic.hosted.RefLockHolder;
import io.jitstatic.hosted.StoreInfo;
import io.jitstatic.hosted.events.AddRef;
import io.jitstatic.hosted.events.DeleteRef;
import io.jitstatic.hosted.events.ReloadRef;
import io.jitstatic.source.ObjectStreamProvider;
import io.jitstatic.source.Source;
import io.jitstatic.utils.Pair;
import io.jitstatic.utils.WrappingAPIException;

public class KeyStorage implements Storage, ReloadRef, DeleteRef, AddRef {

    private static final String DATA_CANNOT_BE_NULL = "data cannot be null";
    private static final String KEY_CANNOT_BE_NULL = "key cannot be null";
    private static final Logger LOG = LoggerFactory.getLogger(KeyStorage.class);
    private final Cache<String, RefHolder> cache;
    private final AtomicReference<Exception> fault = new AtomicReference<>();
    private final Source source;
    private final String defaultRef;
    private final String rootUser;
    private final ExecutorService refCleaner = Executors.newSingleThreadExecutor(new NamingThreadFactory("RefCleaner"));
    private final ExecutorService repoWriter;

    public KeyStorage(final Source source, final String defaultRef, final HashService hashService, final RefLockService clusterService, final String rootUser) {
        this.source = Objects.requireNonNull(source, "Source cannot be null");
        this.defaultRef = defaultRef == null ? Constants.R_HEADS + Constants.MASTER : defaultRef;
        this.rootUser = Objects.requireNonNull(rootUser);
        this.repoWriter = clusterService.getRepoWriter();
        this.cache = getMap(source, hashService, repoWriter, clusterService);
        addRef(this.defaultRef);
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
            LOG.warn("Trying to access non existent ref {}", ref, e);
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
            return Optional.empty();
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
        return Tree.of(List.of(Pair.of(key, false))).accept(Tree.DOT_FINDER);
    }

    private RefHolder getRefHolder(final String finalRef) {
        final RefHolder refHolder = cache.peek(finalRef);
        if (refHolder == null) {
            throw new WrappingAPIException(new RefNotFoundException(finalRef));
        }
        return refHolder;
    }

    @Override
    public void close() {
        shutDownExecutor(refCleaner);
        StreamSupport.stream(cache.entries().spliterator(), true).forEach(ce -> ce.getValue().close());
        cache.close();
        try {
            source.close();
        } catch (final Exception e) {
            LOG.warn("Error when closing source during shutdown", e);
        }
    }

    public static void shutDownExecutor(final ExecutorService service) {
        service.shutdown();
        try {
            service.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
    public CompletableFuture<Either<String, FailedToLock>> putKey(final String key, String ref, final ObjectStreamProvider data, final String oldVersion,
            final CommitMetaData commitMetaData) {
        Objects.requireNonNull(key, KEY_CANNOT_BE_NULL);
        Objects.requireNonNull(data, DATA_CANNOT_BE_NULL);
        Objects.requireNonNull(oldVersion, "oldVersion cannot be null");
        final RefHolder refHolder = getRefHolder(checkRef(ref));
        return refHolder.modifyKey(key, data, oldVersion, commitMetaData);
    }

    @Override
    public CompletableFuture<String> addKey(final String key, String branch, final ObjectStreamProvider data, final MetaData metaData,
            final CommitMetaData commitMetaData) {
        Objects.requireNonNull(key, KEY_CANNOT_BE_NULL);
        Objects.requireNonNull(data, DATA_CANNOT_BE_NULL);
        Objects.requireNonNull(metaData, "metaData cannot be null");

        if (checkKeyIsDotFile(key) || key.endsWith("/")) {
            throw new WrappingAPIException(new UnsupportedOperationException(key));
        }

        final String finalRef = checkRef(branch);
        final RefHolder refStore = getRefHolder(finalRef);
        return refStore.addKey(key, data, metaData, commitMetaData).thenApplyAsync(result -> {
            if (result.isRight()) {
                throw new WrappingAPIException(new KeyAlreadyExist(key, finalRef));
            }
            return result.getLeft();
        });
    }

    @Override
    public CompletableFuture<Either<String, FailedToLock>> putMetaData(final String key, String ref, final MetaData metaData, final String oldMetaDataVersion,
            final CommitMetaData commitMetaData) {
        Objects.requireNonNull(key, KEY_CANNOT_BE_NULL);
        Objects.requireNonNull(metaData, "metaData cannot be null");
        Objects.requireNonNull(oldMetaDataVersion, "metaDataVersion cannot be null");

        if (checkKeyIsDotFile(key)) {
            throw new WrappingAPIException(new UnsupportedOperationException(key));
        }
        final RefHolder refHolder = getRefHolder(checkRef(ref));
        return refHolder.modifyMetadata(key, metaData, oldMetaDataVersion, commitMetaData);
    }

    @Override
    public CompletableFuture<Either<String, FailedToLock>> delete(final String key, final String ref, final CommitMetaData commitMetaData) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(commitMetaData);

        if (checkKeyIsDotFile(key)) {
            throw new WrappingAPIException(new UnsupportedOperationException(key));
        }

        final String finalRef = checkRef(ref);
        if (key.endsWith("/")) {
            // We don't support deleting master .metadata files right now
            throw new WrappingAPIException(new UnsupportedOperationException(key));
        }
        final RefHolder refHolder = cache.peek(finalRef);
        if (refHolder != null) {
            try {
               return refHolder.deleteKey(key, commitMetaData);
            } catch (UncheckedIOException ioe) {
                consumeError(ioe);
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public List<Pair<String, StoreInfo>> getListForRef(final List<Pair<String, Boolean>> keyPairs, final String ref) {
        Objects.requireNonNull(keyPairs);
        final String finalRef = checkRef(ref);
        return Tree.of(keyPairs).accept(new Tree.Extractor())
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
    public CompletableFuture<Either<String, FailedToLock>> updateUser(final String key, String ref, final String realm, final String creatorUserName,
            final UserData data, final String version) {
        ref = checkRef(ref);
        final RefHolder refHolder = cache.peek(ref);
        if (refHolder == null) {
            throw new UnsupportedOperationException(key);
        }
        return refHolder.updateUser(realm + "/" + key, creatorUserName, data, version);
    }

    @Override
    public CompletableFuture<String> addUser(final String key, String ref, final String realm, final String creatorUserName, final UserData data) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(realm);
        Objects.requireNonNull(creatorUserName);
        Objects.requireNonNull(data);
        final String finalRef = checkRef(ref);
        if (rootUser.equals(key)) {
            throw new WrappingAPIException(new KeyAlreadyExist(key, finalRef));
        }
        final RefHolder refHolder = getRefHolder(finalRef);
        return refHolder.addUser(realm + "/" + key, creatorUserName, data).thenApplyAsync(postUser -> {
            if (postUser.isRight()) {
                throw new WrappingAPIException(new KeyAlreadyExist(key, finalRef));
            }
            return postUser.getLeft();
        });
    }

    @Override
    public void deleteUser(final String key, final String ref, final String realm, final String creatorUserName) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(realm);
        Objects.requireNonNull(creatorUserName);
        final RefHolder refHolder = cache.peek(checkRef(ref));
        if (refHolder != null) {
            refHolder.deleteUser(realm + "/" + key, creatorUserName).join();
        }
    }

    @Override
    public void reload(String ref) {
        final RefHolder refHolder = cache.peek(ref);
        if (refHolder != null) {
            refHolder.reload();
        }
    }

    @Override
    public void deleteRef(String ref) {
        LOG.info("Deleting {}", ref);
        final RefHolder removedValue = cache.peekAndRemove(ref);
        CompletableFuture.runAsync(() -> {
            if (removedValue != null) {
                removedValue.close();
                LOG.info("Ref {} is disposed", ref);
            }
        }, refCleaner);

    }

    private static Cache<String, RefHolder> getMap(final Source source, final HashService hashService, final ExecutorService repoWriter,
            RefLockService clusterService) {
        return new Cache2kBuilder<String, RefHolder>() {
        }
                .name(KeyStorage.class)
                .loader(new CacheLoader<String, RefHolder>() {
                    @Override
                    public RefHolder load(final String r) throws Exception {
                        if (r.startsWith("refs/tags/")) {
                            return new ReadOnlyRefHolder(r, source, hashService, repoWriter, clusterService);
                        }
                        final RefHolder refHolder = new RefHolder(r, source, hashService, repoWriter, clusterService);
                        refHolder.start();
                        return refHolder;
                    }
                })
                .build();
    }

    @Override
    public void addRef(String ref) {
        RefHolder refHolder = cache.peek(ref);
        if (refHolder == null) {
            LOG.info("Adding ref {}", ref);
            cache.get(ref);
        }
    }
}
