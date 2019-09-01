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
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.StreamSupport;

import javax.annotation.Nullable;

import org.cache2k.Cache;
import org.cache2k.Cache2kBuilder;
import org.cache2k.CacheEntry;
import org.cache2k.integration.CacheLoader;
import org.cache2k.integration.CacheLoaderException;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.spencerwi.either.Either;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.jitstatic.CommitMetaData;
import io.jitstatic.JitStaticConstants;
import io.jitstatic.MetaData;
import io.jitstatic.auth.UserData;
import io.jitstatic.hosted.DistributedData;
import io.jitstatic.hosted.FailedToLock;
import io.jitstatic.hosted.LoadException;
import io.jitstatic.hosted.RefLockHolder;
import io.jitstatic.hosted.StoreInfo;
import io.jitstatic.source.ObjectStreamProvider;
import io.jitstatic.source.Source;
import io.jitstatic.source.SourceInfo;
import io.jitstatic.utils.Functions.ThrowingSupplier;
import io.jitstatic.utils.Pair;
import io.jitstatic.utils.ShouldNeverHappenException;
import io.jitstatic.utils.VersionIsNotSame;
import io.jitstatic.utils.WrappingAPIException;

@SuppressFBWarnings(value = "NP_OPTIONAL_RETURN_NULL", justification = "Map's returns null and there's a difference from a previous cached 'not found' value and a new 'not found'")
public class RefHolder implements RefLockHolder, AutoCloseable {
    private static final int MAX_ENTRIES = 2000;
    private static final int THRESHOLD = 1_000_000;
    private static final Logger LOG = LoggerFactory.getLogger(RefHolder.class);
    private final AtomicReference<Cache<String, Either<Optional<StoreInfo>, Pair<String, UserData>>>> refCache;
    private final String ref;
    private final Source source;
    final int threshold;
    private final HashService hashService;
    private final LockService lock;
    private final RefLockService clusterService;

    public RefHolder(final String ref, final Source source, final HashService hashService, final ExecutorService repoWriter,
            final RefLockService clusterService) {
        this.ref = Objects.requireNonNull(ref);
        this.refCache = new AtomicReference<>(getStorage(MAX_ENTRIES));
        this.clusterService = clusterService;
        this.source = Objects.requireNonNull(source);
        this.threshold = THRESHOLD;
        this.hashService = Objects.requireNonNull(hashService);
        this.lock = clusterService.getLockService(ref);
    }

    public void start() {
        lock.register(this);
    }

    private Cache<String, Either<Optional<StoreInfo>, Pair<String, UserData>>> getStorage(final int size) {
        return new Cache2kBuilder<String, Either<Optional<StoreInfo>, Pair<String, UserData>>>() {
        }.name(ref.replaceAll("/", "-") + "-" + UUID.randomUUID())
                .loader(new CacheLoader<String, Either<Optional<StoreInfo>, Pair<String, UserData>>>() {
                    @Override
                    public Either<Optional<StoreInfo>, Pair<String, UserData>> load(final String key) throws Exception {
                        // TODO Cache2k doesn't have an asynchronous API, yet.
                        return unwrap(loadFully(key));
                    }
                }).entryCapacity(size).build();
    }

    private CompletableFuture<Either<Optional<StoreInfo>, Pair<String, UserData>>> loadFully(final String key) {
        // TODO Don't complete on this
        return CompletableFuture.completedFuture(key.startsWith(JitStaticConstants.USERS) ? loadUserKey(key) : loadKey(key));
    }

    @Nullable
    public Optional<StoreInfo> readKey(final String key) {
        return unwrapCacheLoaderException(() -> {
            final Either<Optional<StoreInfo>, Pair<String, UserData>> data = readKeyFull(key);
            if (data != null && data.isLeft()) {
                return data.getLeft();
            }
            return null;
        });
    }

