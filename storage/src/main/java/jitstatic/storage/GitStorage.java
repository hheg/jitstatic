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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;

import com.fasterxml.jackson.core.JsonParser.Feature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

public class GitStorage implements Storage {
	private static final Logger LOG = LogManager.getLogger(GitStorage.class);
	private final Map<String, StorageData> cache = new ConcurrentHashMap<>();
	private final String fileStorage;
	private final GitWorkingRepositoryManager gitRepositoryManager;
	private final ObjectMapper mapper = new ObjectMapper();

	public GitStorage(final String fileStorage, final GitWorkingRepositoryManager gitRepositoryManager) {

		this.fileStorage = Objects.requireNonNull(fileStorage, "File storage is null");
		if (fileStorage.trim().isEmpty()) {
			throw new IllegalArgumentException("Storage file name's empty");
		}
		this.gitRepositoryManager = Objects.requireNonNull(gitRepositoryManager, "RepositoryManager is null");
		mapper.getFactory().enable(Feature.ALLOW_COMMENTS);
	}

	@Override
	public StorageData get(String key) {
		return cache.get(key);
	}

	private void refresh() throws LoaderException {
		try {
			gitRepositoryManager.refresh();
		} catch (GitAPIException e) {
			throw new LoaderException("Error while loading storage", e);
		}
	}

	private Path checkStorage() throws LoaderException {
		Path storage = gitRepositoryManager.resolvePath(fileStorage);
		if (storage == null) {
			throw new LoaderException(String.format("%s has been removed from repo. Not %s data.", fileStorage,
					(cache.isEmpty() ? "loading" : "reloading")));
		}
		return storage;
	}

	private Map<String, StorageData> readStorage(final Path storage) throws LoaderException {
		// TODO Change this to an event reader
		try (InputStream bc = Files.newInputStream(storage);) {
			return mapper.readValue(bc, new TypeReference<Map<String, StorageData>>() {
			});
		} catch (IOException e) {
			throw new LoaderException("Error while parsing data", e);
		}
	}

	@Override
	public void load() throws LoaderException {
		try {
			refresh();

			final Path storage = checkStorage();
			final Map<String, StorageData> map = readStorage(storage);

			Set<String> cacheKeySet = cache.keySet();
			Set<String> readKeySet = map.keySet();
			cacheKeySet.retainAll(readKeySet);
			cache.putAll(map);
		} catch (Exception e) {
			LOG.warn("Error while loading store", e);
			throw new LoaderException(e);
		}
	}

	@Override
	public void close() {
		StorageUtils.closeSilently(gitRepositoryManager);
	}

	@Override
	public void checkHealth() {
		try {
			// refresh();
			checkStorage();
			// readStorage(storage);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

}
