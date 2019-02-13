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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import java.util.stream.StreamSupport;

import javax.annotation.Nullable;

import org.cache2k.Cache;
import org.cache2k.Cache2kBuilder;
import org.cache2k.CacheEntry;
import org.cache2k.integration.CacheLoader;
import org.cache2k.integration.CacheLoaderException;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.lib.ObjectLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.spencerwi.either.Either;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.jitstatic.CommitMetaData;
import io.jitstatic.JitStaticConstants;
import io.jitstatic.MetaData;
import io.jitstatic.auth.UserData;
import io.jitstatic.hosted.FailedToLock;
import io.jitstatic.hosted.KeyAlreadyExist;
import io.jitstatic.hosted.LoadException;
import io.jitstatic.hosted.RefLockHolder;
import io.jitstatic.hosted.StoreInfo;
import io.jitstatic.source.ObjectStreamProvider;
import io.jitstatic.source.Source;
import io.jitstatic.source.SourceInfo;
import io.jitstatic.utils.Functions.ThrowingSupplier;
import io.jitstatic.utils.Pair;
import io.jitstatic.utils.VersionIsNotSame;
import io.jitstatic.utils.WrappingAPIException;

@SuppressFBWarnings(value = "NP_OPTIONAL_RETURN_NULL", justification = "Map's returns null and there's a difference from a previous cached 'not found' value and a new 'not found'")
public class RefHolder implements RefLockHolder {
    private static final int MAX_ENTRIES = 2000;
    private static final int THRESHOLD = 1_000_000;
    private static final Logger LOG = LoggerFactory.getLogger(RefHolder.class);
    private volatile Cache<String, Either<Optional<StoreInfo>, Pair<String, UserData>>> refCache;
    private final RefLock lock = new RefLock();
    private final String ref;
    private final Source source;
    private final int threshold;
    private final HashService hashService;

    public RefHolder(final String ref, final Source source, final HashService hashService) {
        this.ref = ref;
        this.refCache = getStorage(MAX_ENTRIES);
        this.source = source;
        this.threshold = THRESHOLD;
        this.hashService = Objects.requireNonNull(hashService);
    }

    private Cache<String, Either<Optional<StoreInfo>, Pair<String, UserData>>> getStorage(final int size) {

        return new Cache2kBuilder<String, Either<Optional<StoreInfo>, Pair<String, UserData>>>() {
        }
                .name(ref.replaceAll("/", "-") + "-" + System.currentTimeMillis())
                .loader(new CacheLoader<String, Either<Optional<StoreInfo>, Pair<String, UserData>>>() {
                    @Override
                    public Either<Optional<StoreInfo>, Pair<String, UserData>> load(final String key) throws Exception {
                        return key.startsWith(JitStaticConstants.USERS) ? mapUserKey(key) : loadKey(key);
                    }
                })
                .entryCapacity(size)
                .build();
    }

    @Nullable
    public Optional<StoreInfo> readKey(final String key) {
        return unwrapCacheLoaderException(() -> {
            final Either<Optional<StoreInfo>, Pair<String, UserData>> data = refCache.get(key);
            if (data != null && data.isLeft()) {
                return data.getLeft();
            }
            return null;
        });
    }

    public void putKey(final String key, final Optional<StoreInfo> store) {
        refCache.put(key, Either.left(store));
    }

    public <T> Either<T, FailedToLock> lockWrite(final Supplier<T> supplier, final String key) {
        return lock.lockWrite(supplier, key, ref);
    }

    public <T> Either<T, FailedToLock> lockWriteAll(final Supplier<T> supplier) {
        return lock.lockWriteAll(supplier, ref);
    }

