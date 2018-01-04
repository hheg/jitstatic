package jitstatic.storage;

import java.util.List;
import java.util.concurrent.Future;

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

public interface Storage extends AutoCloseable {
	public Future<StorageData> get(String key, String ref);
	public void reload(List<String> refsToReload);
	public void close();
	public void checkHealth() throws Exception;
	public Future<StorageData> delete(String key, String ref);
}
