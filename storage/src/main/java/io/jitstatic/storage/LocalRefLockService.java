package io.jitstatic.storage;

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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.spencerwi.either.Either;

import io.jitstatic.hosted.DistributedData;
import io.jitstatic.hosted.FailedToLock;

public class LocalRefLockService implements RefLockService {
    private final Map<String, LockService> refLockMap = new HashMap<>();
    private final ExecutorService repoWriter = Executors.newSingleThreadExecutor(new NamingThreadFactory("RepoWriter"));

    @Override
    public void close() throws Exception {
        repoWriter.shutdown();
        repoWriter.awaitTermination(10, TimeUnit.SECONDS);
        refLockMap.forEach((k, v) -> v.close());
    }

    @Override
    public synchronized LockService getLockService(final String ref) {
        final LockService map = refLockMap.get(ref);
        return map == null ? new LocalLock(this, ref) : map;
    }

    @Override
    public synchronized void returnLock(final LockService keys) {
        refLockMap.put(keys.getRef(), keys);
    }

    private static class LocalLock implements LockService {

        private final Map<String, ActionData> keyMap;
        private final String ref;
        private final LocalRefLockService refLockService;
        private static final String KEYPREFIX = "key-";
        private static final String GLOBAL = "globallock";
        private RefHolder refHolder;

        public LocalLock(final LocalRefLockService refLockService, final String ref) {
            this.keyMap = new HashMap<>();
            this.refLockService = refLockService;
            this.ref = ref;
        }

        private String getRequestedKey(final String key) {
            return key == null ? GLOBAL : KEYPREFIX + key;
        }

        @Override
        public void close() {
            refLockService.returnLock(this);
        }

        @Override
        public void register(RefHolder refHolder) {
            this.refHolder = refHolder;
        }

        @Override
        public CompletableFuture<Either<String, FailedToLock>> fireEvent(final String key, final ActionData data) {
            return CompletableFuture.supplyAsync(() -> {
                final String requestedKey = getRequestedKey(key);
                if (keyMap.putIfAbsent(requestedKey, data) == null) {
                    return CompletableFuture.supplyAsync(() -> {
                        try {
                            return Either.<String, FailedToLock>left(invoke(data));
                        } finally {
                            keyMap.remove(requestedKey);
                        }
                    }, refLockService.getRepoWriter());
                } else {
                    return CompletableFuture.completedFuture(Either.<String, FailedToLock>right(new FailedToLock(getRef(), key)));
                }
            }, refLockService.getRepoWriter()).thenCompose(c -> c);
        }

        private String invoke(final ActionData data) {
            switch (data.getType()) {
            case ADD_KEY:
                return refHolder.internalAddKey(data.getKey(), data.getData(), data.getMetaData(), data.getCommitMetaData());
            case ADD_USER:
                return refHolder.internalAddUser(data.getKey(), data.getUserName(), data.getUserData());
            case DELETE_KEY:
                return refHolder.internalDeleteKey(data.getKey(), data.getCommitMetaData());
            case DELETE_USER:
                return refHolder.internalDeleteUser(data.getKey(), data.getUserName());
            case UPDATE_KEY:
                return refHolder.internalModifyKey(data.getKey(), data.getData(), data.getOldVersion(), data.getCommitMetaData());
            case UPDATE_METAKEY:
                return refHolder.internalModifyMetadata(data.getKey(), data.getMetaData(), data.getOldVersion(), data.getCommitMetaData());
            case UPDATE_USER:
                return refHolder.internalUpdateUser(data.getKey(), data.getUserName(), data.getUserData(), data.getOldVersion());
            case WRITE_REPO:
            case READ_KEY:
            case READ_REPO:
            case READ_USER:
            default:
                break;
            }
            throw new IllegalArgumentException("" + data.getType());
        }

        @Override
        public CompletableFuture<Either<String, FailedToLock>> fireEvent(String ref, Supplier<Exception> preRequisite, Supplier<DistributedData> action,
                Consumer<Exception> postAction) {
            return CompletableFuture.supplyAsync(() -> {
                final String requestedKey = getRequestedKey(null);
                if (keyMap.putIfAbsent(requestedKey, ActionData.PLACEHOLDER) == null) {
                    return CompletableFuture.supplyAsync(() -> {
                        try {
                            Exception exception = preRequisite.get();
                            try {
                                if (exception == null) {
                                    // invoke(action.get());
                                    return Either.<String, FailedToLock>left(ref);
                                }
                            } finally {
                                postAction.accept(exception);
                            }
                            FailedToLock failedToLock = new FailedToLock(ref);
                            failedToLock.addSuppressed(exception);
                            return Either.<String, FailedToLock>right(failedToLock);
                        } finally {
                            keyMap.remove(requestedKey);
                        }
                    }, refLockService.getRepoWriter());
                } else {
                    return CompletableFuture.completedFuture(Either.<String, FailedToLock>right(new FailedToLock(ref)));
                }
            }, refLockService.getRepoWriter()).thenCompose(c -> c);
        }

        public String getRef() {
            return ref;
        }
    }

    @Override
    public ExecutorService getRepoWriter() {
        return repoWriter;
    }
}