    public boolean isEmpty() {
        return StreamSupport.stream(refCache.entries().spliterator(), true)
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
                        return new StoreInfo(sourceInfo.getSourceProvider(), metaData, sourceInfo.getSourceVersion(),
                                sourceInfo.getMetaDataVersion());
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
            Either<Optional<StoreInfo>, Pair<String, UserData>> compute = refCache.get(plainKey);
            if (compute != null && compute.getLeft().isPresent()) {
                throw new WrappingAPIException(new KeyAlreadyExist(key, ref));
            }
        }
    }

    public Either<String, FailedToLock> modifyKey(final String key, final String finalRef, final ObjectStreamProvider data, final String oldVersion,
            final CommitMetaData commitMetaData) {
        return lockWrite(() -> {
            final Optional<StoreInfo> keyHolder = readKey(key);
            if (storageIsForbidden(keyHolder)) {
                throw new WrappingAPIException(new UnsupportedOperationException(key));
            }
            StoreInfo storeInfo = keyHolder.get();
            if (!oldVersion.equals(storeInfo.getVersion())) {
                throw new WrappingAPIException(new VersionIsNotSame());
            }
            final Pair<String, ThrowingSupplier<ObjectLoader, IOException>> newVersion = source.modifyKey(key, finalRef, data, oldVersion, commitMetaData);
            try {
                refCache.put(key, Either.left(Optional.of(
                        new StoreInfo(data.getObjectStreamProvider(newVersion.getRight(), threshold), storeInfo.getMetaData(),
                                newVersion.getLeft(), storeInfo.getMetaDataVersion()))));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return newVersion.getLeft();
        }, key);
    }

    public void deleteKey(final String key, final String finalRef, final CommitMetaData commitMetaData) {
        lockWrite(() -> {
            source.deleteKey(key, finalRef, commitMetaData);
            putKey(key, Optional.empty());
            return null;
        }, key);
    }

    public Either<String, FailedToLock> modifyMetadata(final MetaData metaData, final String oldMetaDataVersion,
            final CommitMetaData commitMetaData,
            final String key, final String finalRef) {
        return lockWrite(() -> {
            checkIfPlainKeyExist(key);
            final Optional<StoreInfo> storeInfo = readKey(key);
            if (storageIsForbidden(storeInfo)) {
                throw new WrappingAPIException(new UnsupportedOperationException(key));
            }
            final String newMetaDataVersion = source.modifyMetadata(metaData, oldMetaDataVersion, key, finalRef, commitMetaData);
            final StoreInfo si = storeInfo.get();
            if (si.isMasterMetaData()) {
                refCache.clear();
                putKey(key, Optional.of(new StoreInfo(metaData, newMetaDataVersion)));
            } else {
                putKey(key, Optional.of(
                        new StoreInfo(si.getStreamProvider(), metaData, si.getVersion(), newMetaDataVersion)));
            }
            return newMetaDataVersion;
        }, key);
    }

    private boolean storageIsForbidden(final Optional<StoreInfo> storeInfo) {
        return storeInfo == null || !storeInfo.isPresent() || storeInfo.get().getMetaData().isProtected();
    }

    String addKey(final String key, final String finalRef, final ObjectStreamProvider data, final MetaData metaData,
            final CommitMetaData commitMetaData) throws IOException {
        final Pair<Pair<ThrowingSupplier<ObjectLoader, IOException>, String>, String> version = source.addKey(key, finalRef, data, metaData, commitMetaData);
        final Pair<ThrowingSupplier<ObjectLoader, IOException>, String> fileInfo = version.getLeft();
        storeIfNotHidden(key, this, new StoreInfo(data.getObjectStreamProvider(fileInfo.getLeft(), threshold), metaData,
                fileInfo.getRight(), version.getRight()));
        return fileInfo.getRight();
    }

    private void storeIfNotHidden(final String key, final RefHolder refStore, final StoreInfo newStoreInfo) {
        if (newStoreInfo.getMetaData().isHidden()) {
            refStore.putKey(key, Optional.empty());
        } else {
            refStore.putKey(key, Optional.of(newStoreInfo));
        }
    }

    @Nullable
    public Pair<String, UserData> getUser(final String userKeyPath) {
        final String key = JitStaticConstants.USERS + userKeyPath;
        return unwrapCacheLoaderException(() -> {
            final Either<Optional<StoreInfo>, Pair<String, UserData>> computed = refCache.get(key);
            if (computed != null) {
                return computed.getRight();
            }
            return null;
        });
    }

    private Either<Optional<StoreInfo>, Pair<String, UserData>> mapUserKey(final String key) {
        Pair<String, UserData> user;
        try {
            user = source.getUser(key, ref);
        } catch (Exception e) {
            throw new WrappingAPIException(e);
        }
        if (user != null && user.isPresent()) {
            return Either.right(user);
        }
        return Either.right(Pair.ofNothing());
    }

    public Either<String, FailedToLock> updateUser(final String userKeyPath, final String username, UserData data,
            final String version) {
        final String key = JitStaticConstants.USERS + Objects.requireNonNull(userKeyPath);
        Objects.requireNonNull(data);
        Objects.requireNonNull(version);
        Objects.requireNonNull(username);
        return lockWrite(() -> {
            final Either<Optional<StoreInfo>, Pair<String, UserData>> keyDataHolder = refCache.peek(key);
            final Pair<String, UserData> userKeyData;
            if (keyDataHolder == null || keyDataHolder.isLeft()) {
                userKeyData = getUser(key);
                if (userKeyData == null) {
                    throw new WrappingAPIException(new UnsupportedOperationException(key));
                }
            } else {
                userKeyData = keyDataHolder.getRight();
            }
            if (!version.equals(userKeyData.getLeft())) {
                throw new WrappingAPIException(new VersionIsNotSame());
            }
            try {
                UserData input;
                if (data.getBasicPassword() != null) {
                    input = hashService.constructUserData(data.getRoles(), data.getBasicPassword());
                } else {
                    final UserData current = userKeyData.getRight();
                    input = new UserData(data.getRoles(), current.getBasicPassword(), current.getSalt(), current.getHash());
                }
                final String newVersion = source.updateUser(key, ref, username, input);
                refCache.put(key, Either.right(Pair.of(newVersion, input)));
                return newVersion;
            } catch (RefNotFoundException e) {
                throw new WrappingAPIException(new UnsupportedOperationException(key));
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to update " + key, e);
            }
        }, key);
    }

    public Either<String, FailedToLock> addUser(final String userKeyPath, final String username, final UserData data) {
        final String key = JitStaticConstants.USERS + Objects.requireNonNull(userKeyPath);
        return lockWrite(() -> {
            final Either<Optional<StoreInfo>, Pair<String, UserData>> keyDataHolder = refCache.get(key);
            if (keyDataHolder != null && keyDataHolder.isRight() && keyDataHolder.getRight().isPresent()) {
                throw new WrappingAPIException(new KeyAlreadyExist(key, ref));
            }
            try {
                String newVersion = source.addUser(key, ref, username, data);
                refCache.put(key, Either.right(Pair.of(newVersion, data)));
                return newVersion;
            } catch (RefNotFoundException e) {
                throw new WrappingAPIException(new UnsupportedOperationException(key));
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to add " + key, e);
            }
        }, key);
    }

    public void deleteUser(final String userKeyPath, final String username) {
        final String key = JitStaticConstants.USERS + Objects.requireNonNull(userKeyPath);
        lockWrite(() -> {
            try {
                refCache.remove(key);
                source.deleteUser(key, ref, username);
                return null;
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to delete " + key, e);
            }
        }, key);
    }
    // This is inefficient. Plan is to remove this
    private static class RefLock {

        private final ReentrantLock lock = new ReentrantLock(true);
        private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
        private final Map<String, Thread> activeKeys = new ConcurrentHashMap<>();

        public <T> Either<T, FailedToLock> lockWrite(final Supplier<T> supplier, final String key, final String ref) {
            if (tryLock(key)) {
                try {
                    return Either.left(supplier.get());
                } finally {
                    unlock(key);
                }
            }
            return Either.right(new FailedToLock(ref + "/" + key));
        }

        private boolean tryLock(final String key) {
            rwLock.readLock().lock();
            try {
                if (activeKeys.putIfAbsent(key, Thread.currentThread()) == null
                        || activeKeys.get(key) == Thread.currentThread()) {
                    lock.lock();
                    return true;
                }
                return false;
            } finally {
                rwLock.readLock().unlock();
            }
        }

        private void unlock(final String key) {
            lock.unlock();
            activeKeys.remove(key);
        }

        public <T> Either<T, FailedToLock> lockWriteAll(final Supplier<T> supplier, final String ref) {
            if (lock.tryLock()) {
                try {
                    rwLock.writeLock().lock();
                    return Either.left(supplier.get());
                } finally {
                    rwLock.writeLock().unlock();
                    lock.unlock();
                }
            }
            return Either.right(new FailedToLock(ref));
        }
    }

    public void reload() {
        final Supplier<Runnable> r = () -> {
            LOG.info("Reloading {}", ref);
            final Cache<String, Either<Optional<StoreInfo>, Pair<String, UserData>>> oldRefCache = refCache;
            refCache = getStorage(MAX_ENTRIES);
            return () -> {
                StreamSupport.stream(oldRefCache.entries().spliterator(), true)
                        .filter(e -> {
                            final Either<Optional<StoreInfo>, Pair<String, UserData>> value = e.getValue();
                            return (value.isLeft() && value.getLeft().isPresent());
                        }).map(CacheEntry<String, Either<Optional<StoreInfo>, Pair<String, UserData>>>::getKey)
                        .forEach(key -> CompletableFuture.runAsync(() -> refCache.get(key)));
                oldRefCache.close();
            };
        };
        r.get().run();
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
}
