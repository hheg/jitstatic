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
import java.util.Objects;
import java.util.Set;

import javax.servlet.ServletException;
import javax.servlet.ServletRegistration.Dynamic;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;

import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.security.Constraint;
import org.eclipse.jgit.http.server.GitServlet;
import org.eclipse.jgit.lib.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.dropwizard.setup.Environment;
import io.jitstatic.auth.AdminConstraintSecurityHandler;
import io.jitstatic.check.CorruptedSourceException;
import io.jitstatic.hosted.HostedGitRepositoryManager;
import io.jitstatic.source.Source;

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

    public Source build(final Environment env, final String gitRealm) throws CorruptedSourceException, IOException {
        final HostedGitRepositoryManager hostedGitRepositoryManager = new HostedGitRepositoryManager(getBasePath(), getHostedEndpoint(), getBranch());
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

        final ConstraintMapping infoPath = new ConstraintMapping();
        infoPath.setConstraint(new Constraint());
        infoPath.getConstraint().setAuthenticate(true);
        infoPath.getConstraint().setDataConstraint(Constraint.DC_NONE);
        infoPath.getConstraint().setRoles(new String[] { "pull" });
        infoPath.setPathSpec(base + getHostedEndpoint() + "/info/*");

        final ConstraintMapping receivePath = new ConstraintMapping();
        receivePath.setConstraint(new Constraint());
        receivePath.getConstraint().setAuthenticate(true);
        receivePath.getConstraint().setDataConstraint(Constraint.DC_NONE);
        receivePath.getConstraint().setRoles(new String[] { "push" });
        receivePath.setPathSpec(base + getHostedEndpoint() + "/git-receive-pack");

        final ConstraintSecurityHandler sec = new ConstraintSecurityHandler();

        sec.setRealmName(gitRealm);
        sec.setAuthenticator(new BasicAuthenticator());
        final LoginService gitLoginService = new LoginService(getUserName(), getSecret(), sec.getRealmName(), getBranch());
        sec.setLoginService(gitLoginService);
        sec.setConstraintMappings(new ConstraintMapping[] { infoPath, receivePath });

        sec.setHandler(new DropWizardHandlerWrapper(env.getApplicationContext()));
        Set<String> pathsWithUncoveredHttpMethods = sec.getPathsWithUncoveredHttpMethods();

        if (!pathsWithUncoveredHttpMethods.isEmpty()) {
            throw new RuntimeException("Following paths are uncovered " + pathsWithUncoveredHttpMethods);
        }
        env.getApplicationContext().addBean(gitLoginService);
        env.servlets().setSecurityHandler(sec);
        if (isProtectHealthChecks() || isProtectMetrics()) {
            AdminConstraintSecurityHandler adminConstraintSecurityHandler = new AdminConstraintSecurityHandler(getAdminName(), getAdminPass(),
                    isProtectHealthChecks(), isProtectMetrics());
            env.admin().setSecurityHandler(adminConstraintSecurityHandler);
            pathsWithUncoveredHttpMethods = adminConstraintSecurityHandler.getPathsWithUncoveredHttpMethods();
            pathsWithUncoveredHttpMethods.stream().forEach(p -> LOG.info("Not protecting {}", p));
        }

        return hostedGitRepositoryManager;
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