    private Either<Optional<StoreInfo>, Pair<String, UserData>> pollForKey(final String key) {
        return refCache.get().peek(key);
    }

    private Either<Optional<StoreInfo>, Pair<String, UserData>> readKeyFull(final String key) {
        return refCache.get().get(key);
    }

    void putKey(final String key, final Optional<StoreInfo> store) {
        putKeyFull(key, Either.left(store));
    }

    private void putKeyFull(final String key, final Either<Optional<StoreInfo>, Pair<String, UserData>> data) {
        refCache.get().put(key, data);
    }

    public boolean isEmpty() {
        return StreamSupport.stream(refCache.get().entries().spliterator(), true)
                .noneMatch(e -> e.getValue().fold(Optional<StoreInfo>::isPresent, u -> true));
    }

    private Either<Optional<StoreInfo>, Pair<String, UserData>> loadKey(final String key) {
        try {
            return Either.left(isStorable(key, load(key)));
        } catch (RefNotFoundException e) {
            throw new LoadException(e);
        }
    }

    @Nullable
    private StoreInfo load(final String key) throws RefNotFoundException {
        final SourceInfo sourceInfo = source.getSourceInfo(key, ref);
        if (sourceInfo != null) {
            try {
                final MetaData metaData = sourceInfo.readMetaData();
                if (!metaData.isHidden()) {
                    if (!sourceInfo.isMetaDataSource()) {
                        return new StoreInfo(sourceInfo.getStreamProvider(), metaData, sourceInfo.getSourceVersion(), sourceInfo.getMetaDataVersion());
                    } else {
                        return new StoreInfo(metaData, sourceInfo.getMetaDataVersion());
                    }
                }
                return null;
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return null;
    }

    private Optional<StoreInfo> isStorable(final String key, final StoreInfo storeInfo) {
        if (storeInfo != null && (keyRequestedIsMasterMeta(key, storeInfo) || keyRequestedIsNormalKey(key, storeInfo))) {
            return Optional.of(storeInfo);
        } else {
            return Optional.empty();
        }
    }

    private boolean keyRequestedIsNormalKey(final String key, final StoreInfo storeInfo) {
        return !key.endsWith("/") && storeInfo.isNormalKey();
    }

    private boolean keyRequestedIsMasterMeta(final String key, final StoreInfo storeInfo) {
        return key.endsWith("/") && storeInfo.isMasterMetaData();
    }

    /*
     * This has to be checked when a user modifies a .metadata file for a directory
     */
    void checkIfPlainKeyExist(final String key) {
        if (key.endsWith("/")) {
            final String plainKey = key.substring(0, key.length() - 1);
            Either<Optional<StoreInfo>, Pair<String, UserData>> compute = pollForKey(plainKey);
            if (compute == null) {
                compute = loadKey(plainKey);
            }
            if (compute != null && compute.getLeft().isPresent()) {
                throw new WrappingAPIException(new KeyAlreadyExist(key, ref));
            }
        }
    }

    public CompletableFuture<Either<String, FailedToLock>> addKey(final String key, final ObjectStreamProvider data, final MetaData metaData,
            final CommitMetaData commitMetaData) {
        return lock.fireEvent(key, ActionData.addKey(key, data, metaData, commitMetaData));
    }

    String internalAddKey(final String key, ObjectStreamProvider data, final MetaData metaData, final CommitMetaData commitMetaData) {
        try {
            final Optional<StoreInfo> storeInfo = readKey(key);
            if (storeInfo != null && storeInfo.isPresent()) {
                throw new WrappingAPIException(new KeyAlreadyExist(key, ref));
            }
        } catch (final LoadException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof RefNotFoundException) {
                throw new WrappingAPIException((RefNotFoundException) cause);
            } else {
                throw e;
            }
        }
        final Pair<Pair<ThrowingSupplier<ObjectLoader, IOException>, String>, String> version = source.addKey(key, ref, data, metaData, commitMetaData);
        final Pair<ThrowingSupplier<ObjectLoader, IOException>, String> fileInfo = version.getLeft();
        final StoreInfo newStoreInfo = new StoreInfo(data.getObjectStreamProvider(fileInfo.getLeft(), threshold), metaData, fileInfo.getRight(),
                version.getRight());
        if (newStoreInfo.getMetaData().isHidden()) {
            putKey(key, Optional.empty());
        } else {
            putKey(key, Optional.of(newStoreInfo));
        }
        return fileInfo.getRight();
    }

    public CompletableFuture<Either<String, FailedToLock>> modifyKey(final String key, final ObjectStreamProvider data, final String oldVersion,
            final CommitMetaData commitMetaData) {
        return lock.fireEvent(key, ActionData.updateKey(key, data, oldVersion, commitMetaData));
    }

    String internalModifyKey(final String key, final ObjectStreamProvider data, final String oldVersion,
            final CommitMetaData commitMetaData) {
        final Optional<StoreInfo> keyHolder = readKey(key);
        if (storageIsForbidden(keyHolder)) {
            throw new WrappingAPIException(new UnsupportedOperationException("modifyKey " + key));
        }
        final StoreInfo storeInfo = keyHolder.get();
        if (!oldVersion.equals(storeInfo.getVersion())) {
            throw new WrappingAPIException(new VersionIsNotSame(oldVersion, storeInfo.getVersion()));
        }

        Pair<StoreInfo, Pair<String, ThrowingSupplier<ObjectLoader, IOException>>> dataPair = Pair.of(storeInfo,
                source.modifyKey(key, ref, data, commitMetaData));
        final StoreInfo newStoreInfo = dataPair.getLeft();
        final Pair<String, ThrowingSupplier<ObjectLoader, IOException>> newVersion = dataPair.getRight();
        putKeyFull(key,
                Either.left(Optional.of(new StoreInfo(data.getObjectStreamProvider(newVersion.getRight(), threshold), newStoreInfo.getMetaData(),
                        newVersion.getLeft(), newStoreInfo.getMetaDataVersion()))));
        return newVersion.getLeft();
    }

    public CompletableFuture<Either<String, FailedToLock>> deleteKey(final String key, final CommitMetaData commitMetaData) {
        return lock.fireEvent(key, ActionData.deleteKey(key, commitMetaData));
    }

    // TODO Should return something more useful?
    String internalDeleteKey(final String key, final CommitMetaData commitMetaData) {
        source.deleteKey(key, ref, commitMetaData);
        putKey(key, Optional.empty());
        return ObjectId.zeroId().name();
    }

    public CompletableFuture<Either<String, FailedToLock>> modifyMetadata(final String key, final MetaData metaData, final String oldMetaDataVersion,
            final CommitMetaData commitMetaData) {
        return lock.fireEvent(key, ActionData.updateMetakey(Objects.requireNonNull(key), Objects.requireNonNull(metaData),
                Objects.requireNonNull(oldMetaDataVersion), Objects.requireNonNull(commitMetaData)));
    }

    String internalModifyMetadata(final String key, final MetaData metaData, final String oldMetaDataVersion,
            final CommitMetaData commitMetaData) {
        checkIfPlainKeyExist(key);
        final Optional<StoreInfo> storeInfo = readKey(key);
        if (storageIsForbidden(storeInfo)) {
            throw new WrappingAPIException(new UnsupportedOperationException(key));
        }
        if (!oldMetaDataVersion.equals(storeInfo.get().getMetaDataVersion())) {
            throw new WrappingAPIException(new VersionIsNotSame(oldMetaDataVersion, storeInfo.get().getMetaDataVersion()));
        }

        Pair<Optional<StoreInfo>, String> data = Pair.of(storeInfo, source.modifyMetadata(metaData, oldMetaDataVersion, key, ref, commitMetaData));
        final String newMetaDataVersion = data.getRight();
        final StoreInfo si = data.getLeft().get();
        if (si.isMasterMetaData()) {
            refCache.get().clear();
            putKey(key, Optional.of(new StoreInfo(metaData, newMetaDataVersion)));
        } else {
            putKey(key, Optional
                    .of(new StoreInfo(si.getStreamProvider(), metaData, si.getVersion(), newMetaDataVersion)));
        }
        return newMetaDataVersion;

    }

    private boolean storageIsForbidden(final Optional<StoreInfo> storeInfo) {
        return storeInfo == null || !storeInfo.isPresent() || storeInfo.get().getMetaData().isProtected();
    }

    @Nullable
    public Pair<String, UserData> getUser(final String userKeyPath) {
        final String key = createFullUserKeyPath(userKeyPath);
        return unwrapCacheLoaderException(() -> {
            final Either<Optional<StoreInfo>, Pair<String, UserData>> computed = readKeyFull(key);
            if (computed != null) {
                return computed.getRight();
            }
            return null;
        });
    }

    private String createFullUserKeyPath(final String userKeyPath) {
        return JitStaticConstants.USERS + Objects.requireNonNull(userKeyPath);
    }

    private Either<Optional<StoreInfo>, Pair<String, UserData>> loadUserKey(final String key) {
        try {
            final Pair<String, UserData> user = source.getUser(key, ref);
            if (user != null && user.isPresent()) {
                return Either.right(user);
            }
            return Either.right(Pair.ofNothing());
        } catch (Exception e) {
            throw new WrappingAPIException(e);
        }
    }

    public CompletableFuture<Either<String, FailedToLock>> modifyUser(final String userKeyPath, final String username, final UserData data,
            final String version) {
        final String key = createFullUserKeyPath(userKeyPath);
        return lock.fireEvent(key,
                ActionData.updateUser(userKeyPath, Objects.requireNonNull(username), Objects.requireNonNull(data), Objects.requireNonNull(version)));
    }

    String internalUpdateUser(final String userKeyPath, final String username, final UserData data,
            final String version) {
        final String key = createFullUserKeyPath(userKeyPath);
        final Either<Optional<StoreInfo>, Pair<String, UserData>> keyDataHolder = pollForKey(key);
        final Pair<String, UserData> userKeyData;
        if (keyDataHolder == null || keyDataHolder.isLeft()) {
            userKeyData = getUser(userKeyPath);
            if (userKeyData == null) {
                throw new WrappingAPIException(new UnsupportedOperationException(key));
            }
        } else {
            userKeyData = keyDataHolder.getRight();
        }
        if (!version.equals(userKeyData.getLeft())) {
            throw new WrappingAPIException(new VersionIsNotSame(version, userKeyData.getLeft()));
        }
        try {
            final UserData input = generateUser(data, userKeyData);
            final String newVersion = source.updateUser(key, ref, username, input);
            Pair<String, UserData> p = Pair.of(newVersion, input);
            putKeyFull(key, Either.right(p));
            return p.getLeft();
        } catch (RefNotFoundException e) {
            throw new WrappingAPIException(new UnsupportedOperationException(key));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to update " + key, e);
        }

    }

    private UserData generateUser(final UserData data, final Pair<String, UserData> userKeyData) {
        if (data.getBasicPassword() != null) {
            return hashService.constructUserData(data.getRoles(), data.getBasicPassword());
        } else {
            final UserData current = userKeyData.getRight();
            return new UserData(data.getRoles(), current.getBasicPassword(), current.getSalt(), current.getHash());
        }
    }

    public CompletableFuture<Either<String, FailedToLock>> addUser(final String userKeyPath, final String username, final UserData data) {
        final String key = createFullUserKeyPath(userKeyPath);
        return lock.fireEvent(key, ActionData.addUser(userKeyPath, username, data));
    }

    String internalAddUser(final String userKeyPath, final String username, final UserData data) {
        final String key = createFullUserKeyPath(userKeyPath);
        final Either<Optional<StoreInfo>, Pair<String, UserData>> keyDataHolder = readKeyFull(key);
        if (keyDataHolder != null && keyDataHolder.isRight() && keyDataHolder.getRight().isPresent()) {
            throw new WrappingAPIException(new KeyAlreadyExist(key, ref));
        }
        try {
            final String newVersion = source.addUser(key, ref, username, data);
            Pair<String, UserData> p = Pair.of(newVersion, data);
            putKeyFull(key, Either.right(p));
            return p.getLeft();
        } catch (RefNotFoundException e) {
            throw new WrappingAPIException(new UnsupportedOperationException(key));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to add " + key, e);
        }
    }

    public CompletableFuture<Either<String, FailedToLock>> deleteUser(final String userKeyPath, final String userName) {
        final String key = createFullUserKeyPath(userKeyPath);
        return lock.fireEvent(key, ActionData.deleteUser(userKeyPath, userName));
    }

    String internalDeleteUser(final String userKeyPath, final String username) {
        final String key = createFullUserKeyPath(userKeyPath);
        try {
            source.deleteUser(key, ref, username);
            refCache.get().remove(key);
            return ObjectId.zeroId().name();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete " + key, e);
        }
    }

    @Deprecated
    static <T> T unwrap(final CompletableFuture<T> future) {
        try {
            return future.orTimeout(5, TimeUnit.SECONDS).join();
        } catch (CompletionException ce) {
            final Throwable cause = ce.getCause();
            if (cause instanceof WrappingAPIException) {
                throw (WrappingAPIException) cause;
            }
            if (cause instanceof LoadException) {
                throw (LoadException) cause;
            }
            if (cause instanceof UncheckedIOException) {
                throw (UncheckedIOException) cause;
            }
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new ShouldNeverHappenException("Error unwrapping future ", ce);
        }
    }

    public void reload() {
        CompletableFuture.runAsync(((Supplier<Runnable>) () -> {
            LOG.info("Reloading {}", ref);
            final Cache<String, Either<Optional<StoreInfo>, Pair<String, UserData>>> oldRefCache = refCache.compareAndExchange(refCache.get(),
                    getStorage(MAX_ENTRIES));
            return () -> {
                StreamSupport.stream(oldRefCache.entries().spliterator(), true).filter(e -> {
                    final Either<Optional<StoreInfo>, Pair<String, UserData>> value = e.getValue();
                    return (value.isLeft() && value.getLeft().isPresent());
                }).map(CacheEntry::getKey)
                        .forEach(key -> refCache.get().get(key));
                oldRefCache.close();
                LOG.info("Reloaded {}", ref);
            };
        }).get());
    }

    private <T> T unwrapCacheLoaderException(final Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (CacheLoaderException cle) {
            final Throwable cause = cle.getCause();
            if (cause != null) {
                if (cause instanceof LoadException) {
                    throw (LoadException) cause;
                }
                if (cause instanceof UncheckedIOException) {
                    throw (UncheckedIOException) cause;
                }
                if (cause instanceof WrappingAPIException) {
                    throw (WrappingAPIException) cause;
                }
                if (cause instanceof RuntimeException) {
                    throw (RuntimeException) cause;
                }
            }
            throw cle;
        }
    }

    @Override
    public void close() {
        // NOOP
    }

    @Override
    public <T> CompletableFuture<Either<T, FailedToLock>> enqueueAndReadBlock(final Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(() -> Either.left(supplier.get()), clusterService.getRepoWriter());
    }

    @Override
    public CompletableFuture<Either<String, FailedToLock>> enqueueAndBlock(final Supplier<Exception> preRequisite, final Supplier<DistributedData> action,
            final Consumer<Exception> postAction) {
        return lock.fireEvent(ref, preRequisite, action, postAction);
    }
}
