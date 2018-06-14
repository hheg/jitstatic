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

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

class RefHolder {

    final Map<String, Optional<StoreInfo>> refCache;
    private final ReentrantReadWriteLock refLock = new ReentrantReadWriteLock(true);
    private final ReentrantLock keyLock = new ReentrantLock(true);
    private final Map<String, Thread> activeKeys = new ConcurrentHashMap<>();
    private final String ref;

    public RefHolder(final String ref, final Map<String, Optional<StoreInfo>> refCache) {
        this.ref = ref;
        this.refCache = refCache;
    }

    public Optional<StoreInfo> getKey(final String key) {
        return refCache.get(key);
    }

    public void putKey(final String key, final Optional<StoreInfo> store) {
        refCache.put(key, store);
    }

    public <T> T lockWrite(final Supplier<T> supplier, final String key) throws FailedToLock {
        if (tryLock(key)) {
            try {
                return supplier.get();
            } finally {
                unlock(key);
            }

        }
        throw new FailedToLock(ref);
    }

    public void lockWrite(final Runnable runnable, final String key) throws FailedToLock {
        if (tryLock(key)) {
            try {
                runnable.run();
                return;
            } finally {
                unlock(key);
            }
        }
        throw new FailedToLock(ref);

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

    private boolean tryLock(final String key) throws FailedToLock {
        keyLock.lock();
        try {
            if (!activeKeys.containsKey(key)) {
                activeKeys.put(key, Thread.currentThread());
            }
        } finally {
            keyLock.unlock();
        }
        if (activeKeys.get(key) == Thread.currentThread()) {
            refLock.writeLock().lock();
            return true;
        }
        return false;
    }

    private void unlock(final String key) {
        activeKeys.remove(key);
        refLock.writeLock().unlock();
    }

    public void lockWriteAll(final Runnable runnable) throws FailedToLock {
        if (refLock.writeLock().tryLock()) {
            try {
                runnable.run();
                return;
            } finally {
                refLock.writeLock().unlock();
            }
        }
        throw new FailedToLock(ref);
    }
}
