package io.jitstatic.storage;

/*-
 * #%L
 * jitstatic
 * %%
 * Copyright (C) 2017 H.Hegardt
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
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.lib.Constants;

import com.spencerwi.either.Either;

import io.jitstatic.StorageData;
import io.jitstatic.hosted.KeyAlreadyExist;
import io.jitstatic.source.Source;
import io.jitstatic.source.SourceInfo;
import io.jitstatic.utils.LinkedException;
import io.jitstatic.utils.Pair;
import io.jitstatic.utils.ShouldNeverHappenException;
import io.jitstatic.utils.WrappingAPIException;

public class GitStorage implements Storage {

    private static final Logger LOG = LogManager.getLogger(GitStorage.class);
    private static final SourceHandler HANDLER = new SourceHandler();
    private final Map<String, RefHolder> cache = new ConcurrentHashMap<>();
    private final AtomicReference<Exception> fault = new AtomicReference<>();
    private final Source source;
    private final String defaultRef;

    public GitStorage(final Source source, final String defaultRef) {
        this.source = Objects.requireNonNull(source, "Source cannot be null");
        this.defaultRef = defaultRef == null ? Constants.R_HEADS + Constants.MASTER : defaultRef;
    }

    public void reload(final List<String> refsToReload) {
        Objects.requireNonNull(refsToReload);
        refsToReload.stream().forEach(ref -> {
            final RefHolder refHolder = cache.get(ref);
            if (refHolder != null) {
                try {
                    refHolder.lockWriteAll(() -> {
                        LOG.info("Reloading " + ref);
                        final Set<String> files = (refHolder != null ? new HashSet<>(refHolder.refCache.keySet()) : Set.<String>of());

                        final List<Either<Optional<Pair<String, StoreInfo>>, Exception>> refreshRef = refreshRef(ref, files);
                        final List<Exception> faults = refreshRef.stream().filter(Either::isRight).map(Either::getRight)
                                .collect(Collectors.toList());
                        if (!faults.isEmpty()) {
                            throw new LinkedException(faults);
                        }
                        final Map<String, Optional<StoreInfo>> newMap = refreshRef.stream().filter(Either::isLeft).map(Either::getLeft)
                                .flatMap(Optional::stream).filter(p -> p.getRight() != null)
                                .collect(Collectors.toConcurrentMap(Pair::getLeft, p -> Optional.of(p.getRight())));
                        if (newMap.size() > 0) {
                            final RefHolder originalRefHolder = cache.get(ref);
                            originalRefHolder.refCache.entrySet().stream().filter(e -> e.getValue().isPresent())
                                    // Can't trust that the old keys are loaded from the new branch...
                                    .filter(e -> !newMap.containsKey(e.getKey())).forEach(e -> newMap.put(e.getKey(), e.getValue()));
                            cache.put(ref, new RefHolder(ref, newMap));
                        } else {
                            cache.remove(ref);
                        }
                    });
                } catch (final FailedToLock ftl) {
                    LOG.info("Failed to reload {}", ftl.getMessage());
                    throw new ShouldNeverHappenException("Failed to reload " + ftl.getMessage());
                }
            }
        });
    }

    private List<Either<Optional<Pair<String, StoreInfo>>, Exception>> refreshRef(final String ref, final Set<String> files) {
        return files.stream().map(key -> {
            try {
                return Either.<Optional<Pair<String, StoreInfo>>, Exception>left(Optional.of(Pair.of(key, load(key, ref))));
            } catch (final RefNotFoundException ignore) {
            } catch (final Exception e) {
                return Either.<Optional<Pair<String, StoreInfo>>, Exception>right(
                        new RuntimeException(key + " in " + ref + " had the following error", e));
            }
            return Either.<Optional<Pair<String, StoreInfo>>, Exception>left(Optional.<Pair<String, StoreInfo>>empty());
        }).collect(Collectors.toCollection(() -> new ArrayList<>(files.size())));
    }

    private void consumeError(final Exception e) {
        fault.getAndSet(e);
        LOG.warn("Error occourred ", e);
    }

    private String checkRef(String ref) {
        if (ref == null) {
            ref = defaultRef;
        }
        return ref;
    }

    @Override
    public Supplier<Optional<StoreInfo>> getKey(final String key, String ref) {
        final String finalRef = checkRef(ref);
        RefHolder refHolder = cache.get(finalRef);

        if (refHolder == null) {
            refHolder = getMap(finalRef);
        }
        final Optional<StoreInfo> storeInfo = refHolder.getKey(key);
        if (storeInfo == null) {
            return loadAndStore(key, finalRef, refHolder);
        }
        return () -> storeInfo;
    }

    private RefHolder getMap(final String finalRef) {
        RefHolder map = cache.get(finalRef);
        if (map == null) {
            synchronized (cache) {
                map = new RefHolder(finalRef, new ConcurrentHashMap<>());
                cache.put(finalRef, map);
            }
        }
        return map;
    }

    private Supplier<Optional<StoreInfo>> loadAndStore(final String key, final String finalRef, final RefHolder refMap) {
        return () -> {
            if (checkKeyIsDotFile(key)) {
                return Optional.empty();
            }
            Optional<StoreInfo> storeInfoContainer = refMap.getKey(key);
            if (storeInfoContainer == null) {
                try {
                    final StoreInfo storeInfo = refMap.read(() -> {
                        try {
                            return load(key, finalRef);
                        } catch (final RefNotFoundException e) {
                            throw new LoadException(e);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
                    storeInfoContainer = store(key, refMap.refCache, storeInfo);
                } catch (final LoadException e) {
                    removeCacheRef(finalRef, refMap);
                    return Optional.empty();
                } catch (final Exception e) {
                    consumeError(e);
                }
            }
            return storeInfoContainer;
        };
    }

    private Optional<StoreInfo> store(final String key, final Map<String, Optional<StoreInfo>> refMap, final StoreInfo storeInfo) {
        Optional<StoreInfo> storeInfoContainer;
        if (storeInfo != null) {
            if (keyRequestedIsMasterMeta(key, storeInfo) || keyRequestedIsNormalKey(key, storeInfo)) {
                storeInfoContainer = Optional.of(storeInfo);
                refMap.put(key, storeInfoContainer);
            } else {
                /* StoreInfo could contain .metadata information but no key info */
                storeInfoContainer = Optional.empty();
                refMap.put(key, storeInfoContainer);
            }
        } else {
            storeInfoContainer = Optional.empty();
            refMap.put(key, storeInfoContainer);
        }
        return storeInfoContainer;
    }

    private boolean keyRequestedIsNormalKey(final String key, final StoreInfo storeInfo) {
        return !key.endsWith("/") && storeInfo.isNormalKey();
    }

    private boolean keyRequestedIsMasterMeta(final String key, final StoreInfo storeInfo) {
        return key.endsWith("/") && storeInfo.isMasterMetaData();
    }

    private boolean checkKeyIsDotFile(final String key) {
        // TODO Change this
        return Paths.get(key).toFile().getName().startsWith(".");
    }

    private StoreInfo load(final String key, final String ref) throws RefNotFoundException, IOException {
        final SourceInfo sourceInfo = source.getSourceInfo(key, ref);
        if (sourceInfo != null) {
            return readStoreInfo(sourceInfo);
        }
        return null;
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
    public Supplier<Either<String, FailedToLock>> put(final String key, String ref, final byte[] data, final String oldVersion,
            final String message, final String userInfo, final String userEmail) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(data, "data cannot be null");
        Objects.requireNonNull(oldVersion, "oldVersion cannot be null");
        Objects.requireNonNull(userInfo, "userInfo cannot be null");

        if (Objects.requireNonNull(message, "message cannot be null").isEmpty()) {
            throw new IllegalArgumentException("message cannot be empty");
        }
        final String finalRef = checkRef(ref);
        final RefHolder refMap = cache.get(finalRef);
        if (refMap == null) {
            throw new WrappingAPIException(new RefNotFoundException(finalRef));
        }
        return () -> {
            try {
                return Either.left(refMap.lockWrite(() -> {
                    final Optional<StoreInfo> storeInfo = refMap.getKey(key);
                    if (storageIsForbidden(storeInfo)) {
                        throw new WrappingAPIException(new UnsupportedOperationException(key));
                    }
                    final String newVersion = source.modify(key, finalRef, data, oldVersion, message, userInfo, userEmail);
                    refreshKey(data, key, oldVersion, newVersion, refMap.refCache, storeInfo.get().getStorageData().getContentType());
                    return newVersion;
                }, key));
            } catch (FailedToLock e) {
                return Either.right(e);
            }
        };
    }

    private void refreshKey(final byte[] data, final String key, final String oldversion, final String newVersion,
            final Map<String, Optional<StoreInfo>> refMap, final String contentType) {
        final Optional<StoreInfo> si = refMap.get(key);
        final StoreInfo storeInfo = si.get();
        if (storeInfo.getVersion().equals(oldversion)) {
            refMap.put(key, Optional.of(new StoreInfo(data, storeInfo.getStorageData(), newVersion, storeInfo.getMetaDataVersion())));
        }
    }

    private void refreshMetaData(final StorageData metaData, final String key, final String metaDataVersion, final String newVersion,
            final Map<String, Optional<StoreInfo>> refMap, String contentType) {
        final Optional<StoreInfo> si = refMap.get(key);
        final StoreInfo storeInfo = si.get();
        if (storeInfo.getMetaDataVersion().equals(metaDataVersion)) {
            if (storeInfo.isMasterMetaData()) {
                refMap.clear(); // TODO Don't clear all keys. Check which ones that could be left alone
                refMap.put(key, Optional.of(new StoreInfo(metaData, newVersion)));
            } else {
                refMap.put(key, Optional.of(new StoreInfo(storeInfo.getData(), metaData, storeInfo.getVersion(), newVersion)));
            }
        }
    }

    @Override
    public Supplier<StoreInfo> add(final String key, String branch, final byte[] data, final StorageData metaData, final String message,
            final String userInfo, final String userMail) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(data, "data cannot be null");
        Objects.requireNonNull(userInfo, "userInfo cannot be null");
        Objects.requireNonNull(metaData, "metaData cannot be null");
        Objects.requireNonNull(userMail, "userMail cannot be null");

        if (Objects.requireNonNull(message, "message cannot be null").isEmpty()) {
            throw new IllegalArgumentException("message cannot be empty");
        }

        final String finalRef = checkRef(branch);
        isRefATag(finalRef);

        final RefHolder refStore = checkIfKeyAlreadyExists(key, finalRef);
        return () -> refStore.write(() -> {
            SourceInfo sourceInfo = null;
            try {
                try {
                    sourceInfo = source.getSourceInfo(key, finalRef);
                } catch (final RefNotFoundException e) {
                    throw new WrappingAPIException(e);
                }
                if (sourceInfo != null && !sourceInfo.isMetaDataSource()) {
                    throw new WrappingAPIException(new KeyAlreadyExist(key, finalRef));
                }
                final Pair<String, String> version = source.addKey(key, finalRef, data, metaData, message, userInfo, userMail);
                final StoreInfo storeInfo = new StoreInfo(data, metaData, version.getLeft(), version.getRight());
                refStore.refCache.put(key, Optional.of(storeInfo));
                return storeInfo;
            } finally {
                if (sourceInfo == null) {
                    removeCacheRef(finalRef, refStore);
                }
            }
        });

    }

    private RefHolder checkIfKeyAlreadyExists(final String key, final String finalRef) {
        final RefHolder refStore = getMap(finalRef);
        final Optional<StoreInfo> storeInfo = refStore.getKey(key);
        if (storeInfo != null && storeInfo.isPresent()) {
            throw new WrappingAPIException(new KeyAlreadyExist(key, finalRef));
        }
        return refStore;
    }

    private void removeCacheRef(final String finalRef, final RefHolder newRefHolder) {
        synchronized (cache) {
            final RefHolder refHolder = cache.get(finalRef);
            if (refHolder == newRefHolder && refHolder.refCache.isEmpty()) {
                cache.remove(finalRef);
            }
        }
    }

    private StoreInfo readStoreInfo(final SourceInfo source) {
        try {
            final StorageData metaData = readMetaData(source);
            try (final InputStream sourceStream = source.getSourceInputStream()) {
                if (!metaData.isHidden()) {
                    if (sourceStream != null) { // Implicitly an master .metadata SourceInfo instance...
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

    private StorageData readMetaData(final SourceInfo source) {
        try (final InputStream metaDataStream = source.getMetadataInputStream()) {
            return HANDLER.readStorage(metaDataStream);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Supplier<Either<String, FailedToLock>> putMetaData(final String key, String ref, final StorageData metaData,
            final String metaDataVersion, final String message, final String userInfo, final String userMail) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(userInfo, "userInfo cannot be null");
        Objects.requireNonNull(metaData, "metaData cannot be null");
        Objects.requireNonNull(userMail, "userMail cannot be null");
        Objects.requireNonNull(metaDataVersion, "metaDataVersion cannot be null");
        Objects.requireNonNull(message, "message cannot be null");

        final String finalRef = checkRef(ref);
        isRefATag(finalRef);

        final RefHolder refMap = cache.get(finalRef);
        if (refMap == null) {
            throw new WrappingAPIException(new RefNotFoundException(finalRef));
        }
        return () -> {
            try {
                return Either.left(refMap.lockWrite(() -> {
                    checkIfPlainKeyExist(key, finalRef, refMap.refCache);
                    final Optional<StoreInfo> storeInfo = refMap.getKey(key);
                    if (storageIsForbidden(storeInfo)) {
                        throw new WrappingAPIException(new UnsupportedOperationException(key));
                    }
                    final String newVersion = source.modify(metaData, metaDataVersion, message, userInfo, userMail, key, finalRef);
                    refreshMetaData(metaData, key, metaDataVersion, newVersion, refMap.refCache, metaData.getContentType());
                    return newVersion;

                }, key));
            } catch (final FailedToLock e) {
                return Either.right(e);
            }
        };
    }

    /*
     * This has to be checked when a user modifies a .metadata file for a directory
     */
    private void checkIfPlainKeyExist(final String key, final String finalRef, final Map<String, Optional<StoreInfo>> refMap) {
        if (key.endsWith("/")) {
            final String plainKey = key.substring(0, key.length() - 1);
            Optional<StoreInfo> optional = refMap.get(plainKey);
            if (optional != null) {
                throw new WrappingAPIException(new KeyAlreadyExist(key, finalRef));
            }
        }
    }

    private boolean storageIsForbidden(final Optional<StoreInfo> storeInfo) {
        return storeInfo == null || !storeInfo.isPresent() || storeInfo.get().getStorageData().isProtected();
    }

    @Override
    public void delete(final String key, final String ref, final String user, final String message, final String userMail) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(user);
        Objects.requireNonNull(user);
        Objects.requireNonNull(message);
        Objects.requireNonNull(userMail);

        final String finalRef = checkRef(ref);
        isRefATag(finalRef);
        if (key.endsWith("/")) {
            // We don't support deleting master .metadata files right now
            throw new WrappingAPIException(new UnsupportedOperationException(key));
        }
        final RefHolder refHolder = cache.get(finalRef);
        if (refHolder != null) {
            refHolder.write(() -> {
                try {
                    source.delete(key, finalRef, user, message, userMail);
                } catch (final UncheckedIOException ioe) {
                    consumeError(ioe);
                }
                refHolder.refCache.put(key, Optional.empty());
            });
            synchronized (cache) {
                if (refHolder.refCache.isEmpty()) {
                    cache.remove(finalRef);
                }
            }
        }

    }

    private void isRefATag(final String finalRef) {
        if (finalRef.startsWith(Constants.R_TAGS)) {
            throw new UnsupportedOperationException("Tags cannot be modified");
        }
    }

}
