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

import java.util.List;
import java.util.Optional;

import org.eclipse.jgit.api.errors.RefNotFoundException;

import com.spencerwi.either.Either;

import io.jitstatic.CommitMetaData;
import io.jitstatic.MetaData;
import io.jitstatic.auth.UserData;
import io.jitstatic.hosted.FailedToLock;
import io.jitstatic.hosted.StoreInfo;
import io.jitstatic.source.ObjectStreamProvider;
import io.jitstatic.utils.CheckHealth;
import io.jitstatic.utils.Pair;

public interface Storage extends AutoCloseable, CheckHealth {
    public Optional<StoreInfo> getKey(String key, String ref);

//    public void reload(List<String> refsToReload);

    public void close();

    public Either<String, FailedToLock> put(String key, String ref, ObjectStreamProvider data, String version, CommitMetaData commitMetaData);

    public String addKey(String key, String branch, ObjectStreamProvider data, MetaData metaData, CommitMetaData commitMetaData);

    public Either<String, FailedToLock> putMetaData(String key, String ref, MetaData metaData, String metaDataVersion, CommitMetaData commitMetaData);

    public void delete(String key, String ref, CommitMetaData commitMetaData);

    public List<Pair<String, StoreInfo>> getListForRef(List<Pair<String, Boolean>> keyPairs, String ref);

    public List<Pair<List<Pair<String, StoreInfo>>, String>> getList(List<Pair<List<Pair<String, Boolean>>, String>> input);

    public UserData getUser(String username, String defaultRef, String realm) throws RefNotFoundException;

    public Pair<String, UserData> getUserData(String username, String defaultRef, String realm) throws RefNotFoundException;

    public Pair<MetaData, String> getMetaKey(String key, String ref);

    public Either<String, FailedToLock> update(String key, String ref, String path, String username, UserData data, String version);
    
    public String addUser(String key, String ref, String path, String name, UserData data);

    public void deleteUser(String key, String ref, String jitstaticKeyadminRealm, String name);
}
