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




import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.dropwizard.Application;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import jitstatic.api.MapResource;
import jitstatic.hosted.HostedFactory;
import jitstatic.source.Source;
import jitstatic.source.SourceEventListener;
import jitstatic.storage.LoaderException;
import jitstatic.storage.Storage;

public class JitstaticApplication extends Application<JitstaticConfiguration> {
	
	private final Logger log = LoggerFactory.getLogger(JitstaticApplication.class);

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
			final HostedFactory hostedFactory = config.getHostedFactory();

			if (hostedFactory != null) {
				source = hostedFactory.build(env);
				if(config.getRemoteFactory() != null) {
					log.warn("When in a hosted configuration, any settings for a remote configuration is ignored");
				}
			} else {
				source = config.getRemoteFactory().build(env);
			}

			final Storage s = storage = config.getStorageFactory().build(source, env);			
			source.addListener(new SourceEventListener() {				
				@Override
				public void onEvent() {
					try {
						s.load();
					} catch (LoaderException e) {
					}					
				}
			});
			env.lifecycle().manage(new AutoCloseableLifeCycleManager<>(storage));
			env.lifecycle().manage(new ManagedObject<>(source));
			env.healthChecks().register(StorageHealthChecker.NAME, new StorageHealthChecker(storage));
			env.jersey().register(new MapResource(storage));
		} catch (Exception e) {
			guard = e;
			throw e;
		} finally {
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

}
