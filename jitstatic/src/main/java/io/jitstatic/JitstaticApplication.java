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

import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;

import javax.inject.Singleton;
import javax.servlet.DispatcherType;
import javax.servlet.FilterRegistration;
import javax.servlet.ServletException;
import javax.servlet.ServletRegistration.Dynamic;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlets.CrossOriginFilter;
import org.eclipse.jetty.util.security.Constraint;
import org.eclipse.jgit.http.server.GitServlet;
import org.eclipse.jgit.util.SystemReader;
import org.glassfish.hk2.api.Factory;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

import com.codahale.metrics.Slf4jReporter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;

import io.dropwizard.Application;
import io.dropwizard.auth.AuthDynamicFeature;
import io.dropwizard.auth.AuthValueFactoryProvider;
import io.dropwizard.lifecycle.Managed;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import io.jitstatic.api.BulkResource;
import io.jitstatic.api.JitstaticInfoResource;
import io.jitstatic.api.KeyResource;
import io.jitstatic.api.MetaKeyResource;
import io.jitstatic.api.StreamingDeserializer;
import io.jitstatic.api.UsersResource;
import io.jitstatic.auth.AdminConstraintSecurityHandler;
import io.jitstatic.auth.UrlAwareBasicCredentialAuthFilter;
import io.jitstatic.auth.User;
import io.jitstatic.git.OverridingSystemReader;
import io.jitstatic.injection.configuration.JitstaticConfiguration;
import io.jitstatic.injection.configuration.hosted.HostedFactory;
import io.jitstatic.injection.configuration.hosted.HostedFactory.Cors;
import io.jitstatic.injection.configuration.reporting.ConsoleReporting;
import io.jitstatic.injection.executors.DefaultExecutorAnnotation;
import io.jitstatic.injection.executors.DefaultExecutorFactory;
import io.jitstatic.injection.executors.RepoWriterAnnotation;
import io.jitstatic.injection.executors.RepoWriterFactory;
import io.jitstatic.injection.executors.WorkStealerAnnotation;
import io.jitstatic.injection.executors.WorkStealerFactory;
import io.jitstatic.hosted.HostedGitRepositoryManager;
import io.jitstatic.hosted.InterceptingCrossOriginFilter;
import io.jitstatic.hosted.LoginService;
import io.jitstatic.source.ObjectStreamProvider;
import io.jitstatic.source.Source;
import io.jitstatic.storage.HashService;
import io.jitstatic.storage.KeyStorage;
import io.jitstatic.storage.Storage;
import io.jitstatic.storage.ref.LocalRefLockService;
import io.jitstatic.storage.ref.RefLockService;
import io.jitstatic.utils.FilesUtils;
import zone.dragon.dropwizard.HK2Bundle;
import zone.dragon.dropwizard.health.InjectableHealthCheck;
import zone.dragon.dropwizard.lifecycle.InjectableManaged;

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
    public void initialize(Bootstrap<JitstaticConfiguration> bootstrap) {
        HK2Bundle.addTo(bootstrap);
    }

    @Override
    public void run(final JitstaticConfiguration config, final Environment env) throws Exception {
        SystemReader.setInstance(new OverridingSystemReader());
        final HostedFactory hostedFactory = config.getHostedFactory();
        registerCustomDeserializer(env, hostedFactory);

        env.jersey().register(new AbstractBinder() {
            @Override
            protected void configure() {
                bindFactory(DefaultExecutorFactory.class, Singleton.class).to(Factory.class).to(ExecutorService.class)
                        .qualifiedBy(DefaultExecutorAnnotation.INSTANCE);
                bindFactory(RepoWriterFactory.class, Singleton.class).to(Factory.class).to(ExecutorService.class).qualifiedBy(RepoWriterAnnotation.INSTANCE);
                bindFactory(WorkStealerFactory.class, Singleton.class).to(Factory.class).to(ExecutorService.class).qualifiedBy(WorkStealerAnnotation.INSTANCE);

                bind(HashService.class).to(HashService.class).in(Singleton.class);
                bind(LocalRefLockService.class).to(RefLockService.class).in(Singleton.class);
                bind(HostedGitRepositoryManager.class).to(Source.class).to(InjectableManaged.class).to(InjectableHealthCheck.class).in(Singleton.class);
                bind(KeyStorage.class).to(Storage.class).to(InjectableManaged.class).to(InjectableHealthCheck.class).in(Singleton.class);
                bind(GitServletHook.class).to(GitServletHook.class).to(InjectableManaged.class).in(Singleton.class);
                bind(LoginServiceHook.class).to(LoginServiceHook.class).to(InjectableManaged.class).in(Singleton.class);
            }
        });
        env.jersey().register(new AuthDynamicFeature(UrlAwareBasicCredentialAuthFilter.class));
        env.jersey().register(new AuthValueFactoryProvider.Binder<>(User.class));

        env.jersey().register(KeyResource.class);
        env.jersey().register(JitstaticInfoResource.class);
        env.jersey().register(MetaKeyResource.class);
        env.jersey().register(BulkResource.class);
        env.jersey().register(UsersResource.class);

        buildReporter(env, config.getReportingFactory().getConsole());
        setupAdmin(env, hostedFactory);
        setupCors(env, hostedFactory);
        setUpGitServlet(env, JITSTATIC_GIT_REALM, hostedFactory);
    }

    private void buildReporter(final Environment env, final ConsoleReporting reporting) {
        if (reporting != null) {
            final Slf4jReporter reporter = Slf4jReporter.forRegistry(env.metrics())
                    .outputTo(LoggerFactory.getLogger(ConsoleReporting.class))
                    .convertRatesTo(ConsoleReporting.convertRate.apply(reporting.getRates()))
                    .convertDurationsTo(reporting.getDurations().getUnit()).build();
            env.lifecycle().manage(new Managed() {
                @Override
                public void start() throws Exception {
                    reporter.start(reporting.getReportPeriods().getQuantity(), reporting.getReportPeriods().getUnit());
                }

                @Override
                public void stop() throws Exception {
                    reporter.stop();
                }
            });
        }
    }

    private void registerCustomDeserializer(final Environment env, final HostedFactory hostedFactory) {
        Path tempfolder = hostedFactory.getTmpPath();
        if (tempfolder == null) {
            tempfolder = hostedFactory.getBasePath().resolve(".git").resolve("jitstatic").resolve("tmpfolder");
        }
        FilesUtils.checkOrCreateFolder(tempfolder.toFile());
        final ObjectMapper mapper = env.getObjectMapper();
        final SimpleModule module = new SimpleModule();
        module.addDeserializer(ObjectStreamProvider.class, new StreamingDeserializer(hostedFactory.getThreshold(), tempfolder.toFile()));
        mapper.registerModule(module);
        env.getObjectMapper().registerModule(module);
    }

    private static void setupAdmin(final Environment env, final HostedFactory hostedFactory) {
        if (hostedFactory.isProtectHealthChecks() || hostedFactory.isProtectMetrics() || hostedFactory.isProtectTasks()) {
            final AdminConstraintSecurityHandler adminConstraintSecurityHandler = new AdminConstraintSecurityHandler(hostedFactory.getAdminName(), hostedFactory
                    .getAdminPass(), hostedFactory.isProtectHealthChecks(), hostedFactory.isProtectMetrics(), hostedFactory.isProtectTasks());
            env.admin().setSecurityHandler(adminConstraintSecurityHandler);
            Set<String> pathsWithUncoveredHttpMethods = adminConstraintSecurityHandler.getPathsWithUncoveredHttpMethods();
            pathsWithUncoveredHttpMethods.stream().forEach(p -> LOG.warn("Not protecting {}", p));
        }
    }

    private static void setupCors(final Environment env, final HostedFactory hostedFactory) {
        final Cors corsConfig = hostedFactory.getCors();
        if (corsConfig != null) {
            final FilterRegistration.Dynamic filter = env.servlets().addFilter("CORS", InterceptingCrossOriginFilter.class);
            filter.setInitParameter("allowedOrigins", corsConfig.getAllowedOrigins());
            filter.setInitParameter("allowedHeaders", corsConfig.getAllowedHeaders());
            filter.setInitParameter("allowedMethods", corsConfig.getAllowedMethods());
            filter.setInitParameter("preflightMaxAge", corsConfig.getPreflightMaxAge());
            filter.setInitParameter("exposedHeaders", corsConfig.getExposedHeaders());
            for (String url : corsConfig.getCorsBaseUrl().split(",")) {
                url = url.trim();
                filter.addMappingForUrlPatterns(EnumSet.allOf(DispatcherType.class), true, url);
                LOG.info("CORS is enabled for {}", url);
            }
            filter.setInitParameter(CrossOriginFilter.CHAIN_PREFLIGHT_PARAM, Boolean.TRUE.toString());
        }
    }

    private static void setUpGitServlet(final Environment env, final String gitRealm, final HostedFactory hostedFactory) {
        Objects.requireNonNull(gitRealm);
        String servletName = hostedFactory.getServletName();
        String hostedEndpoint = hostedFactory.getHostedEndpoint();
        boolean exposeAll = hostedFactory.isExposeAll();
        String userName = hostedFactory.getUserName();
        String secret = hostedFactory.getSecret();

        final String base = "/" + servletName + "/";
        final String baseServletPath = base + "*";
        LOG.info("Configuring hosted GIT environment on {}{}", base, hostedEndpoint);
        final GitServlet gs = new GitServlet();
        env.getApplicationContext().addBean(gs); // Add to lookup

        final Dynamic servlet = env.servlets().addServlet(servletName, gs);
        servlet.setInitParameter(HostedFactory.BASE_PATH, hostedFactory.getBasePath().toUri().getRawPath());
        servlet.setInitParameter(HostedFactory.EXPOSE_ALL, Boolean.toString(exposeAll));
        servlet.setInitParameter(HostedFactory.SERVLET_NAME, servletName);
        servlet.addMapping(baseServletPath);

        final ConstraintMapping baseMapping = new ConstraintMapping();
        baseMapping.setConstraint(new Constraint());
        baseMapping.getConstraint().setAuthenticate(true);
        baseMapping.getConstraint().setDataConstraint(Constraint.DC_NONE);
        baseMapping.getConstraint().setRoles(new String[] {});
        baseMapping.setPathSpec(base + hostedEndpoint + "/*");

        final ConstraintMapping infoPath = new ConstraintMapping();
        infoPath.setConstraint(new Constraint());
        infoPath.getConstraint().setAuthenticate(true);
        infoPath.getConstraint().setDataConstraint(Constraint.DC_NONE);
        infoPath.getConstraint().setRoles(new String[] { JitStaticConstants.GIT_PULL });
        infoPath.setPathSpec(base + hostedEndpoint + "/info/*");

        final ConstraintMapping receivePath = new ConstraintMapping();
        receivePath.setConstraint(new Constraint());
        receivePath.getConstraint().setAuthenticate(true);
        receivePath.getConstraint().setDataConstraint(Constraint.DC_NONE);
        receivePath.getConstraint().setRoles(new String[] { JitStaticConstants.GIT_PUSH });
        receivePath.setPathSpec(base + hostedEndpoint + "/git-receive-pack");

        final ConstraintMapping uploadPath = new ConstraintMapping();
        uploadPath.setConstraint(new Constraint());
        uploadPath.getConstraint().setAuthenticate(true);
        uploadPath.getConstraint().setDataConstraint(Constraint.DC_NONE);
        uploadPath.getConstraint().setRoles(new String[] { JitStaticConstants.GIT_PULL });
        uploadPath.setPathSpec(base + hostedEndpoint + "/git-upload-pack");

        final ConstraintSecurityHandler sec = new ConstraintSecurityHandler();

        sec.setRealmName(gitRealm);
        sec.setAuthenticator(new BasicAuthenticator());
        final LoginService gitLoginService = new LoginService(userName, secret, sec.getRealmName(), JitStaticConstants.REFS_HEADS_SECRETS);
        sec.setLoginService(gitLoginService);
        sec.setConstraintMappings(new ConstraintMapping[] { baseMapping, infoPath, receivePath, uploadPath });

        sec.setHandler(new DropWizardHandlerWrapper(env.getApplicationContext()));
        Set<String> pathsWithUncoveredHttpMethods = sec.getPathsWithUncoveredHttpMethods();

        if (!pathsWithUncoveredHttpMethods.isEmpty()) {
            throw new RuntimeException("Following paths are uncovered " + pathsWithUncoveredHttpMethods);
        }
        env.getApplicationContext().addBean(gitLoginService);
        env.servlets().setSecurityHandler(sec);
    }

    static class DropWizardHandlerWrapper implements Handler {

        private final Handler handler;

        public DropWizardHandlerWrapper(final Handler handler) {
            this.handler = handler;
        }

        @Override
        public void start() throws Exception {
            handler.start();
        }

        @Override
        public void stop() throws Exception {
            handler.stop();
        }

        @Override
        public boolean isRunning() { return handler.isRunning(); }

        @Override
        public boolean isStarted() { return handler.isStarted(); }

        @Override
        public boolean isStarting() { return handler.isStarting(); }

        @Override
        public boolean isStopping() { return handler.isStopping(); }

        @Override
        public boolean isStopped() { return handler.isStopped(); }

        @Override
        public boolean isFailed() { return handler.isFailed(); }

        @Override
        public void addLifeCycleListener(Listener listener) {
            handler.addLifeCycleListener(listener);
        }

        @Override
        public void removeLifeCycleListener(Listener listener) {
            handler.removeLifeCycleListener(listener);
        }

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
            handler.handle(target, baseRequest, request, response);
        }

        @Override
        public void setServer(Server server) {
            handler.setServer(server);
        }

        @Override
        public Server getServer() { return handler.getServer(); }

        @Override
        public void destroy() {
            handler.destroy();
        }
    }
}
