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

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.spencerwi.either.Either;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.jitstatic.CommitMetaData;
import io.jitstatic.JitStaticConstants;
import io.jitstatic.MetaData;
import io.jitstatic.auth.UserData;
import io.jitstatic.hosted.DistributedData;
import io.jitstatic.hosted.FailedToLock;
import io.jitstatic.hosted.RefLockHolder;
import io.jitstatic.hosted.StoreInfo;
import io.jitstatic.source.ObjectStreamProvider;
import io.jitstatic.source.Source;
import io.jitstatic.storage.HashService;
import io.jitstatic.utils.Pair;
import io.jitstatic.utils.VersionIsNotSame;
import io.jitstatic.utils.WrappingAPIException;

@SuppressFBWarnings(value = "NP_OPTIONAL_RETURN_NULL", justification = "Map's returns null and there's a difference from a previous cached 'not found' value and a new 'not found'")
public class RefHolder implements RefLockHolder, AutoCloseable {
    static final int MAX_ENTRIES = 2000;
    static final int THRESHOLD = 1_000_000;
    final String ref;
    final Source source;
    private final HashService hashService;
    final LockService lock;

    public RefHolder(final String ref, final Source source, final HashService hashService, final RefLockService refLockService,
            final ExecutorService workStealingExecutor) {
        this.ref = Objects.requireNonNull(ref);
        this.source = Objects.requireNonNull(source);
        this.hashService = Objects.requireNonNull(hashService);
        this.lock = refLockService.getLockService(ref, workStealingExecutor, source, hashService);
    }

    public void start() {

    }

    public boolean isEmpty() { return lock.isEmpty(); }

    public CompletableFuture<Either<String, FailedToLock>> addKey(final String key, final ObjectStreamProvider data, final MetaData metaData,
            final CommitMetaData commitMetaData) {
        return lock.fireEvent(key, ActionData.addKey(key, data, metaData, commitMetaData));
    }

    public CompletableFuture<Either<String, FailedToLock>> updateKey(final String key, final ObjectStreamProvider data, final String oldVersion,
            final CommitMetaData commitMetaData) {
        return lock.fireEvent(key, ActionData.updateKey(key, data, oldVersion, commitMetaData));
    }

    public CompletableFuture<Either<String, FailedToLock>> deleteKey(final String key, final CommitMetaData commitMetaData) {
        return lock.fireEvent(key, ActionData.deleteKey(key, commitMetaData));
    }

    public CompletableFuture<Either<String, FailedToLock>> updateMetadata(final String key, final MetaData metaData, final String oldMetaDataVersion,
            final CommitMetaData commitMetaData) {
        return lock.fireEvent(key, ActionData.updateMetakey(Objects.requireNonNull(key), Objects.requireNonNull(metaData), Objects
                .requireNonNull(oldMetaDataVersion), Objects.requireNonNull(commitMetaData)));
    }

    @Nullable
    public CompletableFuture<Pair<String, UserData>> getUser(final String userKeyPath) {
        final Either<Optional<StoreInfo>, Pair<String, UserData>> peek = lock.peek(createFullUserKeyPath(userKeyPath));
        if (peek != null && peek.isRight()) {
            return CompletableFuture.completedFuture(peek.getRight());
        }
        return lock.getUser(userKeyPath);
    }

    static String createFullUserKeyPath(final String userKeyPath) {
        return JitStaticConstants.USERS + Objects.requireNonNull(userKeyPath);
    }

    public CompletableFuture<Either<String, FailedToLock>> updateUser(final String userKeyPath, final String username, final UserData data,
            final String version) {
        final String key = createFullUserKeyPath(userKeyPath);
        final UserData generatedUser = generateUser(Objects.requireNonNull(data), getUser(userKeyPath).join(), version);
        return lock.fireEvent(key, ActionData
                .updateUser(userKeyPath, Objects.requireNonNull(username), generatedUser, Objects.requireNonNull(version)));
    }

    UserData generateUser(final UserData data, final Pair<String, UserData> userKeyData, final String oldVersion) {
        if (!oldVersion.equals(userKeyData.getLeft())) {
            throw new WrappingAPIException(new VersionIsNotSame(oldVersion, userKeyData.getLeft()));
        }
        if (data.getBasicPassword() != null) {
            return hashService.constructUserData(data.getRoles(), data.getBasicPassword());
        } else {
            final UserData current = userKeyData.getRight();
            return new UserData(data.getRoles(), current.getBasicPassword(), current.getSalt(), current.getHash());
        }
    }

    public CompletableFuture<Either<String, FailedToLock>> addUser(final String userKeyPath, final String username, final UserData data) {
        final String key = createFullUserKeyPath(userKeyPath);
        return lock.fireEvent(key, ActionData.addUser(userKeyPath, username, data));
    }

    public CompletableFuture<Either<String, FailedToLock>> deleteUser(final String userKeyPath, final String userName) {
        final String key = createFullUserKeyPath(userKeyPath);
        return lock.fireEvent(key, ActionData.deleteUser(userKeyPath, userName));
    }

    public void reload() {
        lock.reload();
    }

    @Override
    public void close() {
        lock.close();
    }

    @Override
    public <T> CompletableFuture<Either<T, FailedToLock>> enqueueAndReadBlock(final Supplier<T> supplier) {
        return lock.enqueueAndReadBlock(supplier);
    }

    @Override
    public CompletableFuture<Either<String, FailedToLock>> enqueueAndBlock(final Supplier<Exception> preRequisite, final Supplier<DistributedData> action,
            final Consumer<Exception> postAction) {
        return lock.fireEvent(ref, preRequisite, action, postAction);
    }

    public CompletableFuture<List<String>> getList(String key, boolean recursive) {
        return lock.getList(key, recursive);
    }

    public Optional<StoreInfo> readKey(String key) {
        final Optional<StoreInfo> storeInfo = lock.readKey(key);
        if (storeInfo == null) {
            return Optional.<StoreInfo>empty();
        }
        return storeInfo;
    }
}
