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

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import com.spencerwi.either.Either;

import io.jitstatic.CommitMetaData;
import io.jitstatic.MetaData;
import io.jitstatic.auth.UserData;
import io.jitstatic.hosted.FailedToLock;
import io.jitstatic.hosted.StoreInfo;
import io.jitstatic.source.ObjectStreamProvider;
import io.jitstatic.source.Source;
import io.jitstatic.storage.HashService;
import io.jitstatic.utils.WrappingAPIException;

public class ReadOnlyRefHolder extends RefHolder {

    private static final String TAGS_CANNOT_BE_MODIFIED = "Tags cannot be modified";

    public ReadOnlyRefHolder(final String ref, final Source source, final HashService hashService, final RefLockService clusterService,
            ExecutorService workStealer) {
        super(ref, source, hashService, clusterService, workStealer);
    }

    @Override
    public CompletableFuture<Either<String, FailedToLock>> addKey(final String key, final ObjectStreamProvider data, final MetaData metaData,
            final CommitMetaData commitMetaData) {
        throw new WrappingAPIException(new UnsupportedOperationException("add key " + TAGS_CANNOT_BE_MODIFIED));
    }

    @Override
    public CompletableFuture<Either<String, FailedToLock>> addUser(final String userKeyPath, final String username, final UserData data) {
        throw new WrappingAPIException(new UnsupportedOperationException("add user " + TAGS_CANNOT_BE_MODIFIED));
    }

    @Override
    public CompletableFuture<Either<String, FailedToLock>> deleteKey(final String key, final CommitMetaData commitMetaData) {
        throw new WrappingAPIException(new UnsupportedOperationException("delete key " + TAGS_CANNOT_BE_MODIFIED));
    }

    @Override
    public CompletableFuture<Either<String, FailedToLock>> deleteUser(final String userKeyPath, final String username) {
        throw new WrappingAPIException(new UnsupportedOperationException("delete user " + TAGS_CANNOT_BE_MODIFIED));
    }

    @Override
    public CompletableFuture<Either<String, FailedToLock>> modifyKey(final String key, final ObjectStreamProvider data, final String oldVersion,
            final CommitMetaData commitMetaData) {
        throw new WrappingAPIException(new UnsupportedOperationException("modify key " + TAGS_CANNOT_BE_MODIFIED));
    }

    @Override
    public CompletableFuture<Either<String, FailedToLock>> modifyMetadata(final String key, final MetaData metaData, final String oldMetaDataVersion,
            final CommitMetaData commitMetaData) {
        throw new WrappingAPIException(new UnsupportedOperationException("modify metadata " + TAGS_CANNOT_BE_MODIFIED));
    }

    @Override
    void putKey(final String key, final Optional<StoreInfo> store) {
        throw new WrappingAPIException(new UnsupportedOperationException("put key" + TAGS_CANNOT_BE_MODIFIED));
    }

    @Override
    public CompletableFuture<Either<String, FailedToLock>> modifyUser(final String userKeyPath, final String username, final UserData data,
            final String version) {
        throw new WrappingAPIException(new UnsupportedOperationException("update user " + TAGS_CANNOT_BE_MODIFIED));
    }

    @Override
    public void start() {
        // NOOP
    }
}
