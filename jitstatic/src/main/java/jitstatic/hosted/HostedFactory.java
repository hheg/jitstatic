package jitstatic.hosted;

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

import javax.servlet.ServletException;
import javax.servlet.ServletRegistration.Dynamic;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.constraints.NotNull;

import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.security.Constraint;
import org.eclipse.jgit.http.server.GitServlet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.dropwizard.setup.Environment;
import jitstatic.CorruptedSourceException;
import jitstatic.source.Source;
import jitstatic.storage.StorageInfo;

public class HostedFactory extends StorageInfo {
	private static final Logger LOG = LoggerFactory.getLogger(HostedFactory.class);

	public static final String SERVLET_NAME = "servlet-name";
	public static final String BASE_PATH = "base-path";
	public static final String EXPOSE_ALL = "expose-all";

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

	public Source build(final Environment env) throws CorruptedSourceException, IOException {
		final HostedGitRepositoryManager hostedGitRepositoryManager = new HostedGitRepositoryManager(getBasePath(), getHostedEndpoint(),
				getBranch());

		final String baseServletPath = "/" + getServletName() + "/*";
		LOG.info("Configuring hosted GIT environment on " + baseServletPath);
		final GitServlet gs = new GitServlet();

		gs.setRepositoryResolver(hostedGitRepositoryManager.getRepositoryResolver());

		gs.setReceivePackFactory(hostedGitRepositoryManager.getReceivePackFactory());

		final Dynamic servlet = env.servlets().addServlet(getServletName(), gs);
		servlet.setInitParameter(BASE_PATH, hostedGitRepositoryManager.repositoryURI().getRawPath());
		servlet.setInitParameter(EXPOSE_ALL, Boolean.toString(isExposeAll()));
		servlet.setInitParameter(SERVLET_NAME, getServletName());
		servlet.addMapping(baseServletPath);

		final ConstraintMapping cm = new ConstraintMapping();
		cm.setConstraint(new Constraint());
		cm.getConstraint().setAuthenticate(true);
		cm.getConstraint().setDataConstraint(Constraint.DC_NONE);
		cm.getConstraint().setRoles(new String[] { "gitrole" });
		cm.setPathSpec(baseServletPath);

		final ConstraintSecurityHandler sec = new ConstraintSecurityHandler();

		sec.setRealmName("git");
		sec.setAuthenticator(new BasicAuthenticator());
		sec.setLoginService(new SimpleLoginService(getUserName(), getSecret(), sec.getRealmName()));
		sec.setConstraintMappings(new ConstraintMapping[] { cm });

		sec.setHandler(new DropWizardHandlerWrapper(env.getApplicationContext()));
		sec.checkPathsWithUncoveredHttpMethods();

		env.servlets().setSecurityHandler(sec);

		return hostedGitRepositoryManager;
	}

	private static class DropWizardHandlerWrapper implements Handler {

		private Handler handler;

		public DropWizardHandlerWrapper(Handler handler) {
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
		public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
				throws IOException, ServletException {
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
