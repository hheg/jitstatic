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

import static io.jitstatic.JitStaticConstants.JITSTATIC_GIT_REALM;
import static io.jitstatic.version.ProjectVersion.INSTANCE;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import org.eclipse.jgit.util.SystemReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

import com.codahale.metrics.InstrumentedExecutorService;
import com.codahale.metrics.MetricRegistry;

import io.dropwizard.Application;
import io.dropwizard.lifecycle.Managed;
import io.dropwizard.setup.Environment;
import io.jitstatic.api.BulkResource;
import io.jitstatic.api.JitstaticInfoResource;
import io.jitstatic.api.KeyResource;
import io.jitstatic.api.MetaKeyResource;
import io.jitstatic.api.UsersResource;
import io.jitstatic.git.OverridingSystemReader;
import io.jitstatic.hosted.HostedFactory;
import io.jitstatic.hosted.LoginService;
import io.jitstatic.source.Source;
import io.jitstatic.storage.HashService;
import io.jitstatic.storage.NamingThreadFactory;
import io.jitstatic.storage.Storage;
import io.jitstatic.storage.ref.LocalRefLockService;

public class JitstaticApplication extends Application<JitstaticConfiguration> {

    private static final Logger LOG = LoggerFactory.getLogger(JitstaticApplication.class);
    static {
        SLF4JBridgeHandler.install();
        java.util.logging.Logger.getGlobal().setLevel(Level.INFO);
    }

    public static void main(final String[] args) throws Exception {
        LOG.info("Starting {} build {}", INSTANCE.getBuildVersion(), INSTANCE.getCommitIdAbbrev());
        new JitstaticApplication().run(args);
    }

    @Override
    public void run(final JitstaticConfiguration config, final Environment env) throws Exception {
        Source source = null;
        Storage storage = null;
        LocalRefLockService refLockService = null;
        try {
            ExecutorService defaultExecutor = setUpExecutor(env.metrics());
            ExecutorService workStealingExecutor = setUpWorkStealingExecutor(env.metrics());
            ExecutorService repoWriter = setUpRepoWriter(env.metrics());
            SystemReader.setInstance(new OverridingSystemReader());
            final HostedFactory hostedFactory = config.getHostedFactory();
            refLockService = new LocalRefLockService(env.metrics());
            source = config.build(env, JITSTATIC_GIT_REALM, repoWriter);
            final String defaultBranch = hostedFactory.getBranch();
            final LoginService loginService = env.getApplicationContext().getBean(LoginService.class);
            final HashService hashService = env.getApplicationContext().getBean(HashService.class);
            storage = config.getStorageFactory().build(source, env, defaultBranch, hashService, hostedFactory
                    .getUserName(), refLockService, defaultExecutor, workStealingExecutor, config.getRootAuthenticator());
            loginService.setUserStorage(storage);

            env.lifecycle().manage(new ManagedObject<>(source));
            env.lifecycle().manage(new AutoCloseableLifeCycleManager<>(storage));
            env.lifecycle().manage(new AutoCloseableLifeCycleManager<>(refLockService));
            env.lifecycle().manage(new ManagedExecutor(defaultExecutor));
            env.lifecycle().manage(new ManagedExecutor(workStealingExecutor));
            env.lifecycle().manage(new ManagedExecutor(repoWriter));

            env.healthChecks().register("storagechecker", new HealthChecker(storage));
            env.healthChecks().register("sourcechecker", new HealthChecker(source));

            env.jersey().register(new KeyResource(storage, config.getHostedFactory().getCors() != null, defaultBranch));
            env.jersey().register(new JitstaticInfoResource());
            env.jersey().register(new MetaKeyResource(storage, defaultBranch));
            env.jersey().register(new BulkResource(storage, defaultBranch));
            env.jersey().register(new UsersResource(storage, defaultBranch, hashService));

            source.readAllRefs();
        } catch (final RuntimeException e) {
            closeSilently(refLockService);
            closeSilently(source);
            closeSilently(storage);

            throw e;
        }
    }

    private ExecutorService setUpRepoWriter(MetricRegistry metricRegistry) {
        return new InstrumentedExecutorService(Executors.newSingleThreadExecutor(new NamingThreadFactory("RepoWriter")), metricRegistry);
    }

    private ExecutorService setUpExecutor(final MetricRegistry metricRegistry) {
        return new InstrumentedExecutorService(Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 2, new NamingThreadFactory("default")), metricRegistry);
    }

    private ExecutorService setUpWorkStealingExecutor(final MetricRegistry metricRegistry) {
        return new InstrumentedExecutorService(Executors.newWorkStealingPool(Runtime.getRuntime().availableProcessors()), metricRegistry);
    }

    private void closeSilently(final AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (final Exception ignore) {
                // NOOP
            }
        }
    }

    private static class ManagedExecutor implements Managed {
        private final ExecutorService service;

        public ManagedExecutor(final ExecutorService service) {
            this.service = service;
        }

        @Override
        public void start() throws Exception {
            // NOOP
        }

        @Override
        public void stop() throws Exception {
            service.shutdown();
            service.awaitTermination(10, TimeUnit.SECONDS);
        }
    }
}
