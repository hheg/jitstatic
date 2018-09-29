package io.jitstatic;

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
import io.dropwizard.setup.Environment;
import io.jitstatic.api.BulkResource;
import io.jitstatic.api.JitstaticInfoResource;
import io.jitstatic.api.KeyResource;
import io.jitstatic.api.MetaKeyResource;
import io.jitstatic.auth.AddKeyAuthenticator;
import io.jitstatic.source.Source;
import io.jitstatic.storage.Storage;

public class JitstaticApplication extends Application<JitstaticConfiguration> {

    public static void main(final String[] args) throws Exception {
        new JitstaticApplication().run(args);
    }

    public static final String GIT_REALM = "git";
    public static final String JITSTATIC_STORAGE_REALM = "update";
    public static final String JITSTATIC_METAKEY_REALM = "create";

    @Override
    public void run(final JitstaticConfiguration config, final Environment env) throws Exception {
        Source source = null;
        Storage storage = null;
        try {
            source = config.build(env, GIT_REALM);
            storage = config.getStorageFactory().build(source, env, JITSTATIC_STORAGE_REALM);
            env.lifecycle().manage(new ManagedObject<>(source));
            env.lifecycle().manage(new AutoCloseableLifeCycleManager<>(storage));
            env.healthChecks().register("storagechecker", new HealthChecker(storage));
            env.healthChecks().register("sourcechecker", new HealthChecker(source));
            final AddKeyAuthenticator authenticator = config.getAddKeyAuthenticator();
            env.jersey().register(new KeyResource(storage, authenticator));
            env.jersey().register(new JitstaticInfoResource());
            env.jersey().register(new MetaKeyResource(storage, authenticator));
            env.jersey().register(new BulkResource(storage));
        } catch (final Exception e) {
            closeSilently(source);
            closeSilently(storage);
            throw e;
        }
    }

    private void closeSilently(final AutoCloseable source) {
        if (source != null) {
            try {
                source.close();
            } catch (final Exception ignore) {
            }
        }
    }
}
