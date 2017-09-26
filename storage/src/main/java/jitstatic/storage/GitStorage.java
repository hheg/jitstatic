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
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonParser.Feature;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;

import jitstatic.source.Source;

public class GitStorage implements Storage {
	private static final Logger LOG = LogManager.getLogger(GitStorage.class);
	private final Map<String, StorageData> cache = new ConcurrentHashMap<>();
	private final ObjectMapper mapper = new ObjectMapper().enable(Feature.ALLOW_COMMENTS);
	
	private final Source source;

	private final AtomicReference<Exception> fault = new AtomicReference<>();
		
	public GitStorage(final Source source) {
		this.source = Objects.requireNonNull(source,"Source cannot be null");		
	}

	@Override
	public StorageData get(String key) {
		return cache.get(key);
	}

	@Override
	public void load() throws LoaderException {
		try (InputStream is = source.getSourceStream()){			
			readStorage(is);
		} catch (Exception e) {
			final Exception old = fault.getAndSet(e);
			if (old != null) {
				LOG.warn("Had an unrecorded unexpected error while loading store", e);
			}
			throw new LoaderException(e);
		}
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

	private void readStorage(final InputStream storageStream) throws IOException {
		final Set<String> keys = new HashSet<>();
		try (final JsonParser parser = mapper.getFactory().createParser(storageStream);) {
			parser.nextToken();
			while (parser.nextToken() == JsonToken.FIELD_NAME) {
				final String key = parser.getText();
				parser.nextToken();
				final StorageData readValue = parser.readValueAs(StorageData.class);
				keys.add(key);
				cache.put(key, readValue);
			}
		}

		final Set<String> cacheKeys = cache.keySet();
		cacheKeys.retainAll(keys);
	}
}
