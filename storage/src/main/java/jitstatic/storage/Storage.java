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

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import jitstatic.StorageData;
import jitstatic.utils.CheckHealth;

public interface Storage extends AutoCloseable, CheckHealth {
	public CompletableFuture<Optional<StoreInfo>> get(String key, String ref);
	public void reload(List<String> refsToReload);
	public void close();
	public CompletableFuture<String> put(byte[] data, String version, String message, String userInfo, String userEmail, String key, String ref);
    public CompletableFuture<StoreInfo> add(String key, String branch, byte[] data, StorageData metaData, String message, String userInfo, String userMail);
}
