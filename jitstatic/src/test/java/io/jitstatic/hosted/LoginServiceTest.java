package io.jitstatic.hosted;

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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.security.Principal;
import java.util.Arrays;
import java.util.List;

import javax.security.auth.Subject;
import javax.servlet.ServletRequest;

import org.eclipse.jetty.security.AbstractLoginService;
import org.eclipse.jetty.security.AbstractLoginService.UserPrincipal;
import org.eclipse.jetty.server.UserIdentity;
import org.eclipse.jetty.util.security.Password;
import org.junit.jupiter.api.Test;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.jitstatic.hosted.LoginService;

@SuppressFBWarnings(value = { "NP_NULL_PARAM_DEREF_NONVIRTUAL", "DM_STRING_CTOR" }, justification = "Testing explicitly for this")
public class LoginServiceTest {

    private static final String DEFAULT_MASTER_REF = "refs/heads/master";
    private static final String user = "user";
    private static final String secret = "secret";
    private static final String realm = "realm";

    private ServletRequest req = mock(ServletRequest.class);
    private UserIdentity uid = mock(UserIdentity.class);

    @Test
    public void testLoginServiceNullUserName() {
        assertThrows(NullPointerException.class, () -> new LoginService(null, secret, realm, DEFAULT_MASTER_REF));
    }

    @Test
    public void testLoginServiceNullSecret() {
        assertThrows(NullPointerException.class, () -> new LoginService(user, null, realm, DEFAULT_MASTER_REF));
    }

    @Test
    public void testLoginServiceNullRealm() {
        assertThrows(NullPointerException.class, () -> new LoginService(user, secret, null, DEFAULT_MASTER_REF));
    }

    @Test
    public void testGetName() {
        LoginService sls = new LoginService(user, secret, realm, DEFAULT_MASTER_REF);
        assertEquals(sls.getName(), new String(realm));
    }

    @Test
    public void testLogin() {
        LoginService sls = new LoginService(user, secret, realm, DEFAULT_MASTER_REF);
        UserIdentity login = sls.login(user, secret, req);
        assertNotNull(login);
        Principal userPrincipal = login.getUserPrincipal();
        assertNotNull(userPrincipal);
        Subject subject = login.getSubject();
        assertNotNull(subject);
    }

    @Test
    public void testNotLogin() {
        LoginService sls = new LoginService(user, secret, realm, DEFAULT_MASTER_REF);
        UserIdentity login = sls.login(user, "", req);
        assertNull(login);
    }

    @Test
    public void testValidate() {
        LoginService sls = new LoginService(user, secret, realm, DEFAULT_MASTER_REF);
        when(uid.getUserPrincipal()).thenReturn(new AbstractLoginService.UserPrincipal(user, new Password(secret)));
        assertTrue(sls.validate(uid));
    }

    @Test
    public void testLoadRoleInfo() {
        LoginService sls = new LoginService(user, secret, realm, DEFAULT_MASTER_REF);
        String[] loadRoleInfo = sls.loadRoleInfo(new AbstractLoginService.UserPrincipal(new String(user), new Password(new String(secret))));
        assertTrue(loadRoleInfo.length == 4);
        Arrays.asList(loadRoleInfo).containsAll(List.of("push", "pull", "forcepush"));
    }

    @Test
    public void testNotRoleInfoForUser() {
        LoginService sls = new LoginService(user, secret, realm, DEFAULT_MASTER_REF);
        String[] loadRoleInfo = sls.loadRoleInfo(new AbstractLoginService.UserPrincipal(new String("someoneelse"), new Password(new String(secret))));
        assertArrayEquals(new String[] {}, loadRoleInfo);
    }

    @Test
    public void testLoadUserInfo() {
        LoginService sls = new LoginService(user, secret, realm, DEFAULT_MASTER_REF);
        UserPrincipal loadUserInfo = sls.loadUserInfo(user);
        assertTrue(loadUserInfo.authenticate(new Password(new String(secret))));
        assertEquals(user, loadUserInfo.getName());
    }

}
