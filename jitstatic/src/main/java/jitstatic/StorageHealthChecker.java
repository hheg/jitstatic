package jitstatic;

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


import com.codahale.metrics.health.HealthCheck;

import jitstatic.storage.Storage;

public class StorageHealthChecker extends HealthCheck {

	public static final String NAME = "storagechecker";
	
	private final Storage storage;
	
	public StorageHealthChecker(Storage storage) {
		this.storage = storage;
	}

	@Override
	protected Result check() throws Exception {		
		try {
			storage.checkHealth();
			return Result.healthy();
		}catch(Exception e) {
			return Result.unhealthy(e.getCause());
		}
	}

}
