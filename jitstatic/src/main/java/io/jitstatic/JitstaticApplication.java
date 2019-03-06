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

import static io.jitstatic.JitStaticConstants.GIT_REALM;
import static io.jitstatic.JitStaticConstants.JITSTATIC_KEYADMIN_REALM;
import static io.jitstatic.version.ProjectVersion.INSTANCE;

import org.eclipse.jgit.util.SystemReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.dropwizard.Application;
import io.dropwizard.setup.Environment;
import io.jitstatic.api.BulkResource;
import io.jitstatic.api.JitstaticInfoResource;
import io.jitstatic.api.KeyResource;
import io.jitstatic.api.MetaKeyResource;
import io.jitstatic.api.UsersResource;
import io.jitstatic.auth.KeyAdminAuthenticator;
import io.jitstatic.git.OverridingSystemReader;
import io.jitstatic.hosted.HostedFactory;
import io.jitstatic.hosted.LoginService;
import io.jitstatic.source.Source;
import io.jitstatic.storage.HashService;
import io.jitstatic.storage.Storage;

public class JitstaticApplication extends Application<JitstaticConfiguration> {

    private static final Logger LOG = LoggerFactory.getLogger(JitstaticApplication.class);

    public static void main(final String[] args) throws Exception {
        LOG.info("Starting {} build {}", INSTANCE.getBuildVersion(), INSTANCE.getCommitIdAbbrev());
        new JitstaticApplication().run(args);
    }

    @Override
    public void run(final JitstaticConfiguration config, final Environment env) throws Exception {
        Source source = null;
        Storage storage = null;
        try {
            SystemReader.setInstance(new OverridingSystemReader());
            source = config.build(env, GIT_REALM);
            final HostedFactory hostedFactory = config.getHostedFactory();
            final String defaultBranch = hostedFactory.getBranch();
            final LoginService loginService = env.getApplicationContext().getBean(LoginService.class);
            final HashService hashService = env.getApplicationContext().getBean(HashService.class);
            storage = config.getStorageFactory().build(source, env, JITSTATIC_KEYADMIN_REALM, hashService, hostedFactory.getUserName());
            loginService.setUserStorage(storage);
            env.lifecycle().manage(new ManagedObject<>(source));
            env.lifecycle().manage(new AutoCloseableLifeCycleManager<>(storage));
            env.healthChecks().register("storagechecker", new HealthChecker(storage));
            env.healthChecks().register("sourcechecker", new HealthChecker(source));
            final KeyAdminAuthenticator authenticator = config.getAddKeyAuthenticator(storage, hashService);
            boolean cors = config.getHostedFactory().getCors() != null;
            env.jersey().register(new KeyResource(storage, authenticator, cors, defaultBranch, env.getObjectMapper(),
                    env.getValidator(), hashService));
            env.jersey().register(new JitstaticInfoResource());
            env.jersey().register(new MetaKeyResource(storage, authenticator, defaultBranch, hashService));
            env.jersey().register(new BulkResource(storage, authenticator, defaultBranch, hashService));
            env.jersey().register(new UsersResource(storage, authenticator, loginService, defaultBranch, hashService));
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
                // NOOP
            }
        }
    }
}
