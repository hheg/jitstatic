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

import io.dropwizard.Application;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import jitstatic.api.JitstaticInfoResource;
import jitstatic.api.MapResource;
import jitstatic.source.Source;
import jitstatic.storage.Storage;

public class JitstaticApplication extends Application<JitstaticConfiguration> {

	public static void main(final String[] args) throws Exception {
		new JitstaticApplication().run(args);
	}

	@Override
	public void initialize(final Bootstrap<JitstaticConfiguration> bootstrap) {
		super.initialize(bootstrap);
	}

	@Override
	public void run(final JitstaticConfiguration config, final Environment env) throws Exception {
		Exception guard = null;
		Source source = null;
		Storage storage = null;
		try {
			source = config.build(env);
			storage = config.getStorageFactory().build(source, env);			
			env.lifecycle().manage(new ManagedObject<>(source));
			env.lifecycle().manage(new AutoCloseableLifeCycleManager<>(storage));
			env.healthChecks().register("storagechecker", new HealthChecker(storage));
			env.healthChecks().register("sourcechecker", new HealthChecker(source));
			env.jersey().register(new MapResource(storage));
			env.jersey().register(new JitstaticInfoResource());		
		} catch (final Exception e) {
			guard = e;
			throw e;
		} finally {
			cleanupIfFailed(guard, source, storage);
		}
	}

	private void cleanupIfFailed(final Exception guard, final Source source, final Storage storage) {
		if (guard != null) {
			if (source != null) {
				try {
					source.close();
				} catch (Exception ignore) {
				}
			}
			if (storage != null) {
				try {
					storage.close();
				} catch (Exception ignore) {
				}
			}
		}
	}
}
