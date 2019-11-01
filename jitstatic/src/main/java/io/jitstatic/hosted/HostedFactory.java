package io.jitstatic.hosted;

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

import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;

import javax.servlet.DispatcherType;
import javax.servlet.FilterRegistration;
import javax.servlet.ServletException;
import javax.servlet.ServletRegistration.Dynamic;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;

import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlets.CrossOriginFilter;
import org.eclipse.jetty.util.security.Constraint;
import org.eclipse.jgit.http.server.GitServlet;
import org.eclipse.jgit.lib.Constants;
import org.hibernate.validator.constraints.NotEmpty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;

import io.dropwizard.setup.Environment;
import io.jitstatic.JitStaticConstants;
import io.jitstatic.api.StreamingDeserializer;
import io.jitstatic.auth.AdminConstraintSecurityHandler;
import io.jitstatic.check.CorruptedSourceException;
import io.jitstatic.source.ObjectStreamProvider;
import io.jitstatic.source.Source;
import io.jitstatic.storage.HashService;
import io.jitstatic.utils.FilesUtils;

public class HostedFactory {
    private static final Logger LOG = LoggerFactory.getLogger(HostedFactory.class);

    public static final String SERVLET_NAME = "servlet-name";
    public static final String BASE_PATH = "base-path";
    public static final String EXPOSE_ALL = "expose-all";

    @JsonProperty
    @Pattern(regexp = "^" + Constants.R_HEADS + ".+$|^" + Constants.R_TAGS + ".+$")
    private String branch = Constants.R_HEADS + Constants.MASTER;

    public String getBranch() {
        return branch;
    }

    public void setBranch(String branch) {
        this.branch = branch;
    }

    @NotNull
    @JsonProperty
    private String servletName = "jitstatic";

    @NotNull
    @JsonProperty
    private String hostedEndpoint = "git";

    @NotNull
    @JsonProperty
    private Path basePath;

    @JsonProperty
    private Path tmpPath;

    @JsonProperty
    @Min(1_000_000)
    @Max(20_000_000)
    private int threshold = 1_000_000;

    @NotNull
    @JsonProperty
    private String userName;

    @NotNull
    @JsonProperty
    private String secret;

    @JsonProperty
    private boolean exposeAll;

    @JsonProperty
    private String adminName = "admin";

    @JsonProperty
    private String adminPass;

    @JsonProperty
    private boolean protectMetrics;

    @JsonProperty
    private boolean protectHealthChecks;
    
    @JsonProperty
    private boolean protectTasks;

    @JsonProperty
    @Valid
    private Cors cors;

    @JsonProperty
    private String privateSalt = null;

    @JsonProperty
    private int iterations = 5;

    public String getServletName() {
        return servletName;
    }

    public void setServletName(String servletName) {
        this.servletName = servletName;
    }

    public Path getBasePath() {
        return basePath;
    }

    public void setBasePath(Path basePath) {
        this.basePath = basePath;
    }

    public boolean isExposeAll() {
        return exposeAll;
    }

    public void setExposeAll(boolean exposeAll) {
        this.exposeAll = exposeAll;
    }

    public String getHostedEndpoint() {
        return hostedEndpoint;
    }

