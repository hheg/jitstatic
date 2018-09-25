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
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.RefNotFoundException;

import com.spencerwi.either.Either;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.jitstatic.MetaData;
import io.jitstatic.hosted.FailedToLock;
import io.jitstatic.hosted.KeyAlreadyExist;
import io.jitstatic.hosted.LoadException;
import io.jitstatic.hosted.RefHolderLock;
import io.jitstatic.hosted.SourceHandler;
import io.jitstatic.hosted.StoreInfo;
import io.jitstatic.source.Source;
import io.jitstatic.source.SourceInfo;
import io.jitstatic.utils.LinkedException;
import io.jitstatic.utils.Pair;
import io.jitstatic.utils.WrappingAPIException;

@SuppressFBWarnings(value = "NP_OPTIONAL_RETURN_NULL", justification = "")
public class RefHolder implements RefHolderLock {

    private static final Logger LOG = LogManager.getLogger(RefHolder.class);
    private static final SourceHandler HANDLER = new SourceHandler();

    private volatile Map<String, Optional<StoreInfo>> refCache;
    private final ReentrantReadWriteLock refLock = new ReentrantReadWriteLock(true);
    private final Map<String, Thread> activeKeys = new ConcurrentHashMap<>();
    private final String ref;
    private final Source source;

    public RefHolder(final String ref, final Map<String, Optional<StoreInfo>> refCache, final Source source) {
        this.ref = ref;
        this.refCache = new ConcurrentHashMap<>();
        this.source = source;
    }

    public Optional<StoreInfo> getKey(final String key) {
        return refCache.get(key);
    }

    public void putKey(final String key, final Optional<StoreInfo> store) {
        refCache.put(key, store);
    }

    public <T> Either<T, FailedToLock> lockWrite(final Supplier<T> supplier, final String key) {
        if (tryLock(key)) {
            try {
                return Either.left(supplier.get());
            } finally {
                unlock(key);
            }
        }
        return Either.right(new FailedToLock(ref));
    }

    public <T> T write(final Supplier<T> supplier) {
        refLock.writeLock().lock();
        try {
            return supplier.get();
        } finally {
            refLock.writeLock().unlock();
        }
    }

    public void write(final Runnable runnable) {
        refLock.writeLock().lock();
        try {
            runnable.run();
        } finally {
            refLock.writeLock().unlock();
        }
    }

    public <T> T read(final Supplier<T> supplier) {
        refLock.readLock().lock();
        try {
            return supplier.get();
        } finally {
            refLock.readLock().unlock();
        }
    }

    private boolean tryLock(final String key) {
        if (activeKeys.putIfAbsent(key, Thread.currentThread()) == null || activeKeys.get(key) == Thread.currentThread()) {
            refLock.writeLock().lock();
            return true;
        }
        return false;
    }

    private void unlock(final String key) {
        activeKeys.remove(key);
        refLock.writeLock().unlock();
    }

    public boolean reloadAll(final Runnable runnable) {
        if (refLock.isWriteLockedByCurrentThread()) {
            runnable.run();
            return true;
        } else {
            return false;
        }
    }

    public <T> Either<T, FailedToLock> lockWriteAll(final Supplier<T> supplier) {
        if (refLock.writeLock().tryLock()) {
            try {
                return Either.left(supplier.get());
            } finally {
                refLock.writeLock().unlock();
            }
        }
        return Either.right(new FailedToLock(ref));
    }

    private Set<String> getFiles() {
        return refCache.entrySet().stream().filter(e -> e.getValue().isPresent()).map(Entry::getKey).collect(Collectors.toSet());
    }

    public boolean isEmpty() {
        return !refCache.values().stream().filter(Optional::isPresent).flatMap(Optional::stream).findAny().isPresent();
    }

    void refreshKey(final byte[] data, final String key, final String oldversion, final String newVersion, final String contentType) {
        refCache.computeIfPresent(key, (k, si) -> {
            if (si.isPresent()) {
                final StoreInfo storeInfo = si.get();
                if (oldversion.equals(storeInfo.getVersion())) {
                    return Optional.of(new StoreInfo(data, storeInfo.getStorageData(), newVersion, storeInfo.getMetaDataVersion()));
                }
            }
            return null;
        });
    }

