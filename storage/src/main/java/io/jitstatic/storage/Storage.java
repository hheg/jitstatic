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

import com.spencerwi.either.Either;

import io.jitstatic.StorageData;
import io.jitstatic.auth.User;
import io.jitstatic.hosted.FailedToLock;
import io.jitstatic.hosted.StoreInfo;
import io.jitstatic.utils.CheckHealth;
import io.jitstatic.utils.Pair;

public interface Storage extends AutoCloseable, CheckHealth {
	public Optional<StoreInfo> getKey(String key, String ref);
	public void reload(List<String> refsToReload);
	public void close();
	public Either<String, FailedToLock> put(String key, String ref, byte[] data, String version, String message, String userInfo, String userEmail);
    public StoreInfo addKey(String key, String branch, byte[] data, StorageData metaData, String message, String userInfo, String userMail);
    public Either<String, FailedToLock> putMetaData(String key, String ref, StorageData metaData, String metaDataVersion, String message, String userInfo, String userMail);
    public void delete(String key, String ref, String user, String message, String userMail);
    public List<Pair<String, StoreInfo>> getList(String key, String ref, Optional<User> user);
}
