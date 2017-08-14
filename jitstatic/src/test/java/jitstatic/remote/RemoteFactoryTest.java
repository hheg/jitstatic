package jitstatic.remote;

/*-
 * #%L
 * jitstatic
 * %%
 * Copyright (C) 2017 HHegardt
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import java.net.URI;
import java.net.URISyntaxException;

import javax.validation.Validation;
import javax.validation.Validator;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import io.dropwizard.setup.Environment;
import jitstatic.source.Source;

public class RemoteFactoryTest {

	Environment env = mock(Environment.class);

	@Rule
	public ExpectedException ex = ExpectedException.none();

	private Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

	@Test
	public void testAHostedRemoteBuild() throws URISyntaxException {
		RemoteFactory rf = new RemoteFactory();
		rf.setRemotePassword("pwd");
		rf.setUserName("user");
		rf.setRemoteRepo(new URI("http://127.0.0.1:8080"));
		try (Source source = rf.build(null);) {
			assertEquals(source.getContact().repositoryURI(), new URI("http://127.0.0.1:8080"));
			assertEquals(source.getContact().getUserName(), rf.getUserName());
			assertEquals(source.getContact().getPassword(), rf.getRemotePassword());
		}
	}

	@Test
	public void testIfRemoteRepoParameterIsURIAbsolute() throws URISyntaxException {
		ex.expect(IllegalArgumentException.class);
		ex.expectMessage("parameter remoteRepo, /tmp, must be absolute");

		RemoteFactory rf = new RemoteFactory();
		rf.setRemotePassword("");
		rf.setUserName("");
		rf.setRemoteRepo(new URI("/tmp"));
		assertTrue(validator.validate(rf).isEmpty());
		rf.build(env);
	}

}
