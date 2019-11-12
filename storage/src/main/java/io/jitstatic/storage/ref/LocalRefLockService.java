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

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.codahale.metrics.InstrumentedExecutorService;
import com.codahale.metrics.MetricRegistry;

import io.jitstatic.source.Source;
import io.jitstatic.storage.HashService;
import io.jitstatic.storage.NamingThreadFactory;

public class LocalRefLockService implements RefLockService {
    private final Map<String, LockService> refLockMap = new HashMap<>();
    private final ExecutorService repoWriter;

    public LocalRefLockService(final MetricRegistry metrics) {
        this.repoWriter = new InstrumentedExecutorService(Executors.newSingleThreadExecutor(new NamingThreadFactory("RepoWriter")), metrics);
    }

    @Override
    public synchronized void close() throws Exception {
        repoWriter.shutdown();
        repoWriter.awaitTermination(10, TimeUnit.SECONDS);
        refLockMap.forEach((k, v) -> v.close());
    }

    @Override
    public synchronized LockService getLockService(final String ref, final ExecutorService workstealingExecutor, final Source source,
            final HashService hashService) {
        final LockService map = refLockMap.get(ref);
        return map == null ? new LockServiceImpl(this, ref, workstealingExecutor, source, repoWriter) : map;
    }

    @Override
    public synchronized void returnLock(final LockService lock) {
        refLockMap.put(lock.getRef(), lock);
    }
}
