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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;

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
import jitstatic.source.Source.Contact;
import jitstatic.source.SourceEventListener;
import jitstatic.storage.LoaderException;
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
	private Contact contact;
	@Mock
	private Storage storage;
	@Rule
	public ExpectedException ex = ExpectedException.none();

	private final JitstaticApplication app = new JitstaticApplication();
	private JitstaticConfiguration config;

	@Before
	public void setup() throws LoaderException {
		MockitoAnnotations.initMocks(this);
		config = new JitstaticConfiguration();
		config.setStorageFactory(storageFactory);

		when(environment.lifecycle()).thenReturn(lifecycle);
		when(environment.jersey()).thenReturn(jersey);
		when(environment.healthChecks()).thenReturn(hcr);
		when(storageFactory.build(any(), isA(Environment.class))).thenReturn(storage);
		when(source.getContact()).thenReturn(contact);
		when(contact.repositoryURI()).thenReturn(URI.create("file://tmp"));
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
		when(remoteFactory.build(any())).thenReturn(source);
		app.run(config, environment);
		verify(lifecycle, times(1)).manage(isA(AutoCloseableLifeCycleManager.class));
	}
	@Test
	public void testAddingAStorageListener() throws Exception {
		config.setHostedFactory(hostedFactory);
		when(hostedFactory.build(any())).thenReturn(source);
		app.run(config, environment);
		verify(source).addListener(isA(SourceEventListener.class));
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

}
