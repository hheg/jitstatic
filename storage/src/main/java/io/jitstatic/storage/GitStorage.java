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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
import io.jitstatic.utils.ErrorConsumingThreadFactory;
import io.jitstatic.utils.LinkedException;
import io.jitstatic.utils.Pair;
import io.jitstatic.utils.WrappingAPIException;

public class GitStorage implements Storage {

    private static final Logger LOG = LogManager.getLogger(GitStorage.class);
    private static final SourceHandler HANDLER = new SourceHandler();
    private final Map<String, Map<String, Optional<StoreInfo>>> cache = new ConcurrentHashMap<>();
    private final AtomicReference<Exception> fault = new AtomicReference<>();

    private final ExecutorService refExecutor;
    private final ExecutorService keyExecutor;
    private final Source source;
    private final String defaultRef;

    public GitStorage(final Source source, final String defaultRef) {
        this.source = Objects.requireNonNull(source, "Source cannot be null");
        this.refExecutor = Executors
                .newSingleThreadExecutor(new ErrorConsumingThreadFactory("ref", this::consumeError));
        this.keyExecutor = Executors
                .newSingleThreadExecutor(new ErrorConsumingThreadFactory("key", this::consumeError));
        this.defaultRef = defaultRef == null ? Constants.R_HEADS + Constants.MASTER : defaultRef;
    }

    public void reload(final List<String> refsToReload) {
        Objects.requireNonNull(refsToReload);
        final List<CompletableFuture<Void>> tasks = refsToReload.stream().map(ref -> {
            return CompletableFuture.supplyAsync(() -> {
                LOG.info("Reloading " + ref);
                final Map<String, Optional<StoreInfo>> map = cache.get(ref);
                if (map != null) {
                    return new HashSet<>(map.keySet());
                }
                return Collections.<String>emptySet();
            }, refExecutor).thenApplyAsync(files -> {
                return waitForTasks(refresh(ref, files)).thenApply(list -> {
                    final List<Exception> faults = list.stream().filter(Either::isRight).map(Either::getRight)
                            .collect(Collectors.toList());
                    if (!faults.isEmpty()) {
                        throw new LinkedException(faults);
                    }
                    return list.stream().filter(Either::isLeft).map(Either::getLeft).filter(Optional::isPresent)
                            .map(Optional::get).filter(p -> p.getRight() != null)
                            .collect(Collectors.toConcurrentMap(Pair::getLeft, p -> Optional.of(p.getRight())));
                });
            }).thenCompose(future -> future).thenAcceptAsync(map -> {
                if (map.size() > 0) {
                    final Map<String, Optional<StoreInfo>> originalMap = cache.get(ref);
                    originalMap.entrySet().stream().filter(e -> !map.containsKey(e.getKey()))
                            .forEach(e -> map.put(e.getKey(), e.getValue()));
                    cache.put(ref, map);
                } else {
                    cache.remove(ref);
                }
            }, refExecutor);
        }).collect(Collectors.toCollection(() -> new ArrayList<>(refsToReload.size())));
        waitForTasks(tasks).thenAccept(listOfEithers -> {
            final List<Exception> errors = listOfEithers.stream().filter(Either::isRight).map(e -> e.getRight())
                    .collect(Collectors.toList());
            if (!errors.isEmpty()) {
                consumeError(new LinkedException(errors));
            }
        }).join();
    }

    private static <T> CompletableFuture<List<Either<T, Exception>>> waitForTasks(
            final List<CompletableFuture<T>> tasks) {
        CompletableFuture<Void> all = CompletableFuture.allOf(tasks.toArray(new CompletableFuture[tasks.size()]));
        return all.thenApply(v -> tasks.stream().map(future -> {
            try {
                return Either.<T, Exception>left(future.join());
            } catch (final Exception e) {
                return Either.<T, Exception>right(e);
            }
        }).collect(Collectors.toList()));
    }

