package io.jitstatic.auth;

/*-
 * #%L
 * jitstatic
 * %%
 * Copyright (C) 2017 - 2019 H.Hegardt
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

import static io.jitstatic.JitStaticConstants.ANONYMOUS;
import static io.jitstatic.JitStaticConstants.JITSTATIC_GIT_REALM;
import static io.jitstatic.JitStaticConstants.JITSTATIC_KEYADMIN_REALM;
import static io.jitstatic.JitStaticConstants.JITSTATIC_KEYUSER_REALM;
import static io.jitstatic.JitStaticConstants.REFS_HEADS_SECRETS;

import java.security.Principal;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import javax.annotation.Nullable;
import javax.annotation.Priority;
import javax.ws.rs.Priorities;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.SecurityContext;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.glassfish.jersey.server.ContainerRequest;
import org.glassfish.jersey.server.ExtendedUriInfo;

import io.dropwizard.auth.DefaultUnauthorizedHandler;
import io.dropwizard.auth.UnauthorizedHandler;
import io.jitstatic.JitStaticConstants;
import io.jitstatic.Role;
import io.jitstatic.storage.HashService;
import io.jitstatic.storage.Storage;

@Priority(Priorities.AUTHENTICATION)
public abstract class ContextAwareAuthFilter<C, P extends Principal> implements ContainerRequestFilter {

    protected final String prefix;
    protected final UnauthorizedHandler unauthorizedHandler = new DefaultUnauthorizedHandler();

    private final Storage source;

    private final HashService hashService;

    private final BiPredicate<String, String> rootAuthenticator;

    public ContextAwareAuthFilter(final Storage storage, final HashService hashService, final String prefix, BiPredicate<String, String> rootAuthenticator) {
        this.source = Objects.requireNonNull(storage);
        this.hashService = Objects.requireNonNull(hashService);
        this.prefix = Objects.requireNonNull(prefix);
        this.rootAuthenticator = Objects.requireNonNull(rootAuthenticator);
    }

    protected Verdict authenticate(final ContainerRequestContext requestContext, final @Nullable C credentials, final String scheme) {
        final ContainerRequest request = (ContainerRequest) requestContext;
        final String ref = extractRef(request.getUriInfo());
        final Realm realm = getRealm(request, ref);
        if (realm == Realm.UNKNOWN) {
            throw new WebApplicationException(HttpStatus.FORBIDDEN_403);
        }
        if (realm == Realm.NONE) {
            setPrincipal(requestContext, scheme, ANONYMOUS, Objects::isNull);
            return realm.accept;
        }
        if (credentials == null) {
            return testForAnonymousAccess(requestContext, scheme, realm);
        }

        try {
            final String password = getPassword(credentials);
            final String userName = getUserName(credentials);
            if (rootAuthenticator.test(userName, password)) {
                final User root = new User(userName, password, "root", true);
                setPrincipal(requestContext, scheme, root, role -> true);
                return realm.accept;
            }
            return setupInRealm(requestContext, scheme, ref, realm, password, userName);
        } catch (RefNotFoundException e) {
            throw new WebApplicationException(e.getMessage(), Status.BAD_REQUEST);
        }
    }

    Verdict setupInRealm(final ContainerRequestContext requestContext, final String scheme, final String ref, final Realm realm, final String password,
            final String userName) throws RefNotFoundException {
        for (Realm.Domain domain : realm.getDomains()) {
            final UserData userData = findUserInDomain(ref, userName, domain);
            if (userData != null && hashService.hasSamePassword(userData, password)) {
                switch (realm) {
                case GIT:
                case ADMIN_GIT:
                case USER_ADMIN_GIT:
                case NONE_USER_ADMIN_GIT:
                    return setupKeysDomain(requestContext, scheme, realm, domain, userData, userName, password);
                case USERS_GIT:
                case USERS_ADMIN_GIT:
                case USERS_USER_ADMIN_GIT:
                    return setupUsersDomain(requestContext, scheme, realm, domain, userData, userName, password);
                case UNKNOWN:
                    break;
                case NONE:
                default:
                    break;
                }
            }
        }
        return realm.denied;
    }

    Verdict setupUsersDomain(final ContainerRequestContext requestContext, final String scheme, final Realm realm, Realm.Domain domain, final UserData userData,
            String userName, String password) {
        final User user = new User(userName, password, domain.domainName, false);
        switch (domain) {
        case GIT:
            return setGitPrincipal(requestContext, scheme, realm, userData, user);
        case KEYADMIN:
            return setKeyAdminPrincipal(requestContext, scheme, realm, user);
        case KEYUSER:
            return setKeyUserPrincipal(requestContext, scheme, realm, user);
        case NONE:
        default:
            throw new IllegalStateException("Unreachable");
        }
    }

    Verdict setKeyUserPrincipal(final ContainerRequestContext requestContext, final String scheme, final Realm realm, User user) {
        // TODO Keyusers can only change their password not their roles
        final String userName = user.getName();
        setPrincipal(requestContext, scheme, user, role -> Realm.Domain.KEYUSER.createUserKey(userName).equals(role));
        return realm.accept;
    }

    Verdict setKeyAdminPrincipal(final ContainerRequestContext requestContext, final String scheme, final Realm realm, User user) {
        // Keyadmins kan change keyuser's data
        final String userName = user.getName();
        setPrincipal(requestContext, scheme, user, role -> Realm.Domain.KEYUSER.getDomainName().equals(role)
                || role.equals(JitStaticConstants.ROLERROLES)
                || Realm.Domain.KEYADMIN.createUserKey(userName).equals(role));
        return realm.accept;
    }

    Verdict setGitPrincipal(final ContainerRequestContext requestContext, final String scheme, final Realm realm, final UserData userData, User user) {
        final Set<Role> roles = userData.getRoles();
        // Git admin with the appropriate roles can change their own password, keyadmins
        // and keyusers
        final String userName = user.getName();
        setPrincipal(requestContext, scheme, user, role -> Realm.Domain.KEYADMIN.getDomainName().equals(role)
                || Realm.Domain.KEYUSER.getDomainName().equals(role)
                || roles.contains(new Role(role))
                || Realm.Domain.GIT.createUserKey(userName).equals(role)
                || (roles.contains(new Role(JitStaticConstants.GIT_CREATE)) && role.equals(JitStaticConstants.ROLERROLES)));
        return realm.accept;
    }

    private Verdict setupKeysDomain(final ContainerRequestContext requestContext, final String scheme, final Realm realm, Realm.Domain domain,
            final UserData userData, String userName, String password) {
        switch (domain) {
        case GIT:
        case KEYADMIN:
            // Git users and KeyAdmins can read and write to all keys
            setPrincipal(requestContext, scheme, new User(userName, password, domain.domainName, true), role -> true);
            return realm.accept;
        case KEYUSER:
            // Keyusers can only read or write to the roles they are part of.
            Set<Role> roles = userData.getRoles();
            setPrincipal(requestContext, scheme, new User(userName, password, domain.domainName, false), role -> roles.contains(new Role(role)));
            return realm.accept;
        case NONE:
        default:
            throw new IllegalStateException(String.format("Domain %s for user %s is illegal", domain.domainName, userName));
        }
    }

    private Verdict testForAnonymousAccess(final ContainerRequestContext requestContext, final String scheme, final Realm realm) {
        if (realm == Realm.NONE_USER_ADMIN_GIT) {
            setPrincipal(requestContext, scheme, ANONYMOUS, Objects::isNull);
            return realm.accept;
        }
        return realm.denied;
    }

    private UserData findUserInDomain(final String ref, final String userName, Realm.Domain domain) throws RefNotFoundException {
        if (domain == Realm.Domain.GIT) {
            try {
                return source.getUser(userName, REFS_HEADS_SECRETS, domain.getDomainName());
            } catch (RefNotFoundException e) {
                // It is ok for refs/heads/secrets branch to not exist
                return null;
            }
        } else {
            return source.getUser(userName, ref, domain.getDomainName());
        }
    }

    protected abstract String getUserName(C credentials);

    protected abstract String getPassword(C credentials);

    private Realm getRealm(ContainerRequest request, String ref) {
        if (ref != null && ref.equals(REFS_HEADS_SECRETS)) {
            return Realm.GIT;
        }
        final List<String> matchedURIs = request.getUriInfo().getMatchedURIs(true);
        switch (matchedURIs.get(matchedURIs.size() - 1)) {
        case "storage":
            return storage(request.getMethod());
        case "bulk":
            return Realm.NONE_USER_ADMIN_GIT;
        case "metakey":
            return Realm.USER_ADMIN_GIT;
        case "info":
            return Realm.NONE;
        case "users":
            return matchBaseUrl(matchedURIs);
        default:
            return Realm.UNKNOWN;
        }
    }

    Realm matchBaseUrl(final List<String> matchedURIs) {
        final String base = matchedURIs.get(0);
        if (base.startsWith("users/keyuser/")) {
            return Realm.USERS_USER_ADMIN_GIT;
        }
        if (base.startsWith("users/keyadmin/")) {
            return Realm.USERS_ADMIN_GIT;
        }
        if (base.startsWith("users/git/")) {
            return Realm.USERS_GIT;
        }
        return Realm.UNKNOWN;
    }

    private Realm storage(String method) {
        switch (method) {
        case "GET":
            return Realm.NONE_USER_ADMIN_GIT;
        case "POST":
        case "PUT":
        case "DELETE":
            return Realm.USER_ADMIN_GIT;
        case "OPTIONS":
            return Realm.NONE;
        default:
            return Realm.UNKNOWN;
        }

    }

    private String extractRef(ExtendedUriInfo uriInfo) {
        final List<String> refs = uriInfo.getQueryParameters(true).get("ref");
        if (refs != null) {
            int element = 0;
            if (refs.size() > 1) {
                final String query = uriInfo.getRequestUri().getQuery();
                int idx = query.length();
                for (int i = 0; i < refs.size(); i++) {
                    int pos = Math.min(idx, query.indexOf(refs.get(i)));
                    if (pos < idx) {
                        element = i;
                        idx = pos;
                    }
                }
            }
            return refs.get(element);
        }
        return null;
    }

    protected enum Realm {
        USERS_USER_ADMIN_GIT(List.of(Domain.KEYUSER, Domain.KEYADMIN, Domain.GIT)),
        USERS_ADMIN_GIT(List.of(Domain.KEYADMIN, Domain.GIT)),
        USERS_GIT(List.of(Domain.GIT)),
        NONE_USER_ADMIN_GIT(List.of(Domain.KEYUSER, Domain.KEYADMIN, Domain.GIT)),
        USER_ADMIN_GIT(List.of(Domain.KEYUSER, Domain.KEYADMIN, Domain.GIT)),
        ADMIN_GIT(List.of(Domain.KEYADMIN, Domain.GIT)),
        GIT(List.of(Domain.GIT)),
        NONE(List.of(Domain.NONE)),
        UNKNOWN(List.of());

        private final String realmName;
        private final List<Domain> domains;
        private Verdict denied;
        private Verdict accept;

        private Realm(List<Domain> domains) {
            this.realmName = domains.stream().map(Domain::getDomainName).collect(Collectors.joining("|"));
            this.domains = domains;
            this.denied = new Verdict(false, this);
            this.accept = new Verdict(true, this);
        }

        public String getRealmName() { return realmName; }

        public List<Domain> getDomains() { return domains; }

        public Verdict accept() {
            return accept;
        }

        public Verdict denied() {
            return denied;
        }

        protected enum Domain {
            NONE(""),
            GIT(JITSTATIC_GIT_REALM),
            KEYADMIN(JITSTATIC_KEYADMIN_REALM),
            KEYUSER(JITSTATIC_KEYUSER_REALM);

            private final String domainName;

            Domain(String domainName) {
                this.domainName = domainName;
            }

            public String getDomainName() { return domainName; }

            public String createUserKey(String userName) {
                return domainName + "/" + userName;
            }
        }
    }

    protected static final class Verdict {
        final boolean isAllowed;
        final Realm realm;

        public Verdict(final boolean isAllowed, final Realm realm) {
            this.isAllowed = isAllowed;
            this.realm = realm;
        }
    }

    private void setPrincipal(ContainerRequestContext requestContext, String scheme, Principal principal, Predicate<String> authorizer) {
        final SecurityContext securityContext = requestContext.getSecurityContext();
        final boolean secure = securityContext != null && securityContext.isSecure();

        requestContext.setSecurityContext(new SecurityContext() {
            @Override
            public Principal getUserPrincipal() { return principal; }

            @Override
            public boolean isUserInRole(final String role) {
                return authorizer.test(role);
            }

            @Override
            public boolean isSecure() { return secure; }

            @Override
            public String getAuthenticationScheme() { return scheme; }
        });
    }

}
