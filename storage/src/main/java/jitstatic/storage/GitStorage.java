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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
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

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonParser.Feature;
import com.fasterxml.jackson.databind.ObjectMapper;

import jitstatic.source.Source;
import jitstatic.util.Pair;
import jitstatic.util.SinkIterator;

public class GitStorage implements Storage {
	private static final Logger LOG = LogManager.getLogger(GitStorage.class);
	private final Map<String, Map<String, StorageData>> cache = new ConcurrentHashMap<>();
	private final ObjectMapper mapper = new ObjectMapper().enable(Feature.ALLOW_COMMENTS);
	private final AtomicReference<Exception> fault = new AtomicReference<>();
	private final ExecutorService refExecutor;
	private final ExecutorService keyExecutor;
	private final Source source;
	private final String defaultRef;

	public GitStorage(final Source source, final String defaultRef) {
		this.source = Objects.requireNonNull(source, "Source cannot be null");
		refExecutor = Executors.newSingleThreadExecutor(new StorageThreadFactory("ref", this::consumeError));
		keyExecutor = Executors.newSingleThreadExecutor(new StorageThreadFactory("key", this::consumeError));
		this.defaultRef = defaultRef == null ? Constants.R_HEADS + Constants.MASTER : defaultRef;
	}

	public void reload(final List<String> refsToReload) {
		Objects.requireNonNull(refsToReload);
		 final List<CompletableFuture<Void>> tasks = refsToReload.stream().map(ref -> {
			return CompletableFuture.supplyAsync(() -> {
				final Map<String, StorageData> map = cache.get(ref);
				if (map != null) {
					return new HashSet<>(map.keySet());
				}
				return null;
			}, refExecutor).thenApplyAsync(files -> {
				if (files != null) {
					final Iterator<CompletableFuture<Optional<Pair<String, StorageData>>>> refreshTaskIterator = new SinkIterator<>(
							refresh(ref, files));
					final Map<String, StorageData> newMap = new ConcurrentHashMap<>(files.size());
					while (refreshTaskIterator.hasNext()) {
						final CompletableFuture<Optional<Pair<String, StorageData>>> next = refreshTaskIterator.next();
						if (next.isDone()) {
							final Optional<Pair<String, StorageData>> pair = unwrap(next);
							pair.ifPresent(p -> {
								final StorageData data = p.getRight();
								if (data != null) {
									newMap.put(p.getLeft(), data);
								}
							});
							refreshTaskIterator.remove();
						}
					}
					return newMap;
				}
				return null;
			}).thenAcceptAsync(map -> {
				if (map != null) {
					if (map.size() > 0) {
						final Map<String, StorageData> originalMap = cache.get(ref);
						originalMap.entrySet().stream().filter(e -> !map.containsKey(e.getKey()))
								.forEach(e -> map.put(e.getKey(), e.getValue()));
						cache.put(ref, map);
					} else {
						cache.remove(ref);
					}
				}
			}, refExecutor);
		}).collect(Collectors.toCollection(() -> new ArrayList<>(refsToReload.size())));
		// TODO Make detaching selectable
		waitForTasks(tasks);
	}

	private void waitForTasks(final List<CompletableFuture<Void>> t) {
		final Iterator<CompletableFuture<Void>> tasksIterator = new SinkIterator<>(t);
		while (tasksIterator.hasNext()) {
			final CompletableFuture<Void> next = tasksIterator.next();
			if (next.isDone()) {
				tasksIterator.remove();
			}
		}
	}

	private Optional<Pair<String, StorageData>> unwrap(
			final CompletableFuture<Optional<Pair<String, StorageData>>> next) {
		try {
			return next.get();
		} catch (InterruptedException | ExecutionException e) {
			consumeError(e);
		}
		return Optional.empty();
	}

	private List<CompletableFuture<Optional<Pair<String, StorageData>>>> refresh(final String ref,
			final Set<String> map) {
		return map.stream().map(key -> {
			return CompletableFuture.supplyAsync(() -> {
				try {
					return Optional.of(new Pair<String, StorageData>(key, load(key, ref)));
				} catch (final IOException e) {
					consumeError(e);
				}
				return Optional.<Pair<String, StorageData>>empty();
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
	public Future<StorageData> get(final String key, String ref) {
		Objects.requireNonNull(key);
		if (ref == null) {
			ref = defaultRef;
		}
		final String r = ref;
		final Map<String, StorageData> refMap = cache.get(ref);
		if (refMap == null) {
			return CompletableFuture.supplyAsync(() -> {
				Map<String, StorageData> map = cache.get(r);
				if (map == null) {
					map = new ConcurrentHashMap<>();
					cache.put(r, map);
				}
				return map;
			}, refExecutor).thenApplyAsync((map) -> {
				final StorageData sd = this.loadAndStore(map, key, r);
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

		final StorageData storageData = refMap.get(key);
		if (storageData == null) {
			return CompletableFuture.supplyAsync(() -> {
				return this.loadAndStore(refMap, key, r);
			}, keyExecutor);
		}
		return CompletableFuture.completedFuture(storageData);
	}

	private StorageData loadAndStore(final Map<String, StorageData> v, final String key, final String r) {
		StorageData storageData = v.get(key);
		if (storageData == null) {
			try {
				storageData = load(key, r);
				if (storageData != null) {
					v.put(key, storageData);
				}
			} catch (final Exception e) {
				if (e.getCause() != null && e.getCause() instanceof RefNotFoundException) {
					return null;
				} else {
					consumeError(e);
				}
			}
		}
		return storageData;
	}

	private StorageData load(final String key, final String ref) throws IOException {
		try (final InputStream is = source.getSourceStream(key, ref)) {
			if (is != null) {
				return readStorage(is);
			}
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

	private StorageData readStorage(final InputStream storageStream) throws IOException {
		try (final JsonParser parser = mapper.getFactory().createParser(storageStream);) {
			return parser.readValueAs(StorageData.class);
		}
	}

	@Override
	public Future<StorageData> delete(String key, String ref) {
		// TODO Auto-generated method stub
		return null;
	}
}
