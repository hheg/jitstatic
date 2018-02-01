package jitstatic.storage;

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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.lib.Constants;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonParser.Feature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spencerwi.either.Either;

import jitstatic.source.Source;
import jitstatic.source.SourceInfo;
import jitstatic.utils.WrappingAPIException;
import jitstatic.utils.ErrorConsumingThreadFactory;
import jitstatic.utils.LinkedException;
import jitstatic.utils.Pair;

public class GitStorage implements Storage {
	private static final Logger LOG = LogManager.getLogger(GitStorage.class);
	private static final ObjectMapper MAPPER = new ObjectMapper().enable(Feature.ALLOW_COMMENTS);

	private final Map<String, Map<String, StoreInfo>> cache = new ConcurrentHashMap<>();
	private final AtomicReference<Exception> fault = new AtomicReference<>();
	private final ExecutorService refExecutor;
	private final ExecutorService keyExecutor;
	private final Source source;
	private final String defaultRef;

	public GitStorage(final Source source, final String defaultRef) {
		this.source = Objects.requireNonNull(source, "Source cannot be null");
		this.refExecutor = Executors.newSingleThreadExecutor(new ErrorConsumingThreadFactory("ref", this::consumeError));
		this.keyExecutor = Executors.newSingleThreadExecutor(new ErrorConsumingThreadFactory("key", this::consumeError));
		this.defaultRef = defaultRef == null ? Constants.R_HEADS + Constants.MASTER : defaultRef;
	}

	public void reload(final List<String> refsToReload) {
		Objects.requireNonNull(refsToReload);
		final List<CompletableFuture<Void>> tasks = refsToReload.stream().map(ref -> {
			LOG.info("Reloading " + ref);
			return CompletableFuture.supplyAsync(() -> {
				final Map<String, StoreInfo> map = cache.get(ref);
				if (map != null) {
					return new HashSet<>(map.keySet());
				}
				return Collections.<String>emptySet();
			}, refExecutor).thenApplyAsync(files -> {
				return waitForTasks(refresh(ref, files)).thenApply(list -> {
					final List<Exception> faults = list.stream().filter(Either::isRight).map(Either::getRight).collect(Collectors.toList());
					if (!faults.isEmpty()) {
						throw new LinkedException(faults);
					}
					return list.stream().filter(Either::isLeft).map(Either::getLeft).filter(Optional::isPresent).map(Optional::get)
							.filter(p -> p.getRight() != null).collect(Collectors.toConcurrentMap(Pair::getLeft, Pair::getRight));
				});
			}).thenCompose(future -> future).thenAcceptAsync(map -> {
				if (map.size() > 0) {
					final Map<String, StoreInfo> originalMap = cache.get(ref);
					originalMap.entrySet().stream().filter(e -> !map.containsKey(e.getKey()))
							.forEach(e -> map.put(e.getKey(), e.getValue()));
					cache.put(ref, map);
				} else {
					cache.remove(ref);
				}
			}, refExecutor);
		}).collect(Collectors.toCollection(() -> new ArrayList<>(refsToReload.size())));
		// TODO Make detaching selectable
		waitForTasks(tasks).thenAccept(l -> {
			final List<Exception> errors = l.stream().filter(Either::isRight).map(e -> e.getRight()).collect(Collectors.toList());
			if (!errors.isEmpty()) {
				consumeError(new LinkedException(errors));
			}
		}).join();
	}

	private static <T> CompletableFuture<List<Either<T, Exception>>> waitForTasks(final List<CompletableFuture<T>> task) {
		CompletableFuture<Void> all = CompletableFuture.allOf(task.toArray(new CompletableFuture[task.size()]));
		return all.thenApply(v -> task.stream().map(future -> {
			try {
				return Either.<T, Exception>left(future.join());
			} catch (final Exception e) {
				return Either.<T, Exception>right(e);
			}
		}).collect(Collectors.toList()));
	}

