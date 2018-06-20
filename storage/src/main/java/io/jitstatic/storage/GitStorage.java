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

import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.lib.Constants;

import com.spencerwi.either.Either;

import io.jitstatic.StorageData;
import io.jitstatic.hosted.FailedToLock;
import io.jitstatic.hosted.KeyAlreadyExist;
import io.jitstatic.hosted.LoadException;
import io.jitstatic.hosted.RefHolder;
import io.jitstatic.hosted.StoreInfo;
import io.jitstatic.source.Source;
import io.jitstatic.source.SourceInfo;
import io.jitstatic.utils.Pair;
import io.jitstatic.utils.Path;
import io.jitstatic.utils.ShouldNeverHappenException;
import io.jitstatic.utils.WrappingAPIException;

public class GitStorage implements Storage {

    private static final Logger LOG = LogManager.getLogger(GitStorage.class);
    private final Map<String, RefHolder> cache = new ConcurrentHashMap<>();
    private final AtomicReference<Exception> fault = new AtomicReference<>();
    private final Source source;
    private final String defaultRef;

    public GitStorage(final Source source, final String defaultRef) {
        this.source = Objects.requireNonNull(source, "Source cannot be null");
        this.defaultRef = defaultRef == null ? Constants.R_HEADS + Constants.MASTER : defaultRef;
    }
    
    public RefHolder getRefHolderLock(final String ref) {
        return getRefHolder(ref);
    }

    public void reload(final List<String> refsToReload) {
        Objects.requireNonNull(refsToReload);
        refsToReload.stream().forEach(ref -> {
            final RefHolder refHolder = cache.get(ref);
            if (refHolder != null) {
                try {
                    refHolder.reloadAll(() -> {
                        boolean isRefreshed = refHolder.refresh();
                        if (!isRefreshed) {
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
    public Optional<StoreInfo> getKey(final String key, String ref) {
        if (checkKeyIsDotFile(key)) {
            return Optional.empty();
        }
        final String finalRef = checkRef(ref);
        final RefHolder refHolder = getRefHolder(finalRef);
        final Optional<StoreInfo> storeInfo = refHolder.getKey(key);
        if (storeInfo == null) {
            try {
                return refHolder.loadAndStore(key);
            } catch (final LoadException e) {
                removeCacheRef(finalRef, refHolder);
                return Optional.empty();
            } catch (final Exception e) {
                consumeError(e);
            }
        }
        return storeInfo;
    }
    
    private boolean checkKeyIsDotFile(final String key) {
        return Path.of(key).getLastElement().startsWith(".");
    }

    private RefHolder getRefHolder(final String finalRef) {
        RefHolder refHolder = cache.get(finalRef);
        if (refHolder == null) {
            refHolder = cache.computeIfAbsent(finalRef, (r) -> new RefHolder(r, new ConcurrentHashMap<>(), source));            
        }
        return refHolder;
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
    public Either<String, FailedToLock> put(final String key, String ref, final byte[] data, final String oldVersion, final String message,
            final String userInfo, final String userEmail) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(data, "data cannot be null");
        Objects.requireNonNull(oldVersion, "oldVersion cannot be null");
        Objects.requireNonNull(userInfo, "userInfo cannot be null");

        if (Objects.requireNonNull(message, "message cannot be null").isEmpty()) {
            throw new IllegalArgumentException("message cannot be empty");
        }
        final String finalRef = checkRef(ref);
        final RefHolder refHolder = cache.get(finalRef);
        if (refHolder == null) {
            throw new WrappingAPIException(new RefNotFoundException(finalRef));
        }

        try {
            return Either.left(refHolder.lockWrite(() -> {
                final Optional<StoreInfo> storeInfo = refHolder.getKey(key);
                if (storageIsForbidden(storeInfo)) {
                    throw new WrappingAPIException(new UnsupportedOperationException(key));
                }
                final String newVersion = source.modify(key, finalRef, data, oldVersion, message, userInfo, userEmail);
                refHolder.refreshKey(data, key, oldVersion, newVersion, storeInfo.get().getStorageData().getContentType());
                return newVersion;
            }, key));
        } catch (FailedToLock e) {
            return Either.right(e);
        }
    }

    @Override
    public StoreInfo add(final String key, String branch, final byte[] data, final StorageData metaData, final String message,
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
        return refStore.write(() -> {
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
                refStore.putKey(key, Optional.of(storeInfo));
                return storeInfo;
            } finally {
                if (sourceInfo == null) {
                    removeCacheRef(finalRef, refStore);
                }
            }
        });
    }

    private RefHolder checkIfKeyAlreadyExists(final String key, final String finalRef) {
        final RefHolder refStore = getRefHolder(finalRef);
        final Optional<StoreInfo> storeInfo = refStore.getKey(key);
        if (storeInfo != null && storeInfo.isPresent()) {
            throw new WrappingAPIException(new KeyAlreadyExist(key, finalRef));
        }
        return refStore;
    }

    private void removeCacheRef(final String finalRef, final RefHolder newRefHolder) {
        synchronized (cache) {
            final RefHolder refHolder = cache.get(finalRef);
            if (refHolder == newRefHolder && refHolder.isEmpty()) {
                cache.remove(finalRef);
            }
        }
    }

    @Override
    public Either<String, FailedToLock> putMetaData(final String key, String ref, final StorageData metaData, final String metaDataVersion,
            final String message, final String userInfo, final String userMail) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(userInfo, "userInfo cannot be null");
        Objects.requireNonNull(metaData, "metaData cannot be null");
        Objects.requireNonNull(userMail, "userMail cannot be null");
        Objects.requireNonNull(metaDataVersion, "metaDataVersion cannot be null");
        Objects.requireNonNull(message, "message cannot be null");

        final String finalRef = checkRef(ref);
        isRefATag(finalRef);

        final RefHolder refHolder = cache.get(finalRef);
        if (refHolder == null) {
            throw new WrappingAPIException(new RefNotFoundException(finalRef));
        }

        try {
            return Either.left(refHolder.lockWrite(() -> {
                refHolder.checkIfPlainKeyExist(key);
                final Optional<StoreInfo> storeInfo = refHolder.getKey(key);
                if (storageIsForbidden(storeInfo)) {
                    throw new WrappingAPIException(new UnsupportedOperationException(key));
                }
                final String newVersion = source.modify(metaData, metaDataVersion, message, userInfo, userMail, key, finalRef);
                refHolder.refreshMetaData(metaData, key, metaDataVersion, newVersion, metaData.getContentType());
                return newVersion;

            }, key));
        } catch (final FailedToLock e) {
            return Either.right(e);
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
                refHolder.putKey(key, Optional.empty());
            });
            synchronized (cache) {
                if (refHolder.isEmpty()) {
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