    private List<CompletableFuture<Optional<Pair<String, StoreInfo>>>> refresh(final String ref,
            final Set<String> map) {
        return map.stream().map(key -> {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return Optional.of(Pair.of(key, load(key, ref)));
                } catch (final RefNotFoundException ignore) {
                } catch (final Exception e) {
                    consumeError(new RuntimeException(key + " in " + ref + " had the following error", e));
                }
                return Optional.<Pair<String, StoreInfo>>empty();
            });
        }).collect(Collectors.toCollection(() -> new ArrayList<>(map.size())));
    }

    private void consumeError(final Exception e) {
        final Exception old = fault.getAndSet(e);
        if (old != null) {
            LOG.warn("Had an unrecorded unexpected error while loading store", old);
        }
    }

    private String checkRef(String ref) {
        if (ref == null) {
            ref = defaultRef;
        }
        return ref;
    }

    @Override
    public CompletableFuture<Optional<StoreInfo>> get(final String key, String ref) {
        Objects.requireNonNull(key);
       
        ref = checkRef(ref);
        final String finalRef = ref;
        final Map<String, Optional<StoreInfo>> refMap = cache.get(ref);
        if (refMap == null) {
            return CompletableFuture.supplyAsync(() -> {
                Map<String, Optional<StoreInfo>> map = cache.get(finalRef);
                if (map == null) {
                    map = new ConcurrentHashMap<>();
                    cache.put(finalRef, map);
                }
                return map;
            }, refExecutor).thenApplyAsync((map) -> {
                try {
                    return loadAndStore(map, key, finalRef);
                } catch (final RefNotFoundException e) {
                    removeCacheRef(finalRef, map);
                    return Optional.empty();
                }
            }, keyExecutor);
        }

        final Optional<StoreInfo> storeInfo = refMap.get(key);
        if (storeInfo == null) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return loadAndStore(refMap, key, finalRef);
                } catch (final RefNotFoundException e) {
                    return Optional.empty();
                }
            }, keyExecutor);
        }
        return CompletableFuture.completedFuture(storeInfo);
    }

    private Optional<StoreInfo> loadAndStore(final Map<String, Optional<StoreInfo>> keyStorage, final String key,
            final String ref) throws RefNotFoundException {
        if(checkKeyIsDotFile(key)) {
            return Optional.empty();
        }
        Optional<StoreInfo> storeInfoContainer = keyStorage.get(key);
        if (storeInfoContainer == null) {
            try {
                final StoreInfo storeInfo = load(key, ref);
                storeInfoContainer = Optional.ofNullable(storeInfo);
                keyStorage.put(key, storeInfoContainer);
            } catch (final RefNotFoundException e) {
                throw e;
            } catch (final Exception e) {
                consumeError(e);
            }
        }
        return storeInfoContainer;
    }

    private boolean checkKeyIsDotFile(final String key) {
        return Paths.get(key).toFile().getName().startsWith(".");       
    }

    private StoreInfo load(final String key, final String ref) throws RefNotFoundException, IOException {
        final SourceInfo sourceInfo = source.getSourceInfo(key, ref);
        if (sourceInfo != null) {
            if (sourceInfo.hasFailed()) {
                throw new RuntimeException(sourceInfo.getFailiure());
            }
            return readStoreInfo(sourceInfo);
        }
        return null;
    }

    @Override
    public void close() {
        refExecutor.shutdown();
        keyExecutor.shutdown();
        try {
            refExecutor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (final InterruptedException ignore) {
        }
        try {
            keyExecutor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (final InterruptedException ignore) {
        }
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
    public CompletableFuture<String> put(final String key, String ref, final byte[] data, final String oldVersion,
            final String message, final String userInfo, final String userEmail) {
        ref = checkRef(ref);
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(data, "data cannot be null");
        Objects.requireNonNull(oldVersion, "oldVersion cannot be null");
        Objects.requireNonNull(userInfo, "userInfo cannot be null");        

        if (Objects.requireNonNull(message, "message cannot be null").isEmpty()) {
            throw new IllegalArgumentException("message cannot be empty");
        }
        final String finalRef = ref;
        return CompletableFuture.supplyAsync(() -> {
            final Map<String, Optional<StoreInfo>> refMap = cache.get(finalRef);
            if (refMap == null) {
                throw new WrappingAPIException(new RefNotFoundException(finalRef));
            }
            final Optional<StoreInfo> storeInfo = refMap.get(key);
            if (storeInfo == null || !storeInfo.isPresent()) {
                throw new WrappingAPIException(new UnsupportedOperationException(key));
            }
            final String contentType = storeInfo.get().getStorageData().getContentType();
            final String newVersion = source.modify(key, finalRef, data, oldVersion, message, userInfo, userEmail)
                    .join();
            refreshKey(data, key, oldVersion, newVersion, refMap, contentType);
            return newVersion;
        }, keyExecutor);
    }

    private void refreshKey(final byte[] data, final String key, final String oldversion, final String newVersion,
            final Map<String, Optional<StoreInfo>> refMap, final String contentType) {
        final Optional<StoreInfo> si = refMap.get(key);
        final StoreInfo storeInfo = si.get();
        if (storeInfo.getVersion().equals(oldversion)) {
            refMap.put(key, Optional
                    .of(new StoreInfo(data, storeInfo.getStorageData(), newVersion, storeInfo.getMetaDataVersion())));
        }
    }

    private void refreshKey(final StorageData metaData, final String key, final String metaDataVersion,
            final String newVersion, final Map<String, Optional<StoreInfo>> refMap, String contentType) {
        final Optional<StoreInfo> si = refMap.get(key);
        final StoreInfo storeInfo = si.get();
        if (storeInfo.getMetaDataVersion().equals(metaDataVersion)) {
            refMap.put(key,
                    Optional.of(new StoreInfo(storeInfo.getData(), metaData, storeInfo.getVersion(), newVersion)));
        }
    }

    @Override
    public CompletableFuture<StoreInfo> add(final String key, String branch, final byte[] data,
            final StorageData metaData, final String message, final String userInfo, final String userMail) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(data);
        Objects.requireNonNull(metaData);
        Objects.requireNonNull(message);
        Objects.requireNonNull(userInfo);
        Objects.requireNonNull(userMail);
        
        branch = checkRef(branch);

        final String finalRef = branch;

        return CompletableFuture.supplyAsync(() -> {
            Map<String, Optional<StoreInfo>> refStore = cache.get(finalRef);
            if (refStore != null) {
                final Optional<StoreInfo> storeInfo = refStore.get(key);
                if (storeInfo != null && storeInfo.isPresent()) {
                    throw new WrappingAPIException(new KeyAlreadyExist(key, finalRef));
                }
            } else {
                refStore = new ConcurrentHashMap<>();
                cache.put(finalRef, refStore);
            }
            return refStore;
        }, refExecutor).thenApplyAsync((map) -> {
            SourceInfo sourceInfo = null;
            try {
                try {
                    sourceInfo = source.getSourceInfo(key, finalRef);
                } catch (final RefNotFoundException e) {
                    throw new WrappingAPIException(e);
                }
                if (sourceInfo != null) {
                    throw new WrappingAPIException(new KeyAlreadyExist(key, finalRef));
                }
                final Pair<String, String> version = source.addKey(key, finalRef, data, metaData, message, userInfo, userMail).join();
                final StoreInfo storeInfo = new StoreInfo(data, metaData, version.getLeft(), version.getRight());
                map.put(key, Optional.of(storeInfo));
                return storeInfo;
            } finally {
                if (sourceInfo == null) {
                    removeCacheRef(finalRef, map);
                }
            }
        }, keyExecutor);
    }

    private void removeCacheRef(final String finalRef, final Map<String, Optional<StoreInfo>> map) {
        refExecutor.execute(() -> {
            keyExecutor.execute(() -> {
                if (map.isEmpty()) {
                    cache.remove(finalRef);
                }
            });
        });
    }

    private StoreInfo readStoreInfo(final SourceInfo source) {
        try {
            final CompletableFuture<StorageData> supplyAsync = CompletableFuture.supplyAsync(() -> {
                try (final InputStream metaDataStream = source.getMetadataInputStream()) {
                    return HANDLER.readStorage(metaDataStream);
                } catch (final IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            try (final InputStream sourceStream = source.getSourceInputStream()) {
                final StoreInfo storeInfo = new StoreInfo(HANDLER.readStorageData(sourceStream), supplyAsync.join(),
                        source.getSourceVersion(), source.getMetaDataVersion());
                return storeInfo;
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        } catch (final CompletionException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof UncheckedIOException) {
                throw ((UncheckedIOException) cause);
            }
            throw e;
        }
    }

    @Override
    public CompletableFuture<String> putMetaData(final String key, String ref, final StorageData metaData,
            final String metaDataVersion, final String message, final String userInfo, final String userMail) {
        Objects.requireNonNull(metaData);
        Objects.requireNonNull(key);
        Objects.requireNonNull(metaDataVersion);
        Objects.requireNonNull(message);
        Objects.requireNonNull(userInfo);
        Objects.requireNonNull(userMail);
        ref = checkRef(ref);

        final String finalRef = ref;
        return CompletableFuture.supplyAsync(() -> {
            final Map<String, Optional<StoreInfo>> refMap = cache.get(finalRef);
            if (refMap == null) {
                throw new WrappingAPIException(new RefNotFoundException(finalRef));
            }
            final Optional<StoreInfo> storeInfo = refMap.get(key);
            if (storeInfo == null || !storeInfo.isPresent()) {
                throw new WrappingAPIException(new UnsupportedOperationException(key));
            }
            final String contentType = metaData.getContentType();
            final String newVersion = source
                    .modify(metaData, metaDataVersion, message, userInfo, userMail, key, finalRef).join();
            refreshKey(metaData, key, metaDataVersion, newVersion, refMap, contentType);
            return newVersion;
        }, keyExecutor);
    }

}
