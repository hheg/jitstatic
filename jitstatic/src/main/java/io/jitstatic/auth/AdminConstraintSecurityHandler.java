package io.jitstatic.auth;

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

import java.util.Objects;

import org.eclipse.jetty.security.AbstractLoginService;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.util.security.Constraint;
import org.eclipse.jetty.util.security.Password;

public class AdminConstraintSecurityHandler extends ConstraintSecurityHandler {

    private static final String ADMIN_ROLE = "admin";

    public AdminConstraintSecurityHandler(final String userName, final String password, final boolean protectHealthChecks, final boolean protectMetrics,
            boolean protectTasks) {
        Objects.requireNonNull(password, "admin password cannot be null to protect /admin endpoint");
        if (protectHealthChecks) {
            addConstraintMapping(getMappingFor("/healthcheck"));
        }
        if (protectMetrics) {
            addConstraintMapping(getMappingFor("/metrics"));
        }
        if (protectTasks) {
            addConstraintMapping(getMappingFor("/tasks"));
            addConstraintMapping(getMappingFor("/tasks/*"));
        }
        setLoginService(new AdminLoginService(userName, password));
    }

    private ConstraintMapping getMappingFor(final String url) {
        final Constraint constraint = new Constraint(Constraint.__BASIC_AUTH, ADMIN_ROLE);
        constraint.setAuthenticate(true);
        constraint.setRoles(new String[] { ADMIN_ROLE });
        final ConstraintMapping cm = new ConstraintMapping();
        cm.setMethod("*");
        cm.setConstraint(constraint);
        cm.setPathSpec(url);
        setAuthenticator(new BasicAuthenticator());
        return cm;
    }

    public static class AdminLoginService extends AbstractLoginService {

        private final UserPrincipal adminPrincipal;
        private final String adminUserName;

        public AdminLoginService(final String userName, final String password) {
            this.adminUserName = Objects.requireNonNull(userName);
            this.adminPrincipal = new UserPrincipal(userName, new Password(Objects.requireNonNull(password)));
        }

        @Override
        protected String[] loadRoleInfo(final UserPrincipal principal) {
            if (adminUserName.equals(principal.getName())) {
                return new String[] { ADMIN_ROLE };
            }
            return new String[0];
        }

        @Override
        protected UserPrincipal loadUserInfo(final String userName) {
            return adminUserName.equals(userName) ? adminPrincipal : null;
        }
    }
}
