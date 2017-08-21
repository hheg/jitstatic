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


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;

import javax.servlet.Servlet;
import javax.servlet.ServletRegistration.Dynamic;
import javax.validation.Validation;
import javax.validation.Validator;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;

import io.dropwizard.jetty.MutableServletContextHandler;
import io.dropwizard.jetty.setup.ServletEnvironment;
import io.dropwizard.setup.Environment;
import jitstatic.hosted.HostedFactory;
import jitstatic.hosted.HostedGitRepositoryManager;
import jitstatic.source.Source;

public class HostedFactoryTest {
	
	private Environment env = mock(Environment.class);
	private ServletEnvironment senv = mock(ServletEnvironment.class);
	private Dynamic servlet = mock(Dynamic.class);
	private MutableServletContextHandler handler = mock(MutableServletContextHandler.class);
	
	private Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
	
	@Rule
	public final TemporaryFolder tempFolder = new TemporaryFolder();
		
	@Test
	public void testAHostedRemoteFileBuild() throws IOException {
		when(env.servlets()).thenReturn(senv);
		when(senv.addServlet(any(), Mockito.<Servlet>any())).thenReturn(servlet);
		when(env.getApplicationContext()).thenReturn(handler);
		
		HostedFactory rf = new HostedFactory();
		rf.setBasePath(tempFolder.newFolder().toPath().toAbsolutePath());
		rf.setHostedEndpoint("endpoint");
		rf.setUserName("user");
		rf.setSecret("secret");
		rf.setServletName("servletName");
		assertTrue(validator.validate(rf).isEmpty());
		
		Source source = rf.build(env);
		assertEquals(source.getContact().repositoryURI(), rf.getBasePath().resolve(HostedGitRepositoryManager.BARE).toUri());
		
	}
}
