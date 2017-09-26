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


import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.security.Principal;

import javax.security.auth.Subject;
import javax.servlet.ServletRequest;

import org.eclipse.jetty.security.AbstractLoginService;
import org.eclipse.jetty.security.AbstractLoginService.UserPrincipal;
import org.eclipse.jetty.server.UserIdentity;
import org.eclipse.jetty.util.security.Password;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jitstatic.hosted.SimpleLoginService;

@SuppressFBWarnings(value = { "NP_NULL_PARAM_DEREF_NONVIRTUAL","DM_STRING_CTOR" }, justification = "Testing explicitly for this")
public class SimpleLoginServiceTest {

	private static final String user = "user";
	private static final String secret = "secret";
	private static final String realm = "realm";

	private ServletRequest req = mock(ServletRequest.class);
	private UserIdentity uid = mock(UserIdentity.class);

	@Rule
	public ExpectedException ex = ExpectedException.none();

	@Test
	public void testSimpleLoginServiceNullUserName() {
		ex.expect(NullPointerException.class);
		new SimpleLoginService(null, secret, realm);
	}

	@Test
	public void testSimpleLoginServiceNullSecret() {
		ex.expect(NullPointerException.class);
		new SimpleLoginService(user, null, realm);
	}

	@Test
	public void testSimpleLoginServiceNullRealm() {
		ex.expect(NullPointerException.class);
		new SimpleLoginService(user, secret, null);
	}

	@Test
	public void testGetName() {
		SimpleLoginService sls = new SimpleLoginService(user, secret, realm);
		assertEquals(sls.getName(), new String(realm));
	}

	@Test
	public void testLogin() {
		SimpleLoginService sls = new SimpleLoginService(user, secret, realm);
		UserIdentity login = sls.login(user, secret, req);
		assertNotNull(login);
		Principal userPrincipal = login.getUserPrincipal();
		assertNotNull(userPrincipal);
		Subject subject = login.getSubject();
		assertNotNull(subject);
	}

	@Test
	public void testNotLogin() {
		SimpleLoginService sls = new SimpleLoginService(user, secret, realm);
		UserIdentity login = sls.login(user, "", req);
		assertNull(login);
	}

	@Test
	public void testValidate() {
		SimpleLoginService sls = new SimpleLoginService(user, secret, realm);
		when(uid.getUserPrincipal()).thenReturn(new AbstractLoginService.UserPrincipal(user, new Password(secret)));
		assertTrue(sls.validate(uid));
	}

	@Test
	@Ignore
	public void testNotValid() {
		SimpleLoginService sls = new SimpleLoginService(user, secret, realm);
		assertFalse(sls.validate(uid));
	}

	@Test
	public void testLoadRoleInfo() {
		SimpleLoginService sls = new SimpleLoginService(user, secret, realm);
		String[] loadRoleInfo = sls.loadRoleInfo(
				new AbstractLoginService.UserPrincipal(new String(user), new Password(new String(secret))));
		assertArrayEquals(new String[] { "gitrole" }, loadRoleInfo);
	}

	@Test
	public void testNotRoleInfoForUser() {
		SimpleLoginService sls = new SimpleLoginService(user, secret, realm);
		String[] loadRoleInfo = sls.loadRoleInfo(
				new AbstractLoginService.UserPrincipal(new String("someoneelse"), new Password(new String(secret))));
		assertArrayEquals(new String[] {}, loadRoleInfo);
	}

	@Test
	public void testLoadUserInfo() {
		SimpleLoginService sls = new SimpleLoginService(user, secret, realm);
		UserPrincipal loadUserInfo = sls.loadUserInfo(user);
		assertTrue(loadUserInfo.authenticate(new Password(new String(secret))));
		assertEquals(user, loadUserInfo.getName());
	}

}