    public void refreshMetaData(final MetaData metaData, final String key, final String oldMetaDataVersion,
            final String newMetaDataVersion) {
        write(() -> {
            final Optional<StoreInfo> storeInfo = refCache.get(key);
            storeInfo.ifPresent(si -> {
                if (oldMetaDataVersion.equals(si.getMetaDataVersion())) {
                    if (si.isMasterMetaData()) {
                        refCache.clear();
                        putKey(key, Optional.of(new StoreInfo(metaData, newMetaDataVersion)));
                    } else {
                        putKey(key, Optional.of(new StoreInfo(si.getData(), metaData, si.getVersion(), newMetaDataVersion)));
                    }
                }
            });
        });
    }

    private Map<String, Optional<StoreInfo>> refreshFiles(final Set<String> files) {
        final List<Either<Optional<Pair<String, StoreInfo>>, Exception>> refreshRef = refreshRef(files);
        final List<Exception> faults = refreshRef.stream().filter(Either::isRight).map(Either::getRight).collect(Collectors.toList());
        if (!faults.isEmpty()) {
            throw new LinkedException(faults);
        }
        final Map<String, Optional<StoreInfo>> newMap = refreshRef.stream().filter(Either::isLeft).map(Either::getLeft)
                .flatMap(Optional::stream).filter(p -> p.getRight() != null)
                .collect(Collectors.toConcurrentMap(Pair::getLeft, p -> Optional.of(p.getRight())));
        return newMap;
    }

    private List<Either<Optional<Pair<String, StoreInfo>>, Exception>> refreshRef(final Set<String> files) {
        return files.stream().map(key -> {
            try {
                return Either.<Optional<Pair<String, StoreInfo>>, Exception>left(Optional.of(Pair.of(key, load(key))));
            } catch (final RefNotFoundException ignore) {
            } catch (final Exception e) {
                return Either.<Optional<Pair<String, StoreInfo>>, Exception>right(
                        new RuntimeException(key + " in " + ref + " had the following error", e));
            }
            return Either.<Optional<Pair<String, StoreInfo>>, Exception>left(Optional.<Pair<String, StoreInfo>>empty());
        }).collect(Collectors.toCollection(() -> new ArrayList<>(files.size())));
    }

    public Optional<StoreInfo> loadAndStore(final String key) {
        Optional<StoreInfo> storeInfoContainer = getKey(key);
        if (storeInfoContainer == null) {
            final StoreInfo storeInfo = read(() -> {
                try {
                    return load(key);
                } catch (final RefNotFoundException e) {
                    throw new LoadException(e);
                }
            });
            storeInfoContainer = store(key, storeInfo);
        }
        return storeInfoContainer;
    }

    private StoreInfo load(final String key) throws RefNotFoundException {
        final SourceInfo sourceInfo = source.getSourceInfo(key, ref);
        if (sourceInfo != null) {
            return readStoreInfo(sourceInfo);
        }
        return null;
    }

