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

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.ExecutorService;

import javax.servlet.FilterRegistration.Dynamic;
import javax.validation.Validator;

import org.eclipse.jetty.servlets.CrossOriginFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.health.HealthCheck;
import com.codahale.metrics.health.HealthCheckRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.dropwizard.jersey.setup.JerseyEnvironment;
import io.dropwizard.jetty.MutableServletContextHandler;
import io.dropwizard.jetty.setup.ServletEnvironment;
import io.dropwizard.lifecycle.setup.LifecycleEnvironment;
import io.dropwizard.setup.Environment;
import io.jitstatic.api.KeyResource;
import io.jitstatic.hosted.HostedFactory;
import io.jitstatic.hosted.LoginService;
import io.jitstatic.source.Source;
import io.jitstatic.storage.HashService;
import io.jitstatic.storage.Storage;
import io.jitstatic.storage.StorageFactory;

public class JitstaticApplicationTest {
    @Mock
    private Environment environment;
    @Mock
    private JerseyEnvironment jersey;
    @Mock
    private HealthCheckRegistry hcr;
    @Mock
    private LifecycleEnvironment lifecycle;
    @Mock
    private StorageFactory storageFactory;
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

    private HashService hashService = new HashService(); 
    private JitstaticConfiguration config;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.initMocks(this);
        config = new JitstaticConfiguration();
        config.setStorageFactory(storageFactory);
        when(hostedFactory.getBranch()).thenReturn("refs/heads/master");
        when(environment.servlets()).thenReturn(servlets);
        when(servlets.addFilter(Mockito.eq("CORS"), Mockito.eq(CrossOriginFilter.class))).thenReturn(filter);
        when(environment.lifecycle()).thenReturn(lifecycle);
        when(environment.jersey()).thenReturn(jersey);
        when(environment.healthChecks()).thenReturn(hcr);
        when(storageFactory.build(any(), isA(Environment.class), any(), any(), any(), any(), any(), any(), any())).thenReturn(storage);
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
        when(hostedFactory.build(any(), any(), any())).thenReturn(source);
        app.run(config, environment);
        verify(jersey).register(isA(KeyResource.class));
    }

    @Test
    public void buildsAstorageHealthCheck() throws Exception {
        JitstaticApplication app = new JitstaticApplication();
        config.setHostedFactory(hostedFactory);
        when(hostedFactory.build(any(), any(), any())).thenReturn(source);
        app.run(config, environment);
        verify(hcr).register(eq("storagechecker"), isA(HealthCheck.class));
    }

    @Test
    public void testRemoteManagerLifeCycleManagerIsRegistered() throws Exception {
        JitstaticApplication app = new JitstaticApplication();
        config.setHostedFactory(hostedFactory);
        when(hostedFactory.build(any(), any(), any())).thenReturn(source);
        app.run(config, environment);
        verify(lifecycle, times(1)).manage(isA(ManagedObject.class));
    }

    @Test
    public void testStorageLifeCycleManagerIsRegisterd() throws Exception {
        JitstaticApplication app = new JitstaticApplication();
        config.setHostedFactory(hostedFactory);
        when(hostedFactory.build(any(), any(), any())).thenReturn(source);
        app.run(config, environment);
        verify(lifecycle, times(2)).manage(isA(AutoCloseableLifeCycleManager.class));
    }

    @Test
    public void testResourcesAreGettingClosed() throws Exception {
        JitstaticApplication app = new JitstaticApplication();
        assertThrows(RuntimeException.class, () -> {
            config.setHostedFactory(hostedFactory);
            when(hostedFactory.build(any(), any(), any())).thenReturn(source);
            Mockito.doThrow(new RuntimeException()).when(jersey).register(any(KeyResource.class));
            try {
                app.run(config, environment);
            } catch (Exception e) {
                verify(source).close();
                verify(storage).close();
                throw e;
            }
        });
    }

    @Test
    public void testDealingWhenFailed() throws Exception {
        JitstaticApplication app = new JitstaticApplication();
        TestException r = new TestException("Test Exception");
        HostedFactory hf = mock(HostedFactory.class);
        config.setHostedFactory(hf);
        doThrow(r).when(hf).build(Mockito.eq(environment), Mockito.eq(JitStaticConstants.JITSTATIC_GIT_REALM), any());
        assertSame(r, assertThrows(TestException.class, () -> {
            app.run(config, environment);
        }));
    }

    @Test
    public void testBothHostedAndRemoteConfigurationIsSet() throws Exception {
        JitstaticApplication app = new JitstaticApplication();
        config.setStorageFactory(storageFactory);
        config.setHostedFactory(hostedFactory);
        when(hostedFactory.build(Mockito.eq(environment), Mockito.eq(JitStaticConstants.JITSTATIC_GIT_REALM), any())).thenReturn(source);
        when(storageFactory.build(Mockito.eq(source), Mockito.eq(environment), Mockito.eq(JitStaticConstants.JITSTATIC_KEYADMIN_REALM), Mockito
                .eq(hashService), any(), any(), any(), any(), any())).thenReturn(storage);
        app.run(config, environment);
        
    }

    @Test
    public void testClosingSourceAndThrow() throws Exception {
        JitstaticApplication app = new JitstaticApplication();
        assertThrows(TestException.class, () -> {
            doThrow(new TestException("Test exception1")).when(source).close();
            doThrow(new TestException("Test exception2")).when(storage).close();
            config.setStorageFactory(storageFactory);
            config.setHostedFactory(hostedFactory);
            when(config.getRootAuthenticator()).thenThrow(new TestException("Test exception3"));
            when(hostedFactory.build(Mockito.eq(environment), Mockito.eq(JitStaticConstants.JITSTATIC_GIT_REALM), any())).thenReturn(source);
            when(storageFactory.build(Mockito.eq(source), Mockito.eq(environment), Mockito.eq(JitStaticConstants.JITSTATIC_KEYADMIN_REALM), Mockito
                    .eq(hashService), any(), any(), any(), any(), any())).thenReturn(storage);
            app.run(config, environment);
        });
    }

    private static class TestException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public TestException(String msg) {
            super(msg);
        }
    }
}