    public void setHostedEndpoint(String hostedEndpoint) {
        this.hostedEndpoint = hostedEndpoint;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public String getAdminName() {
        return adminName;
    }

    public void setAdminName(String adminName) {
        this.adminName = adminName;
    }

    public String getAdminPass() {
        return adminPass;
    }

    public void setAdminPass(String adminPass) {
        this.adminPass = adminPass;
    }

    public boolean isProtectMetrics() {
        return protectMetrics;
    }

    public void setProtectMetrics(boolean protectMetrics) {
        this.protectMetrics = protectMetrics;
    }

    public boolean isProtectHealthChecks() {
        return protectHealthChecks;
    }

    public void setProtectHealthChecks(boolean protectHealthChecks) {
        this.protectHealthChecks = protectHealthChecks;
    }

    public static class Cors {
        @JsonProperty
        @NotEmpty
        private String allowedOrigins = "*";

        @JsonProperty
        @NotEmpty
        private String allowedHeaders = "X-Requested-With,Content-Type,Accept,Origin,if-match";

        @JsonProperty
        @NotEmpty
        private String allowedMethods = "OPTIONS,GET,PUT,POST,DELETE,HEAD";

        @JsonProperty
        @NotEmpty
        private String corsBaseUrl = "/storage,/storage/*,/metakey/*,/users/*,/bulk/*,/info/*";

        @JsonProperty
        @NotEmpty
        private String preflightMaxAge = "1800";

        @JsonProperty
        @NotNull
        private String exposedHeaders = "etag,Content-length";

        public String getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(String allowedOrigins) {
            this.allowedOrigins = allowedOrigins;
        }

        public String getAllowedHeaders() {
            return allowedHeaders;
        }

        public void setAllowedHeaders(String allowedHeaders) {
            this.allowedHeaders = allowedHeaders;
        }

        public String getAllowedMethods() {
            return allowedMethods;
        }

        public void setAllowedMethods(String allowedMethods) {
            this.allowedMethods = allowedMethods;
        }

        public String getCorsBaseUrl() {
            return corsBaseUrl;
        }

        public void setCorsBaseUrl(String corsBaseUrl) {
            this.corsBaseUrl = corsBaseUrl;
        }

        public String getPreflightMaxAge() {
            return preflightMaxAge;
        }

        public void setPreflightMaxAge(String preflightMaxAge) {
            this.preflightMaxAge = preflightMaxAge;
        }

        public String getExposedHeaders() {
            return exposedHeaders;
        }

        public void setExposedHeaders(String exposedHeaders) {
            this.exposedHeaders = exposedHeaders;
        }

    }

    public Source build(final Environment env, final String gitRealm, ExecutorService repoWriter) throws CorruptedSourceException, IOException {
        final HostedGitRepositoryManager hostedGitRepositoryManager = new HostedGitRepositoryManager(getBasePath(), getHostedEndpoint(), getBranch(), repoWriter);
        final HashService hashService = new HashService(getPrivateSalt(), getIterations());
        env.getApplicationContext().addBean(hashService);
        registerCustomDeserializer(env);

        Objects.requireNonNull(gitRealm);
        final String base = "/" + getServletName() + "/";
        final String baseServletPath = base + "*";
        LOG.info("Configuring hosted GIT environment on {}{}", base, getHostedEndpoint());
        final GitServlet gs = new GitServlet();

        gs.setRepositoryResolver(hostedGitRepositoryManager.getRepositoryResolver());

        gs.setReceivePackFactory(hostedGitRepositoryManager.getReceivePackFactory());

        gs.setUploadPackFactory(hostedGitRepositoryManager.getUploadPackFactory());

        final Dynamic servlet = env.servlets().addServlet(getServletName(), gs);
        servlet.setInitParameter(BASE_PATH, hostedGitRepositoryManager.repositoryURI().getRawPath());
        servlet.setInitParameter(EXPOSE_ALL, Boolean.toString(isExposeAll()));
        servlet.setInitParameter(SERVLET_NAME, getServletName());
        servlet.addMapping(baseServletPath);

        final ConstraintMapping baseMapping = new ConstraintMapping();
        baseMapping.setConstraint(new Constraint());
        baseMapping.getConstraint().setAuthenticate(true);
        baseMapping.getConstraint().setDataConstraint(Constraint.DC_NONE);
        baseMapping.getConstraint().setRoles(new String[] {});
        baseMapping.setPathSpec(base + getHostedEndpoint() + "/*");

        final ConstraintMapping infoPath = new ConstraintMapping();
        infoPath.setConstraint(new Constraint());
        infoPath.getConstraint().setAuthenticate(true);
        infoPath.getConstraint().setDataConstraint(Constraint.DC_NONE);
        infoPath.getConstraint().setRoles(new String[] { JitStaticConstants.GIT_PULL });
        infoPath.setPathSpec(base + getHostedEndpoint() + "/info/*");

        final ConstraintMapping receivePath = new ConstraintMapping();
        receivePath.setConstraint(new Constraint());
        receivePath.getConstraint().setAuthenticate(true);
        receivePath.getConstraint().setDataConstraint(Constraint.DC_NONE);
        receivePath.getConstraint().setRoles(new String[] { JitStaticConstants.GIT_PUSH });
        receivePath.setPathSpec(base + getHostedEndpoint() + "/git-receive-pack");

        final ConstraintMapping uploadPath = new ConstraintMapping();
        uploadPath.setConstraint(new Constraint());
        uploadPath.getConstraint().setAuthenticate(true);
        uploadPath.getConstraint().setDataConstraint(Constraint.DC_NONE);
        uploadPath.getConstraint().setRoles(new String[] { JitStaticConstants.GIT_PULL });
        uploadPath.setPathSpec(base + getHostedEndpoint() + "/git-upload-pack");

        final ConstraintSecurityHandler sec = new ConstraintSecurityHandler();

        sec.setRealmName(gitRealm);
        sec.setAuthenticator(new BasicAuthenticator());
        final LoginService gitLoginService = new LoginService(getUserName(), getSecret(), sec.getRealmName(), "refs/heads/" + JitStaticConstants.GIT_SECRETS,
                hashService);
        sec.setLoginService(gitLoginService);
        sec.setConstraintMappings(new ConstraintMapping[] { baseMapping, infoPath, receivePath, uploadPath });

        sec.setHandler(new DropWizardHandlerWrapper(env.getApplicationContext()));
        Set<String> pathsWithUncoveredHttpMethods = sec.getPathsWithUncoveredHttpMethods();

        if (!pathsWithUncoveredHttpMethods.isEmpty()) {
            throw new RuntimeException("Following paths are uncovered " + pathsWithUncoveredHttpMethods);
        }
        env.getApplicationContext().addBean(gitLoginService);
        env.servlets().setSecurityHandler(sec);
        if (isProtectHealthChecks() || isProtectMetrics() || isProtectTasks()) {
            final AdminConstraintSecurityHandler adminConstraintSecurityHandler = new AdminConstraintSecurityHandler(getAdminName(), getAdminPass(),
                    isProtectHealthChecks(), isProtectMetrics(), isProtectTasks());
            env.admin().setSecurityHandler(adminConstraintSecurityHandler);
            pathsWithUncoveredHttpMethods = adminConstraintSecurityHandler.getPathsWithUncoveredHttpMethods();
            pathsWithUncoveredHttpMethods.stream().forEach(p -> LOG.warn("Not protecting {}", p));
        }
        final Cors corsConfig = getCors();
        if (corsConfig != null) {
            final FilterRegistration.Dynamic filter = env.servlets().addFilter("CORS", InterceptingCrossOriginFilter.class);
            filter.setInitParameter("allowedOrigins", corsConfig.allowedOrigins);
            filter.setInitParameter("allowedHeaders", corsConfig.allowedHeaders);
            filter.setInitParameter("allowedMethods", corsConfig.allowedMethods);
            filter.setInitParameter("preflightMaxAge", corsConfig.preflightMaxAge);
            filter.setInitParameter("exposedHeaders", corsConfig.exposedHeaders);
            for (String url : corsConfig.corsBaseUrl.split(",")) {
                url = url.trim();
                filter.addMappingForUrlPatterns(EnumSet.allOf(DispatcherType.class), true, url);
                LOG.info("CORS is enabled for {}", url);
            }
            filter.setInitParameter(CrossOriginFilter.CHAIN_PREFLIGHT_PARAM, Boolean.TRUE.toString());
        }
        return hostedGitRepositoryManager;
    }

    private void registerCustomDeserializer(final Environment env) {
        Path tempfolder = getTmpPath();
        if (tempfolder == null) {
            tempfolder = getBasePath().resolve(".git").resolve("jitstatic").resolve("tmpfolder");
        }
        FilesUtils.checkOrCreateFolder(tempfolder.toFile());
        final ObjectMapper mapper = env.getObjectMapper();
        final SimpleModule module = new SimpleModule();
        module.addDeserializer(ObjectStreamProvider.class, new StreamingDeserializer(getThreshold(), tempfolder.toFile()));
        mapper.registerModule(module);
        env.getObjectMapper().registerModule(module);
    }

    public Cors getCors() {
        return cors;
    }

    public void setCors(Cors cors) {
        this.cors = cors;
    }

    public Path getTmpPath() {
        return tmpPath;
    }

    public void setTmpPath(Path tmpPath) {
        this.tmpPath = tmpPath;
    }

    public int getThreshold() {
        return threshold;
    }

    public void setThreshold(int threshold) {
        this.threshold = threshold;
    }

    public String getPrivateSalt() {
        return privateSalt;
    }

    public void setPrivateSalt(String privateSalt) {
        this.privateSalt = privateSalt;
    }

    public int getIterations() {
        return iterations;
    }

    public void setIterations(int iterations) {
        this.iterations = iterations;
    }

    public boolean isProtectTasks() {
        return protectTasks;
    }

    public void setProtectTasks(boolean protectTasks) {
        this.protectTasks = protectTasks;
    }

    private static class DropWizardHandlerWrapper implements Handler {

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
        public boolean isRunning() {
            return handler.isRunning();
        }

        @Override
        public boolean isStarted() {
            return handler.isStarted();
        }

        @Override
        public boolean isStarting() {
            return handler.isStarting();
        }

        @Override
        public boolean isStopping() {
            return handler.isStopping();
        }

        @Override
        public boolean isStopped() {
            return handler.isStopped();
        }

        @Override
        public boolean isFailed() {
            return handler.isFailed();
        }

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
        public Server getServer() {
            return handler.getServer();
        }

        @Override
        public void destroy() {
            handler.destroy();
        }

    }
}