	private List<CompletableFuture<Optional<Pair<String, StoreInfo>>>> refresh(final String ref, final Set<String> map) {
		return map.stream().map(key -> {
			return CompletableFuture.supplyAsync(() -> {
				try {
					return Optional.of(Pair.of(key, load(key, ref)));
				} catch (final RefNotFoundException ignore) {
				} catch (final Exception e) {
					consumeError(new RuntimeException(key + " in " + ref + " had the following error",e));
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

	@Override
	public Future<StoreInfo> get(final String key, String ref) {
		Objects.requireNonNull(key);
		if (ref == null) {
			ref = defaultRef;
		}
		final String r = ref;
		final Map<String, StoreInfo> refMap = cache.get(ref);
		if (refMap == null) {
			return CompletableFuture.supplyAsync(() -> {
				Map<String, StoreInfo> map = cache.get(r);
				if (map == null) {
					map = new ConcurrentHashMap<>();
					cache.put(r, map);
				}
				return map;
			}, refExecutor).thenApplyAsync((map) -> {
				final StoreInfo sd = this.loadAndStore(map, key, r);
				if (map.isEmpty()) {
					refExecutor.execute(() -> {
						if (map.isEmpty()) {
							cache.remove(r);
						}
					});
				}
				return sd;
			}, keyExecutor);
		}

		final StoreInfo storeInfo = refMap.get(key);
		if (storeInfo == null) {
			return CompletableFuture.supplyAsync(() -> {
				return this.loadAndStore(refMap, key, r);
			}, keyExecutor);
		}
		return CompletableFuture.completedFuture(storeInfo);
	}

	private StoreInfo loadAndStore(final Map<String, StoreInfo> v, final String key, final String r) {
		StoreInfo storeInfo = v.get(key);
		if (storeInfo == null) {
			try {
				storeInfo = load(key, r);
				if (storeInfo != null) {
					v.put(key, storeInfo);
				}
			} catch (final RefNotFoundException e) {
				return null;
			} catch (final Exception e) {
				consumeError(e);
			}
		}
		return storeInfo;
	}

	private StoreInfo load(final String key, final String ref) throws RefNotFoundException, IOException {
		final SourceInfo sourceInfo = source.getSourceInfo(key, ref);
		if (sourceInfo != null) {
			if(sourceInfo.hasFailed()) {
				throw new RuntimeException(sourceInfo.getFailiure());
			}
			final CompletableFuture<StorageData> metaDataFuture = CompletableFuture.supplyAsync(() -> {
				try (final InputStream is = sourceInfo.getMetadataInputStream()) {
					return readStorage(is);
				} catch (final IOException e) {
					throw new UncheckedIOException(e);
				}
			});

			try (final InputStream is = sourceInfo.getSourceInputStream()) {
				return new StoreInfo(readStorageData(is), metaDataFuture.join(), sourceInfo.getSourceVersion());
			}
		}
		return null;
	}

	private JsonNode readStorageData(InputStream is) throws JsonParseException, JsonMappingException, IOException {
		return MAPPER.readValue(is, JsonNode.class);
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

	private StorageData readStorage(final InputStream storageStream) throws IOException {
		try (final JsonParser parser = MAPPER.getFactory().createParser(storageStream);) {
			return parser.readValueAs(StorageData.class);
		}
	}

	@Override
	public CompletableFuture<String> put(final JsonNode data, final String oldVersion, final String message, final String userInfo,
			String userEmail, final String key, String ref) {
		if (ref == null) {
			ref = defaultRef;
		}
		Objects.requireNonNull(key, "key cannot be null");
		Objects.requireNonNull(data, "data cannot be null");
		Objects.requireNonNull(oldVersion, "oldVersion cannot be null");
		Objects.requireNonNull(userInfo, "userInfo cannot be null");

		if (Objects.requireNonNull(message, "message cannot be null").isEmpty()) {
			throw new IllegalArgumentException("message cannot be empty");
		}
		final String finalRef = ref;
		return CompletableFuture.supplyAsync(() -> {
			final Map<String, StoreInfo> refMap = cache.get(finalRef);
			if (refMap == null) {
				throw new WrappingAPIException(new RefNotFoundException(finalRef));
			}
			final StoreInfo storeInfo = refMap.get(key);
			if (storeInfo == null) {
				throw new WrappingAPIException(new UnsupportedOperationException(key));
			}
			final String newVersion = source.modify(data, oldVersion, message, userInfo, userEmail, key, finalRef).join();
			refreshKey(data, key, oldVersion, newVersion, refMap);
			return newVersion;
		}, keyExecutor);
	}

	private void refreshKey(final JsonNode data, final String key, final String oldversion, final String newVersion,
			final Map<String, StoreInfo> refMap) {
		final StoreInfo si = refMap.get(key);
		if (si.getVersion().equals(oldversion)) {
			final StorageData sd = si.getStorageData();
			refMap.put(key, new StoreInfo(data, new StorageData(sd.getUsers()), newVersion));
		}
	}
}
