package jitstatic.remote;

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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.hamcrest.Matchers.isA;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;

import javax.validation.Validation;
import javax.validation.Validator;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;

import io.dropwizard.setup.Environment;
import jitstatic.CorruptedSourceException;
import jitstatic.source.Source;

public class RemoteFactoryTest {

	private static final String STORE = "store";

	private Environment env = mock(Environment.class);

	@Rule
	public ExpectedException ex = ExpectedException.none();
	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

	private File remoteFolder;
	private RemoteFactory rf;

	@Before
	public void setup() throws IOException {
		remoteFolder = folder.newFolder();
		rf =  new RemoteFactory();
	}

	@Test
	public void testAHostedRemoteBuild()
			throws URISyntaxException, IOException, IllegalStateException, GitAPIException, CorruptedSourceException {
		setUpRepo();
		
		rf.setRemotePassword("pwd");
		rf.setUserName("user");
		URI uri = remoteFolder.toURI();
		rf.setRemoteRepo(uri);
		rf.setBasePath(folder.newFolder().toPath());
		try (Source source = rf.build(env);) {
			assertNotNull(source);
		}
	}

	@Test
	public void testAHostedRemoteBuildWithNoRemoteRepo() throws URISyntaxException, IOException, CorruptedSourceException {
		ex.expect(RuntimeException.class);
		ex.expectCause(isA(InvalidRemoteException.class));

		rf.setRemotePassword("pwd");
		rf.setUserName("user");
		URI uri = folder.newFolder().toURI();
		rf.setRemoteRepo(uri);
		rf.setBasePath(folder.newFolder().toPath());
		try (Source source = rf.build(env);) {
		}
	}

	@Test
	public void testIfRemoteRepoParameterIsURIAbsolute() throws URISyntaxException, CorruptedSourceException, IOException {
		ex.expect(IllegalArgumentException.class);
		ex.expectMessage("parameter remoteRepo, /tmp, must be absolute");

		rf.setRemotePassword("");
		rf.setUserName("");
		rf.setRemoteRepo(new URI("/tmp"));
		assertTrue(validator.validate(rf).isEmpty());
		rf.build(env);
	}

	private void setUpRepo() throws IllegalStateException, GitAPIException, IOException {
		final File base = folder.newFolder();
		try (Git bare = Git.init().setBare(true).setDirectory(remoteFolder).call();
				Git git = Git.cloneRepository().setDirectory(base).setURI(remoteFolder.toURI().toString()).call()) {
			Files.write(base.toPath().resolve(STORE), getData().getBytes("UTF-8"));
			git.add().addFilepattern(STORE).call();
			git.commit().setMessage("Commit").call();
			git.push().call();
		}
	}
	
	
	private String getData() {
		return getData(1);
	}

	private String getData(int i) {
		return "{\"data\":{\"key" + i
				+ "\":{\"data\":\"value1\",\"users\":[{\"password\":\"1234\",\"user\":\"user1\"}]},\"key3\":{\"data\":\"value3\",\"users\":[{\"password\":\"1234\",\"user\":\"user1\"}]}},\"users\":[{\"password\":\"1234\",\"user\":\"user1\"}]}";
	}

}
