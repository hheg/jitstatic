package io.jitstatic.source;

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
import java.util.List;
import java.util.function.Function;

import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.events.RepositoryListener;
import org.eclipse.jgit.lib.ObjectLoader;

import io.jitstatic.CommitMetaData;
import io.jitstatic.MetaData;
import io.jitstatic.auth.UserData;
import io.jitstatic.hosted.DistributedData;
import io.jitstatic.hosted.RefLockHolder;
import io.jitstatic.utils.CheckHealth;
import io.jitstatic.utils.Functions.ThrowingSupplier;
import io.jitstatic.utils.Pair;

public interface Source extends AutoCloseable, CheckHealth {
    public void close();

    public void start();

    public SourceInfo getSourceInfo(String key, String ref) throws RefNotFoundException;

    public Pair<String, ThrowingSupplier<ObjectLoader, IOException>> modifyKey(String key, String ref, ObjectStreamProvider data, CommitMetaData commitMetaData);

    public Pair<Pair<ThrowingSupplier<ObjectLoader, IOException>, String>, String> addKey(String key, String ref, ObjectStreamProvider data, MetaData metaData, CommitMetaData commitMetaData);

    public String modifyMetadata(MetaData metaData, String metaDataVersion, String key, String ref, CommitMetaData commitMetaData);

    public void deleteKey(String key, String ref, CommitMetaData commitMetaData);

    public void addRefHolderFactory(Function<String, RefLockHolder> factory);

    public void createRef(String ref) throws IOException;

    public void deleteRef(String ref) throws IOException;

    public List<String> getList(String keys, String ref, boolean recursive) throws RefNotFoundException, IOException;

    Pair<String, UserData> getUser(String userKey, String ref) throws RefNotFoundException, IOException;

    public String updateUser(String key, String ref, String username, UserData data) throws RefNotFoundException, IOException;

    public String addUser(String key, String ref, String username, UserData data) throws IOException, RefNotFoundException;

    public void deleteUser(String key, String ref, String username) throws IOException;

    <T extends RepositoryListener> void addListener(T listener, Class<T> type);

    public void readAllRefs() throws IOException;

    public void write(DistributedData data, String ref) throws IOException;

}
