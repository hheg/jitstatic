package io.jitstatic.storage.ref;

/*-
 * #%L
 * jitstatic
 * %%
 * Copyright (C) 2017 - 2019 H.Hegardt
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.StreamSupport;

import javax.annotation.Nullable;
import javax.inject.Singleton;

import org.cache2k.Cache;
import org.cache2k.Cache2kBuilder;
import org.cache2k.CacheEntry;
import org.cache2k.integration.CacheLoader;
import org.cache2k.integration.CacheLoaderException;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.jvnet.hk2.annotations.Service;
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
import io.jitstatic.hosted.StoreInfo;
import io.jitstatic.source.ObjectStreamProvider;
import io.jitstatic.source.Source;
import io.jitstatic.source.SourceInfo;
import io.jitstatic.storage.KeyAlreadyExist;
import io.jitstatic.utils.Pair;
import io.jitstatic.utils.VersionIsNotSame;
import io.jitstatic.utils.WrappingAPIException;
import io.jitstatic.utils.Functions.ThrowingSupplier;

@Singleton
@Service
@SuppressFBWarnings(value = "NP_OPTIONAL_RETURN_NULL", justification = "Map's returns null and there's a difference from a previous cached 'not found' value and a new 'not found'")
class LockServiceImpl implements LockService {

    private final Map<String, ActionData> keyMap;
    private final String ref;
    private final LocalRefLockService refLockService;
    private static final String KEYPREFIX = "key-";
    private static final String GLOBAL = "globallock";
    private final AtomicReference<Cache<String, Either<Optional<StoreInfo>, Pair<String, UserData>>>> refCache;
    private final Logger log;
    private final ExecutorService workStealingExecutor;
    private final Source source;
    private final ExecutorService repoWriter;

    public LockServiceImpl(final LocalRefLockService refLockService, final String ref, ExecutorService workStealingExecutor, final Source source,
            final ExecutorService repoWriter) {
        this.keyMap = new HashMap<>();
        this.refLockService = Objects.requireNonNull(refLockService);
        this.ref = Objects.requireNonNull(ref);
        this.refCache = new AtomicReference<>(getStorage(1000));
        this.log = LoggerFactory.getLogger(ref);
        this.workStealingExecutor = Objects.requireNonNull(workStealingExecutor);
        this.source = Objects.requireNonNull(source);
        this.repoWriter = Objects.requireNonNull(repoWriter);
    }

    private Cache<String, Either<Optional<StoreInfo>, Pair<String, UserData>>> getStorage(final int size) {
        return new Cache2kBuilder<String, Either<Optional<StoreInfo>, Pair<String, UserData>>>() {
        }.name(ref.replace("/", "-") + "-" + UUID.randomUUID())
                .loader(new CacheLoader<String, Either<Optional<StoreInfo>, Pair<String, UserData>>>() {
                    @Override
                    public Either<Optional<StoreInfo>, Pair<String, UserData>> load(final String key) throws Exception {
                        // TODO Cache2k doesn't have an asynchronous API, yet.
                        return key.startsWith(JitStaticConstants.USERS) ? internalLoadUserKey(key) : internalLoadKey(key);
                    }
                }).entryCapacity(size).build();
    }

    private Either<Optional<StoreInfo>, Pair<String, UserData>> internalLoadKey(final String key) {
        try {
            return Either.left(isStorable(key, internalLoad(key)));
        } catch (RefNotFoundException e) {
            throw new LoadException(e);
        }
    }

    @Nullable
    private StoreInfo internalLoad(final String key) throws RefNotFoundException {
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
            Either<Optional<StoreInfo>, Pair<String, UserData>> keyData = peek(plainKey);
            if (keyData == null) {
                keyData = internalLoadKey(plainKey);
            }
            if (keyData != null && keyData.getLeft().isPresent()) {
                throw new WrappingAPIException(new KeyAlreadyExist(key, ref));
            }
        }
    }

    private String getRequestedKey(final String key) {
        return KEYPREFIX + key;
    }

    @Override
    public void close() {
        refLockService.returnLock(this);
    }

    @Override
    public CompletableFuture<Either<String, FailedToLock>> fireEvent(final String key, final ActionData data) {
        return CompletableFuture.supplyAsync(() -> {
            final String requestedKey = getRequestedKey(key);
            if (!keyMap.containsKey(GLOBAL) && keyMap.putIfAbsent(requestedKey, data) == null) {
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        return Either.<String, FailedToLock>left(invoke(data));
                    } finally {
                        keyMap.remove(requestedKey);
                    }
                }, repoWriter);
            } else {
                return CompletableFuture.completedFuture(Either.<String, FailedToLock>right(new FailedToLock(getRef(), key)));
            }
        }, repoWriter).thenCompose(c -> c);
    }

    @Override
    public CompletableFuture<Either<String, FailedToLock>> fireEvent(String ref, Supplier<Exception> preRequisite, Supplier<DistributedData> action,
            Consumer<Exception> postAction) {
        return CompletableFuture.supplyAsync(() -> {
            if (keyMap.putIfAbsent(GLOBAL, ActionData.PLACEHOLDER) == null) {
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        Exception exception = preRequisite.get();
                        try {
                            if (exception == null) {
                                // invoke(action.get());
                                return Either.<String, FailedToLock>left(ref);
                            }
                        } finally {
                            postAction.accept(exception);
                        }
                        FailedToLock failedToLock = new FailedToLock(ref);
                        failedToLock.addSuppressed(exception);
                        return Either.<String, FailedToLock>right(failedToLock);
                    } finally {
                        keyMap.remove(GLOBAL);
                    }
                }, repoWriter);
            } else {
                return CompletableFuture.completedFuture(Either.<String, FailedToLock>right(new FailedToLock(ref)));
            }
        }, repoWriter).thenCompose(c -> c);
    }

    private String invoke(final ActionData data) {
        switch (data.getType()) {
        case ADD_KEY:
            return internalAddKey(data.getKey(), data.getData(), data.getMetaData(), data.getCommitMetaData());
        case ADD_USER:
            return internalAddUser(data.getKey(), data.getUserName(), data.getUserData());
        case DELETE_KEY:
            return internalDeleteKey(data.getKey(), data.getCommitMetaData());
        case DELETE_USER:
            return internalDeleteUser(data.getKey(), data.getUserName());
        case UPDATE_KEY:
            return internalUpdateKey(data.getKey(), data.getData(), data.getOldVersion(), data.getCommitMetaData());
        case UPDATE_METAKEY:
            return internalUpdateMetadata(data.getKey(), data.getMetaData(), data.getOldVersion(), data.getCommitMetaData());
        case UPDATE_USER:
            return internalUpdateUser(data.getKey(), data.getUserName(), data.getUserData(), data.getOldVersion());
        case WRITE_REPO:
        case READ_KEY:
        case READ_REPO:
        case READ_USER:
        default:
            break;
        }
        throw new IllegalArgumentException("" + data.getType());
    }

    public String getRef() { return ref; }

    @Nullable
    private Optional<StoreInfo> internalReadKey(final String key) {
        return unwrapCacheLoaderException(() -> {
            final Either<Optional<StoreInfo>, Pair<String, UserData>> data = refCache.get().get(key);
            if (data != null && data.isLeft()) {
                return data.getLeft();
            }
            return null;
        });
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

    private String internalAddKey(final String key, ObjectStreamProvider data, final MetaData metaData, final CommitMetaData commitMetaData) {
        final Optional<StoreInfo> storeInfo = unwrapCacheLoaderException(() -> internalReadKey(key));
        if (storeInfo != null && storeInfo.isPresent()) {
            throw new WrappingAPIException(new KeyAlreadyExist(key, ref));
        }
        final Pair<Pair<ThrowingSupplier<ObjectLoader, IOException>, String>, String> version = source.addKey(key, ref, data, metaData, commitMetaData);
        final Pair<ThrowingSupplier<ObjectLoader, IOException>, String> fileInfo = version.getLeft();
        final StoreInfo newStoreInfo = new StoreInfo(data.getObjectStreamProvider(fileInfo.getLeft(), RefHolder.THRESHOLD), metaData, fileInfo
                .getRight(), version
                        .getRight());
        if (newStoreInfo.getMetaData().isHidden()) {
            putKey(key, Optional.empty());
        } else {
            putKey(key, Optional.of(newStoreInfo));
        }
        return fileInfo.getRight();
    }

    private String internalUpdateKey(final String key, final ObjectStreamProvider data, final String oldVersion, final CommitMetaData commitMetaData) {
        final Optional<StoreInfo> keyHolder = internalReadKey(key);
        if (storageIsForbidden(keyHolder)) {
            throw new WrappingAPIException(new UnsupportedOperationException("modifyKey " + key));
        }
        final StoreInfo storeInfo = keyHolder.get();
        if (!oldVersion.equals(storeInfo.getVersion())) {
            throw new WrappingAPIException(new VersionIsNotSame(oldVersion, storeInfo.getVersion()));
        }

        Pair<StoreInfo, Pair<String, ThrowingSupplier<ObjectLoader, IOException>>> dataPair = Pair
                .of(storeInfo, source.updateKey(key, ref, data, commitMetaData));
        final StoreInfo newStoreInfo = dataPair.getLeft();
        final Pair<String, ThrowingSupplier<ObjectLoader, IOException>> newVersion = dataPair.getRight();
        putKeyFull(key, Either
                .left(Optional.of(new StoreInfo(data.getObjectStreamProvider(newVersion.getRight(), RefHolder.THRESHOLD), newStoreInfo
                        .getMetaData(), newVersion.getLeft(), newStoreInfo.getMetaDataVersion()))));
        return newVersion.getLeft();
    }

    private String internalDeleteKey(final String key, final CommitMetaData commitMetaData) {
        source.deleteKey(key, ref, commitMetaData);
        putKey(key, Optional.empty());
        return ObjectId.zeroId().name();
    }

    private boolean storageIsForbidden(final Optional<StoreInfo> storeInfo) {
        return storeInfo == null || !storeInfo.isPresent() || storeInfo.get().getMetaData().isProtected();
    }

    private String internalUpdateMetadata(final String key, final MetaData metaData, final String oldMetaDataVersion, final CommitMetaData commitMetaData) {
        checkIfPlainKeyExist(key);
        final Optional<StoreInfo> storeInfo = internalReadKey(key);
        if (storageIsForbidden(storeInfo)) {
            throw new WrappingAPIException(new UnsupportedOperationException(key));
        }
        if (!oldMetaDataVersion.equals(storeInfo.get().getMetaDataVersion())) {
            throw new WrappingAPIException(new VersionIsNotSame(oldMetaDataVersion, storeInfo.get().getMetaDataVersion()));
        }

        final String newMetaDataVersion = source.updateMetaData(metaData, oldMetaDataVersion, key, ref, commitMetaData);
        final StoreInfo si = storeInfo.get();
        if (si.isMasterMetaData()) {
            refCache.get().clear(); // Reminder
            putKey(key, Optional.of(new StoreInfo(metaData, newMetaDataVersion)));
        } else {
            putKey(key, Optional
                    .of(new StoreInfo(si.getStreamProvider(), metaData, si.getVersion(), newMetaDataVersion)));
        }
        return newMetaDataVersion;
    }

    void putKey(final String key, final Optional<StoreInfo> store) {
        putKeyFull(key, Either.left(store));
    }

    public void putKeyFull(final String key, final Either<Optional<StoreInfo>, Pair<String, UserData>> data) {
        refCache.get().put(key, data);
    }

    private Pair<String, UserData> internalGetUser(final String userKeyPath) {
        final String key = RefHolder.createFullUserKeyPath(userKeyPath);
        return unwrapCacheLoaderException(() -> {
            final Either<Optional<StoreInfo>, Pair<String, UserData>> data = refCache.get().get(key);
            if (data != null) {
                return data.getRight();
            }
            return null;
        });
    }

    private Pair<String, UserData> extractUserKeyData(final String userKeyPath, final String key) {
        final Either<Optional<StoreInfo>, Pair<String, UserData>> keyDataHolder = peek(key);
        final Pair<String, UserData> userKeyData;
        if (keyDataHolder == null || keyDataHolder.isLeft()) {
            userKeyData = internalGetUser(userKeyPath);
            if (userKeyData == null) {
                throw new WrappingAPIException(new UnsupportedOperationException(key));
            }
        } else {
            userKeyData = keyDataHolder.getRight();
        }
        return userKeyData;
    }

    private String internalUpdateUser(final String userKeyPath, final String username, final UserData data, final String version) {
        final String key = RefHolder.createFullUserKeyPath(userKeyPath);
        final Pair<String, UserData> userKeyData = extractUserKeyData(userKeyPath, key);
        if (!version.equals(userKeyData.getLeft())) {
            throw new WrappingAPIException(new VersionIsNotSame(version, userKeyData.getLeft()));
        }
        try {
            final String newVersion = source.updateUser(key, ref, username, data);
            putKeyFull(key, Either.right(Pair.of(newVersion, data)));
            return newVersion;
        } catch (RefNotFoundException e) {
            throw new WrappingAPIException(e);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to update " + key, e);
        }
    }

    private String internalAddUser(final String userKeyPath, final String username, final UserData data) {
        final String key = RefHolder.createFullUserKeyPath(userKeyPath);
        final Either<Optional<StoreInfo>, Pair<String, UserData>> keyDataHolder = refCache.get().get(key);
        if (keyDataHolder != null && keyDataHolder.isRight() && keyDataHolder.getRight().isPresent()) {
            throw new WrappingAPIException(new KeyAlreadyExist(key, ref));
        }
        try {
            final String newVersion = source.addUser(key, ref, username, data);
            putKeyFull(key, Either.right(Pair.of(newVersion, data)));
            return newVersion;
        } catch (RefNotFoundException e) {
            throw new WrappingAPIException(new UnsupportedOperationException(key));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to add " + key, e);
        }
    }

    private String internalDeleteUser(final String userKeyPath, final String username) {
        final String key = RefHolder.createFullUserKeyPath(userKeyPath);
        try {
            source.deleteUser(key, ref, username);
            refCache.get().remove(key);
            return ObjectId.zeroId().name();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete " + key, e);
        }
    }

    @Override
    public CompletableFuture<Pair<String, UserData>> getUser(String userKeyPath) {
        return CompletableFuture.supplyAsync(() -> internalGetUser(userKeyPath), repoWriter);
    }

    private Either<Optional<StoreInfo>, Pair<String, UserData>> internalLoadUserKey(final String key) {
        try {
            final Pair<String, UserData> user = source.getUser(key, ref);
            if (user != null && user.isPresent()) {
                return Either.right(user);
            }
            return Either.right(Pair.ofNothing());
        } catch (RefNotFoundException | IOException e) {
            throw new WrappingAPIException(e);
        }
    }

    @Override
    public <T> CompletableFuture<Either<T, FailedToLock>> enqueueAndReadBlock(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(() -> Either.left(supplier.get()), repoWriter);
    }

    @Override
    public Optional<StoreInfo> readKey(String key) {
        return internalReadKey(key);
    }

    @Override
    public CompletableFuture<List<String>> getList(String key, boolean recursive) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return source.getList(key, ref, recursive);
            } catch (RefNotFoundException e) {
                throw new WrappingAPIException(e);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, repoWriter);
    }

    @Override
    public void reload() {
        CompletableFuture.runAsync(((Supplier<Runnable>) () -> {
            log.info("Reloading {}", ref);
            final Cache<String, Either<Optional<StoreInfo>, Pair<String, UserData>>> oldRefCache = refCache
                    .compareAndExchange(refCache.get(), getStorage(RefHolder.MAX_ENTRIES));
            return () -> {
                StreamSupport.stream(oldRefCache.entries().spliterator(), true).filter(e -> {
                    final Either<Optional<StoreInfo>, Pair<String, UserData>> value = e.getValue();
                    return (value.isLeft() && value.getLeft().isPresent());
                }).map(CacheEntry::getKey).forEach(key -> refCache.get().get(key));
                oldRefCache.close();
                log.info("Reloaded {}", ref);
            };
        }).get(), workStealingExecutor);

    }

    @Override
    public boolean isEmpty() {
        return StreamSupport.stream(refCache.get().entries().spliterator(), true)
                .noneMatch(e -> e.getValue().fold(Optional<StoreInfo>::isPresent, u -> true));
    }

    @Override
    public Either<Optional<StoreInfo>, Pair<String, UserData>> peek(String key) {
        return refCache.get().peek(key);
    }
}
