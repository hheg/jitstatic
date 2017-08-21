package jitstatic.storage;

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


import static org.junit.Assert.assertNull;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;

import javax.validation.Validation;
import javax.validation.Validator;

import static org.mockito.Mockito.*;
import static org.junit.Assert.*;
import org.eclipse.jgit.api.Git;
import org.glassfish.jersey.server.filter.RolesAllowedDynamicFeature;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import io.dropwizard.auth.AuthDynamicFeature;
import io.dropwizard.auth.AuthValueFactoryProvider;
import io.dropwizard.jersey.setup.JerseyEnvironment;
import io.dropwizard.setup.Environment;
import jitstatic.source.Source;
import jitstatic.source.Source.Contact;
import jitstatic.storage.LoaderException;
import jitstatic.storage.Storage;
import jitstatic.storage.StorageFactory;

public class StorageFactoryTest {

	private Environment env = mock(Environment.class);
	private JerseyEnvironment jersey = mock(JerseyEnvironment.class);
	private Source source = mock(Source.class);

	@Rule
	public final TemporaryFolder tempFolder = new TemporaryFolder();

	@Before
	public void setup() throws IOException {
		tempDir = tempFolder.newFolder().toPath();
	}
	private Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
	private StorageFactory sf = new StorageFactory();
	private Path tempDir;
	private Contact remoteRepo;

	@Before
	public void setUp() throws Exception {		
		tempDir = tempFolder.newFolder("base").toPath();
		sf.setBaseDirectory(tempDir.toAbsolutePath().toString());
		sf.setLocalFilePath("storage");
		sf.setUser("user");
		sf.setSecret("secret");
		assertTrue(validator.validate(sf).isEmpty());
		try (Git git = Git.init().setDirectory(tempDir.resolve("bare").toFile()).setBare(true).call();) {
			URI uri = git.getRepository().getDirectory().toURI();
			remoteRepo = new RemoteContact(uri) ;
		}
	}

	@Test
	public void testBuild() throws LoaderException {
		when(env.jersey()).thenReturn(jersey);
		when(source.getContact()).thenReturn(remoteRepo);
		try (Storage storage = sf.build(source, env);) {
			storage.load();
			assertNull(storage.get("key"));
		}
		verify(jersey).register(isA(AuthDynamicFeature.class));
		verify(jersey).register(RolesAllowedDynamicFeature.class);
		verify(jersey).register(isA(AuthValueFactoryProvider.Binder.class));
	}
	
	private static final class RemoteContact implements Contact {
		private final URI uri;

		private RemoteContact(URI uri) {
			this.uri = uri;
		}

		@Override
		public URI repositoryURI() {
			return uri;
		}

		@Override
		public String getUserName() {					
			return null;
		}

		@Override
		public String getPassword() {
			return null;
		}
	}

}
