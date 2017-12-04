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
import jitstatic.remote.RemoteFactory;
import jitstatic.source.Source;
import jitstatic.source.SourceEventListener;
import jitstatic.storage.Storage;
import jitstatic.storage.StorageFactory;

public class JitstaticApplication extends Application<JitstaticConfiguration> {

	private static final Logger log = LoggerFactory.getLogger(JitstaticApplication.class);

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
			final StorageFactory storageFactory = config.getStorageFactory();
			final HostedFactory hostedFactory = config.getHostedFactory();
			String ref = null;
			if (hostedFactory != null) {
				source = hostedFactory.build(env);
				ref = hostedFactory.getBranch();
				if (config.getRemoteFactory() != null) {
					log.warn("When in a hosted configuration, any settings for a remote configuration is ignored");
				}
			} else {
				final RemoteFactory remoteFactory = config.getRemoteFactory();
				if (remoteFactory == null) {
					throw new IllegalStateException("Either hosted or remote must be chosen");
				}
				source = remoteFactory.build(env);
				ref = remoteFactory.getBranch();
			}

			final Storage s = storage = storageFactory.build(source, env, ref);
			source.addListener((updatedRefs) -> {
				try {
					s.reload(updatedRefs);
				} catch (Exception e) {
					LoggerFactory.getLogger(SourceEventListener.class).error("Error while loading storage", e);
				}
			});
			env.lifecycle().manage(new AutoCloseableLifeCycleManager<>(storage));
			env.lifecycle().manage(new ManagedObject<>(source));
			env.healthChecks().register(StorageHealthChecker.NAME, new StorageHealthChecker(storage));
			env.healthChecks().register("Source", new SourceHealthChecker(source));
			env.jersey().register(new MapResource(storage));
		} catch (final Exception e) {
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