    private StoreInfo readStoreInfo(final SourceInfo source) {
        try {
            final MetaData metaData = readMetaData(source);
            if (!metaData.isHidden()) {
                try (final InputStream sourceStream = source.getSourceInputStream()) {
                    if (sourceStream != null) { // Implicitly a master .metadata SourceInfo instance...
                        return new StoreInfo(HANDLER.readStorageData(sourceStream), metaData, source.getSourceVersion(),
                                source.getMetaDataVersion());
                    } else {
                        return new StoreInfo(metaData, source.getMetaDataVersion());
                    }
                }
            }
            return null;
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private MetaData readMetaData(final SourceInfo source) {
        try (final InputStream metaDataStream = source.getMetadataInputStream()) {
            return HANDLER.readStorage(metaDataStream);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Optional<StoreInfo> store(final String key, final StoreInfo storeInfo) {
        final Map<String, Optional<StoreInfo>> refMap = refCache;
        Optional<StoreInfo> storeInfoContainer;
        if (storeInfo != null) {
            if (keyRequestedIsMasterMeta(key, storeInfo) || keyRequestedIsNormalKey(key, storeInfo)) {
                storeInfoContainer = Optional.of(storeInfo);
            } else {
                /* StoreInfo could contain .metadata information but no key info */
                storeInfoContainer = Optional.empty();
            }
        } else {
            storeInfoContainer = Optional.empty();
        }
        final Optional<StoreInfo> current = refMap.putIfAbsent(key, storeInfoContainer);
        return current != null && current.isPresent() ? current : storeInfoContainer;
    }

    private boolean keyRequestedIsNormalKey(final String key, final StoreInfo storeInfo) {
        return !key.endsWith("/") && storeInfo.isNormalKey();
    }

    private boolean keyRequestedIsMasterMeta(final String key, final StoreInfo storeInfo) {
        return key.endsWith("/") && storeInfo.isMasterMetaData();
    }

    public boolean refresh() {
        LOG.info("Reloading " + ref);
        final Set<String> files = getFiles();
        final Map<String, Optional<StoreInfo>> newMap = refreshFiles(files);
        boolean isRefreshed = newMap.size() > 0;
        if (isRefreshed) {
            refCache = newMap;
        }
        return isRefreshed;
    }

    /*
     * This has to be checked when a user modifies a .metadata file for a directory
     */
    void checkIfPlainKeyExist(final String key) {
        if (key.endsWith("/")) {
            final String plainKey = key.substring(0, key.length() - 1);
            Optional<StoreInfo> optional = refCache.get(plainKey);
            if (optional != null && optional.isPresent()) {
                throw new WrappingAPIException(new KeyAlreadyExist(key, ref));
            }
        }
    }

    public Either<String, FailedToLock> modifyKey(String key, String finalRef, byte[] data, String oldVersion, String message,
            String userInfo, String userEmail) {
        return lockWrite(() -> {
            final Optional<StoreInfo> storeInfo = getKey(key);
            if (storageIsForbidden(storeInfo)) {
                throw new WrappingAPIException(new UnsupportedOperationException(key));
            }
            final String newVersion = source.modifyKey(key, finalRef, data, oldVersion, message, userInfo, userEmail);
            refreshKey(data, key, oldVersion, newVersion, storeInfo.get().getStorageData().getContentType());
            return newVersion;
        }, key);

    }

    public void deleteKey(String key, String finalRef, String user, String message, String userMail) {
        write(() -> {
            source.deleteKey(key, finalRef, user, message, userMail);
            putKey(key, Optional.empty());
        });
    }

    public Either<String, FailedToLock> modifyMetadata(MetaData metaData, String oldMetaDataVersion, String message, String userInfo,
            String userMail, String key, String finalRef) {
        return lockWrite(() -> {
            checkIfPlainKeyExist(key);
            final Optional<StoreInfo> storeInfo = getKey(key);
            if (storageIsForbidden(storeInfo)) {
                throw new WrappingAPIException(new UnsupportedOperationException(key));
            }
            final String newMetaDataVersion = source.modifyMetadata(metaData, oldMetaDataVersion, message, userInfo, userMail, key,
                    finalRef);
            refreshMetaData(metaData, key, oldMetaDataVersion, newMetaDataVersion);
            return newMetaDataVersion;
        }, key);
    }

    private boolean storageIsForbidden(final Optional<StoreInfo> storeInfo) {
        return storeInfo == null || !storeInfo.isPresent() || storeInfo.get().getStorageData().isProtected();
    }

    public String addKey(String key, String finalRef, byte[] data, MetaData metaData, String message, String userInfo, String userMail) {
        final Pair<String, String> version = source.addKey(key, finalRef, data, metaData, message, userInfo, userMail);
        storeIfNotHidden(key, finalRef, this, new StoreInfo(data, metaData, version.getLeft(), version.getRight()));
        return version.getLeft();
    }

    private void storeIfNotHidden(final String key, final String ref, final RefHolder refStore, final StoreInfo newStoreInfo) {
        if (newStoreInfo.getStorageData().isHidden()) {
            refStore.putKey(key, Optional.empty());
        } else {
            refStore.putKey(key, Optional.of(newStoreInfo));
        }
    }
}
