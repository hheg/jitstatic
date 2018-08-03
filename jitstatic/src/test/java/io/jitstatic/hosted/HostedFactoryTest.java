package io.jitstatic.hosted;

/*-
 * #%L
 * jitstatic
 * %%
 * Copyright (C) 2017 - 2018 H.Hegardt
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

import javax.servlet.Servlet;
import javax.servlet.ServletRegistration.Dynamic;
import javax.validation.ConstraintViolation;
import javax.validation.Validation;
import javax.validation.Validator;

import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.component.LifeCycle.Listener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import io.dropwizard.jetty.MutableServletContextHandler;
import io.dropwizard.jetty.setup.ServletEnvironment;
import io.dropwizard.setup.Environment;
import io.jitstatic.check.CorruptedSourceException;
import io.jitstatic.hosted.HostedFactory;
import io.jitstatic.source.Source;
import io.jitstatic.test.TemporaryFolder;
import io.jitstatic.test.TemporaryFolderExtension;
@ExtendWith(TemporaryFolderExtension.class)
public class HostedFactoryTest {

    private Environment env = mock(Environment.class);
    private ServletEnvironment senv = mock(ServletEnvironment.class);
    private Dynamic servlet = mock(Dynamic.class);
    private MutableServletContextHandler handler = mock(MutableServletContextHandler.class);
    private TemporaryFolder tmpFolder;
    private Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    public void testAHostedRemoteFileBuild() throws IOException, CorruptedSourceException {
        when(env.servlets()).thenReturn(senv);
        when(senv.addServlet(any(), Mockito.<Servlet>any())).thenReturn(servlet);
        when(env.getApplicationContext()).thenReturn(handler);

        HostedFactory rf = new HostedFactory();
        rf.setBasePath(getFolder().toAbsolutePath());
        rf.setHostedEndpoint("endpoint");
        rf.setUserName("user");
        rf.setSecret("secret");
        rf.setServletName("servletName");
        assertTrue(validator.validate(rf).isEmpty());

        Source source = rf.build(env);
        assertNotNull(source);
    }

    @Test
    public void testWithFulldBranchName() throws IOException, CorruptedSourceException {
        when(env.servlets()).thenReturn(senv);
        when(senv.addServlet(any(), Mockito.<Servlet>any())).thenReturn(servlet);
        when(env.getApplicationContext()).thenReturn(handler);
        HostedFactory hf = new HostedFactory();
        hf.setBasePath(getFolder().toAbsolutePath());
        hf.setHostedEndpoint("endpoint");
        hf.setUserName("user");
        hf.setSecret("secret");
        hf.setServletName("servletName");
        hf.setBranch("refs/heads/master");
        Source source = hf.build(env);
        assertNotNull(source);
    }

    @Test
    public void testHostedFactoryNotNullHandler() throws Exception {
        ArgumentCaptor<SecurityHandler> ac = ArgumentCaptor.forClass(SecurityHandler.class);
        when(env.servlets()).thenReturn(senv);
        when(senv.addServlet(any(), Mockito.<Servlet>any())).thenReturn(servlet);
        when(env.getApplicationContext()).thenReturn(handler);
        HostedFactory hf = new HostedFactory();
        hf.setBasePath(getFolder().toAbsolutePath());
        hf.setHostedEndpoint("endpoint");
        hf.setUserName("user");
        hf.setSecret("secret");
        hf.setServletName("servletName");
        hf.setBranch("refs/heads/master");
        Source source = hf.build(env);
        assertNotNull(source);
        verify(senv).setSecurityHandler(ac.capture());
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

    @Test
    public void testBranchName() {
        HostedFactory si = new HostedFactory();
        si.setBasePath(Paths.get("."));
        si.setSecret("ss");
        si.setUserName("user");
        si.setHostedEndpoint("ep");
        si.setServletName("servlet");
        si.setBranch("refs/tags/tag");
        Set<ConstraintViolation<HostedFactory>> validate = validator.validate(si);
        assertTrue(validate.isEmpty());
        si.setBranch("refs/heads/branch");
        validate = validator.validate(si);
        assertTrue(validate.isEmpty());
        si.setBranch("");
        validate = validator.validate(si);
        assertFalse(validate.isEmpty());
        si.setBranch("garbage/blargh");
        validate = validator.validate(si);
        assertFalse(validate.isEmpty());
    }

    private Path getFolder() throws IOException {
        return tmpFolder.createTemporaryDirectory().toPath();
    }
}
