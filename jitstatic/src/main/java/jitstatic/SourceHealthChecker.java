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

import java.util.Objects;
import com.codahale.metrics.health.HealthCheck;
import jitstatic.source.Source;

public class SourceHealthChecker extends HealthCheck {
	
	private final Source source;

	public SourceHealthChecker(final Source source) {
		this.source = Objects.requireNonNull(source);
	}

	@Override
	protected Result check() throws Exception {
		try{
			source.checkHealth();
			return Result.healthy();
		}catch(final Exception e) {
			return Result.unhealthy(e);
		}
	}

}
