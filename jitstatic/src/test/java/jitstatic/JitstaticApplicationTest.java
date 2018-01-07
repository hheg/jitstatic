package jitstatic;

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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import com.codahale.metrics.health.HealthCheck;
import com.codahale.metrics.health.HealthCheckRegistry;

import io.dropwizard.jersey.setup.JerseyEnvironment;
import io.dropwizard.lifecycle.setup.LifecycleEnvironment;
import io.dropwizard.setup.Environment;
import jitstatic.api.MapResource;
import jitstatic.hosted.HostedFactory;
import jitstatic.remote.RemoteFactory;
import jitstatic.source.Source;
import jitstatic.storage.Storage;
import jitstatic.storage.StorageFactory;

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
	private RemoteFactory remoteFactory;
	@Mock
	private StorageFactory storageFactory;
	@Mock
	private HostedFactory hostedFactory;
	@Mock
	private Source source;
	@Mock
	private Storage storage;
	@Rule
	public ExpectedException ex = ExpectedException.none();

	private final JitstaticApplication app = new JitstaticApplication();
	private JitstaticConfiguration config;

	@Before
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
	public void buildsAMapResourceWithRemote() throws Exception {
		config.setRemoteFactory(remoteFactory);
		config.setStorageFactory(storageFactory);
		when(remoteFactory.build(any())).thenReturn(source);
		app.run(config, environment);
		verify(jersey).register(isA(MapResource.class));
	}

	@Test
	public void buildsAstorageHealthCheckWithRemote() throws Exception {
		config.setRemoteFactory(remoteFactory);
		when(remoteFactory.build(any())).thenReturn(source);
		app.run(config, environment);
		verify(hcr).register(eq("storagechecker"), isA(HealthCheck.class));
	}

	@Test
	public void testRemoteManagerLifeCycleManagerIsRegisteredWithRemote() throws Exception {
		config.setRemoteFactory(remoteFactory);
		when(remoteFactory.build(any())).thenReturn(source);
		app.run(config, environment);
		verify(lifecycle, times(1)).manage(isA(ManagedObject.class));
	}

	@Test
	public void testStorageLifeCycleManagerIsRegisterdWithRemote() throws Exception {
		config.setRemoteFactory(remoteFactory);
		config.setStorageFactory(storageFactory);
		when(remoteFactory.build(any())).thenReturn(source);
		app.run(config, environment);
		verify(lifecycle, times(1)).manage(isA(AutoCloseableLifeCycleManager.class));
	}

	@Test
	public void testResourcesAreGettingClosed() throws Exception {
		ex.expect(RuntimeException.class);
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
	}
	
	@Test
	public void testNoConfigSet() throws Exception {
		ex.expect(IllegalStateException.class);
		ex.expectMessage("Either hosted or remote must be chosen");
		app.run(config, environment);
	}
	
	@Test
	public void testDealingWhenFailed() throws Exception {
		RuntimeException r = new RuntimeException();
		ex.expect(Matchers.sameInstance(r));
		HostedFactory hf = mock(HostedFactory.class);
		config.setHostedFactory(hf);
		doThrow(r).when(hf).build(environment);
		app.run(config,environment);
	}
	
	@Test
	public void testBothHostedAndRemoteConfigurationIsSet() throws Exception {
		config.setStorageFactory(storageFactory);
		config.setHostedFactory(hostedFactory);
		config.setRemoteFactory(remoteFactory);
		when(hostedFactory.build(environment)).thenReturn(source);
		when(storageFactory.build(source, environment)).thenReturn(storage);
		app.run(config, environment);
	}
}
