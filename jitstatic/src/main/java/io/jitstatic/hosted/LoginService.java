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

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;

import org.eclipse.jetty.security.AbstractLoginService;
import org.eclipse.jetty.util.security.Credential;
import org.eclipse.jetty.util.security.Password;

import io.jitstatic.JitStaticConstants;
import io.jitstatic.Role;
import io.jitstatic.auth.UserData;
import io.jitstatic.storage.HashService;
import io.jitstatic.storage.Storage;

public class LoginService extends AbstractLoginService {

    private static final String[] EMPTY_ROLES = new String[0];
    private static final String[] ROLES = JitStaticConstants.GIT_ROLES.toArray(new String[JitStaticConstants.GIT_ROLES.size()]);
    private final UserPrincipal root;
    private final String defaultRef;
    private Storage storage;
    private final HashService hashService;

    public LoginService(final String userName, final String secret, final String realm, final String defaultRef, HashService hashService) {
        this._name = Objects.requireNonNull(realm);
        this.defaultRef = Objects.requireNonNull(defaultRef);
        this.root = new UserPrincipal(Objects.requireNonNull(userName), new Password(Objects.requireNonNull(secret)));
        this.hashService = Objects.requireNonNull(hashService);
    }

    @Override
    protected String[] loadRoleInfo(final UserPrincipal user) {
        if (root.getName().equals(user.getName())) {
            return Arrays.copyOf(ROLES, ROLES.length);
        }
        if (user instanceof RoleBearingUserPrincipal) {
            RoleBearingUserPrincipal rbup = (RoleBearingUserPrincipal) user;
            return rbup.getRoles();
        }
        return EMPTY_ROLES;
    }

    @Override
    protected UserPrincipal loadUserInfo(final String username) {
        if (root.getName().equals(username)) {
            return root;
        }
        try {
            final UserData userData = storage.getUser(username, defaultRef, _name).join();
            if (userData == null) {
                return null;
            }
            return new RoleBearingUserPrincipal(username, new HashingCredential(hashService, userData), userData.getRoles());
        } catch (final Exception e) {
            return null;
        }
    }

    public void setUserStorage(final Storage storage) {
        this.storage = storage;
    }

    private static class RoleBearingUserPrincipal extends UserPrincipal {

        private static final long serialVersionUID = 1L;
        private final String[] roles;

        public RoleBearingUserPrincipal(final String name, final Credential credential, final Set<Role> roles) {
            super(name, credential);
            this.roles = roles.stream().map(Role::getRole).toArray(String[]::new);
        }

        public String[] getRoles() {
            return Arrays.copyOf(roles, roles.length);
        }
    }

    private static class HashingCredential extends Credential {

        private static final long serialVersionUID = 1L;
        private final HashService service;
        private final UserData data;

        public HashingCredential(final HashService service, final UserData data) {
            this.service = service;
            this.data = data;
        }

        @Override
        public boolean check(Object credentials) {
            if (credentials instanceof char[]) {
                credentials = new String((char[]) credentials);
            }
            if (credentials instanceof Password || credentials instanceof String) {
                return service.hasSamePassword(data, credentials.toString());
            } else if (credentials instanceof HashingCredential) {
                return data.equals(((HashingCredential) credentials).data);
            }
            return false;
        }

    }
}
