package io.jitstatic;

/*-
 * #%L
 * jitstatic
 * %%
 * Copyright (C) 2017 - 2019 H.Hegardt
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.concurrent.ExecutorService;

import javax.servlet.FilterRegistration.Dynamic;
import javax.servlet.Servlet;
import javax.validation.Validator;

import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.component.LifeCycle.Listener;
import org.eclipse.jgit.http.server.GitServlet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.health.HealthCheckRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.dropwizard.jersey.setup.JerseyEnvironment;
import io.dropwizard.jetty.MutableServletContextHandler;
import io.dropwizard.jetty.setup.ServletEnvironment;
import io.dropwizard.lifecycle.Managed;
import io.dropwizard.lifecycle.setup.LifecycleEnvironment;
import io.dropwizard.setup.AdminEnvironment;
import io.dropwizard.setup.Environment;
import io.dropwizard.util.Duration;
import io.jitstatic.api.KeyResource;
import io.jitstatic.injection.configuration.JitstaticConfiguration;
import io.jitstatic.injection.configuration.hosted.HostedFactory;
import io.jitstatic.injection.configuration.hosted.HostedFactory.Cors;
import io.jitstatic.injection.configuration.reporting.ConsoleReporting;
import io.jitstatic.injection.configuration.reporting.ReportingFactory;
import io.jitstatic.hosted.InterceptingCrossOriginFilter;
import io.jitstatic.hosted.LoginService;
import io.jitstatic.source.Source;
import io.jitstatic.storage.HashService;
import io.jitstatic.storage.Storage;
import io.jitstatic.test.BaseTest;
import io.jitstatic.test.TemporaryFolder;
import io.jitstatic.test.TemporaryFolderExtension;

@ExtendWith({ TemporaryFolderExtension.class })
public class JitstaticApplicationTest extends BaseTest {
    @Mock
    private Environment environment;
    @Mock
    private JerseyEnvironment jersey;
    @Mock
    private HealthCheckRegistry hcr;
    @Mock
    private LifecycleEnvironment lifecycle;
    @Mock
    private HostedFactory hostedFactory;
    @Mock
    private Source source;
    @Mock
    private Storage storage;
    @Mock
    private MutableServletContextHandler handler;
    @Mock
    private LoginService service;
    @Mock
    private ServletEnvironment servlets;
    @Mock
    private Dynamic filter;
    @Mock
    private Validator validator;
    @Mock
    private ObjectMapper mapper;
    @Mock
    private ExecutorService executor;
    @Mock
    private MetricRegistry registry;
    @Mock
    private AdminEnvironment adminEnv;

    private HashService hashService = new HashService();
    private JitstaticConfiguration config;

    private TemporaryFolder tmpFolder;

    @BeforeEach
    public void setup() throws IOException {
        MockitoAnnotations.initMocks(this);
        config = new JitstaticConfiguration();
        when(hostedFactory.getBranch()).thenReturn("refs/heads/master");
        when(hostedFactory.getBasePath()).thenReturn(tmpFolder.createTemporaryDirectory().toPath());
        when(hostedFactory.getServletName()).thenReturn("name");
        when(hostedFactory.getUserName()).thenReturn("user");
        when(hostedFactory.getSecret()).thenReturn("secret");
        when(environment.servlets()).thenReturn(servlets);
        when(environment.getApplicationContext()).thenReturn(handler);
        when(environment.admin()).thenReturn(adminEnv);
        when(servlets.addServlet(eq("name"), Mockito.<GitServlet>any())).thenReturn(mock(javax.servlet.ServletRegistration.Dynamic.class));
        when(servlets.addFilter(Mockito.eq("CORS"), Mockito.eq(InterceptingCrossOriginFilter.class))).thenReturn(filter);
        when(environment.lifecycle()).thenReturn(lifecycle);
        when(environment.jersey()).thenReturn(jersey);
        when(environment.healthChecks()).thenReturn(hcr);
        when(environment.getApplicationContext()).thenReturn(handler);
        when(handler.getBean(Mockito.eq(LoginService.class))).thenReturn(service);
        when(handler.getBean(Mockito.eq(HashService.class))).thenReturn(hashService);
        when(environment.getValidator()).thenReturn(validator);
        when(environment.getObjectMapper()).thenReturn(mapper);
        when(environment.metrics()).thenReturn(registry);
    }

    @Test
    public void buildsAMapResource() throws Exception {
        JitstaticApplication app = new JitstaticApplication();
        config.setHostedFactory(hostedFactory);
        app.run(config, environment);
        verify(jersey).register(eq(KeyResource.class));
    }

    @Test
    public void testGitServletIsRegistered() throws Exception {
        JitstaticApplication app = new JitstaticApplication();
        config.setHostedFactory(hostedFactory);
        app.run(config, environment);
        verify(handler).addBean(isA(GitServlet.class));
    }

    @Test
    public void testConsoleReporting() throws Exception {
        ReportingFactory reportingFactory = mock(ReportingFactory.class);
        ConsoleReporting consoleReporting = mock(ConsoleReporting.class);
        when(reportingFactory.getConsole()).thenReturn(consoleReporting);
        when(consoleReporting.getRates()).thenReturn("s");
        when(consoleReporting.getDurations()).thenReturn(Duration.seconds(5));
        when(consoleReporting.getReportPeriods()).thenReturn(Duration.seconds(5));
        ArgumentCaptor<Managed> arg = ArgumentCaptor.forClass(Managed.class);
        JitstaticApplication app = new JitstaticApplication();
        config.setHostedFactory(hostedFactory);
        config.setReportingFactory(reportingFactory);
        app.run(config, environment);
        verify(lifecycle).manage(arg.capture());
        Managed managed = arg.getValue();
        managed.start();
        managed.stop();
        verify(consoleReporting, times(2)).getReportPeriods();
    }

    @Test
    public void testSetupAdmin() throws Exception {
        JitstaticApplication app = new JitstaticApplication();
        config.setHostedFactory(hostedFactory);
        when(hostedFactory.isProtectHealthChecks()).thenReturn(true);
        when(hostedFactory.isProtectMetrics()).thenReturn(true);
        when(hostedFactory.isProtectTasks()).thenReturn(true);
        when(hostedFactory.getAdminName()).thenReturn("name");
        when(hostedFactory.getAdminPass()).thenReturn("pass");
        app.run(config, environment);
        verify(adminEnv).setSecurityHandler(any());
    }

    @Test
    public void testSetupCors() throws Exception {
        JitstaticApplication app = new JitstaticApplication();
        config.setHostedFactory(hostedFactory);
        when(hostedFactory.getCors()).thenReturn(new Cors());
        app.run(config, environment);
        verify(filter, times(6)).setInitParameter(any(), any());
    }

    @Test
    public void testHandlerManagement() throws Exception {
        ArgumentCaptor<SecurityHandler> ac = ArgumentCaptor.forClass(SecurityHandler.class);
        when(environment.servlets()).thenReturn(servlets);
        when(servlets.addServlet(any(), Mockito.<Servlet>any())).thenReturn(mock(javax.servlet.ServletRegistration.Dynamic.class));
        when(environment.getApplicationContext()).thenReturn(handler);
        when(environment.getObjectMapper()).thenReturn(mapper);
        HostedFactory hf = new HostedFactory();
        config.setHostedFactory(hf);
        hf.setBasePath(tmpFolder.createTemporaryDirectory().toPath());
        hf.setTmpPath(tmpFolder.createTemporaryDirectory().toPath());
        hf.setHostedEndpoint("endpoint");
        hf.setUserName("user");
        hf.setSecret("secret");
        hf.setServletName("servletName");
        hf.setBranch("refs/heads/master");
        JitstaticApplication app = new JitstaticApplication();
        app.run(config, environment);
        verify(servlets).setSecurityHandler(ac.capture());
        SecurityHandler sh = ac.getValue();
        Handler h = sh.getHandler();
        h.addLifeCycleListener(mock(Listener.class));
        h.destroy();
        h.getServer();
        h.isFailed();
        h.isRunning();
        h.isStarted();
        h.isStarting();
        h.isStopped();
        h.isStopping();
        h.removeLifeCycleListener(mock(Listener.class));
        h.setServer(mock(Server.class));
    }
}
