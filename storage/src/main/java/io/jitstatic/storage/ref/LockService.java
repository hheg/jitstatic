package io.jitstatic.storage.ref;

import java.util.List;
import java.util.Optional;

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

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.jvnet.hk2.annotations.Contract;

import com.spencerwi.either.Either;

import io.jitstatic.auth.UserData;
import io.jitstatic.hosted.DistributedData;
import io.jitstatic.hosted.FailedToLock;
import io.jitstatic.hosted.StoreInfo;
import io.jitstatic.utils.Pair;

@Contract
public interface LockService extends AutoCloseable {

    void close();

    CompletableFuture<Either<String, FailedToLock>> fireEvent(String key, ActionData data);

    CompletableFuture<Either<String, FailedToLock>> fireEvent(String ref, Supplier<Exception> preRequisite, Supplier<DistributedData> action, Consumer<Exception> postAction);
    String getRef();
    CompletableFuture<Pair<String, UserData>> getUser(final String userKeyPath);
    <T> CompletableFuture<Either<T, FailedToLock>> enqueueAndReadBlock(Supplier<T> supplier);
    Optional<StoreInfo> readKey(String key);
    CompletableFuture<List<String>> getList(String key, boolean recursive);
    void reload();
    boolean isEmpty();
    Either<Optional<StoreInfo>, Pair<String, UserData>> peek(String key);

    /** 
     * This is for testing purposes only
     * @param key
     * @param data
     */
    void putKeyFull(String key, Either<Optional<StoreInfo>, Pair<String, UserData>> data);
}
