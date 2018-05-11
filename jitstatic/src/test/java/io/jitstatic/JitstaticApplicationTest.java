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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import com.codahale.metrics.health.HealthCheck;
import com.codahale.metrics.health.HealthCheckRegistry;

import io.dropwizard.jersey.setup.JerseyEnvironment;
import io.dropwizard.lifecycle.setup.LifecycleEnvironment;
import io.dropwizard.setup.Environment;
import io.jitstatic.api.MapResource;
import io.jitstatic.hosted.HostedFactory;
import io.jitstatic.source.Source;
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

    private final JitstaticApplication app = new JitstaticApplication();
    private JitstaticConfiguration config;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.initMocks(this);
        config = new JitstaticConfiguration();
        config.setStorageFactory(storageFactory);

        when(environment.lifecycle()).thenReturn(lifecycle);
        when(environment.jersey()).thenReturn(jersey);
        when(environment.healthChecks()).thenReturn(hcr);
        when(storageFactory.build(any(), isA(Environment.class))).thenReturn(storage);
    }

    @Test
    public void buildsAMapResource() throws Exception {
        config.setHostedFactory(hostedFactory);
        when(hostedFactory.build(any())).thenReturn(source);
        app.run(config, environment);
        verify(jersey).register(isA(MapResource.class));
    }

    @Test
    public void buildsAstorageHealthCheck() throws Exception {
        config.setHostedFactory(hostedFactory);
        when(hostedFactory.build(any())).thenReturn(source);
        app.run(config, environment);
        verify(hcr).register(eq("storagechecker"), isA(HealthCheck.class));
    }

    @Test
    public void testRemoteManagerLifeCycleManagerIsRegistered() throws Exception {
        config.setHostedFactory(hostedFactory);
        when(hostedFactory.build(any())).thenReturn(source);
        app.run(config, environment);
        verify(lifecycle, times(1)).manage(isA(ManagedObject.class));
    }

    @Test
    public void testStorageLifeCycleManagerIsRegisterd() throws Exception {
        config.setHostedFactory(hostedFactory);
        when(hostedFactory.build(any())).thenReturn(source);
        app.run(config, environment);
        verify(lifecycle, times(1)).manage(isA(AutoCloseableLifeCycleManager.class));
    }

    @Test
    public void testResourcesAreGettingClosed() throws Exception {
        assertThrows(RuntimeException.class, () -> {
            config.setHostedFactory(hostedFactory);
            when(hostedFactory.build(any())).thenReturn(source);
            Mockito.doThrow(new RuntimeException()).when(jersey).register(any(MapResource.class));
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
        TestException r = new TestException("Test Exception");
        assertSame(r, assertThrows(TestException.class, () -> {
            HostedFactory hf = mock(HostedFactory.class);
            config.setHostedFactory(hf);
            doThrow(r).when(hf).build(environment);
            app.run(config, environment);
        }));
    }

    @Test
    public void testBothHostedAndRemoteConfigurationIsSet() throws Exception {
        config.setStorageFactory(storageFactory);
        config.setHostedFactory(hostedFactory);
        when(hostedFactory.build(environment)).thenReturn(source);
        when(storageFactory.build(source, environment)).thenReturn(storage);
        app.run(config, environment);
    }

    @Test
    public void testClosingSourceAndThrow() throws Exception {
        assertThrows(TestException.class, () -> {
            doThrow(new TestException("Test exception1")).when(source).close();
            doThrow(new TestException("Test exception2")).when(storage).close();
            config.setStorageFactory(storageFactory);
            config.setHostedFactory(hostedFactory);
            when(config.getAddKeyAuthenticator()).thenThrow(new TestException("Test exception3"));
            when(hostedFactory.build(environment)).thenReturn(source);
            when(storageFactory.build(source, environment)).thenReturn(storage);
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
