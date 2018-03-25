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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.concurrent.ExecutorService;

import javax.servlet.http.HttpServletRequest;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;
import org.eclipse.jgit.transport.resolver.ServiceNotEnabledException;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;

import io.jitstatic.hosted.ErrorReporter;
import io.jitstatic.hosted.JitStaticReceivePackFactory;
import io.jitstatic.hosted.RepositoryBus;

public class JitStaticReceivePackFactoryTest {

	@ClassRule
	public static final TemporaryFolder FOLDER = new TemporaryFolder();
	
	@Rule
	public ExpectedException ex = ExpectedException.none();

	private Git git;

	private ExecutorService service = mock(ExecutorService.class);
	private final ErrorReporter reporter = new ErrorReporter();
	private final String defaultRef = "refs/heads/master";
	private HttpServletRequest req = mock(HttpServletRequest.class);

	@Before
	public void setup() throws IllegalStateException, GitAPIException, IOException {
		git = Git.init().setDirectory(FOLDER.newFolder()).call();
	}

	@After
	public void tearDown() {
		git.close();
	}

	@Test
	public void testJitStaticReceivePackFactory() throws ServiceNotEnabledException, ServiceNotAuthorizedException {
		String user = "user", host = "remotehost";
		JitStaticReceivePackFactory jsrpf = new JitStaticReceivePackFactory(service, reporter, defaultRef, new RepositoryBus(reporter));
		when(req.getRemoteUser()).thenReturn(user);
		when(req.getRemoteHost()).thenReturn(host);
		ReceivePack create = jsrpf.create(req, git.getRepository());
		assertNotNull(create);
		PersonIdent refLogIdent = create.getRefLogIdent();
		assertNotNull(refLogIdent);
		assertEquals(user, refLogIdent.getName());
		assertEquals(user + "@" + host, refLogIdent.getEmailAddress());
		assertTrue(create.isAtomic());
	}

	@Test
	public void testJitStaticReceivePackFactoryNoUser() throws ServiceNotEnabledException, ServiceNotAuthorizedException {
		ex.expect(ServiceNotAuthorizedException.class);
		ex.expectMessage("Unauthorized");
		String host = "remotehost";
		JitStaticReceivePackFactory jsrpf = new JitStaticReceivePackFactory(service, reporter, defaultRef, new RepositoryBus(reporter));
		when(req.getRemoteHost()).thenReturn(host);
		jsrpf.create(req, git.getRepository());
	}

	@Test
	public void testJitStaticReceivePackFactoryReceivePackTurnedOff() throws ServiceNotEnabledException, ServiceNotAuthorizedException {
		ex.expect(ServiceNotEnabledException.class);
		ex.expectMessage("Service not enabled");		
		String user = "user", host = "remotehost";
		JitStaticReceivePackFactory jsrpf = new JitStaticReceivePackFactory(service, reporter, defaultRef, new RepositoryBus(reporter));
		git.getRepository().getConfig().setString("http", null, "receivepack", "false");
		when(req.getRemoteUser()).thenReturn(user);
		when(req.getRemoteHost()).thenReturn(host);
		jsrpf.create(req, git.getRepository());
	}

	@Test
	public void testJitStaticReceivePackFactoryReceivePackTurnedOn() throws ServiceNotEnabledException, ServiceNotAuthorizedException {
		String user = "user", host = "remotehost";
		JitStaticReceivePackFactory jsrpf = new JitStaticReceivePackFactory(service, reporter, defaultRef, new RepositoryBus(reporter));
		git.getRepository().getConfig().setString("http", null, "receivepack", "true");
		when(req.getRemoteUser()).thenReturn(user);
		when(req.getRemoteHost()).thenReturn(host);
		ReceivePack create = jsrpf.create(req, git.getRepository());
		assertNotNull(create);
		PersonIdent refLogIdent = create.getRefLogIdent();
		assertNotNull(refLogIdent);
		assertEquals(user, refLogIdent.getName());
		assertEquals(user + "@" + host, refLogIdent.getEmailAddress());
		assertTrue(create.isAtomic());
	}
}
