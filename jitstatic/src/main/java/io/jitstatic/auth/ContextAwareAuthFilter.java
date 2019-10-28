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

import static io.jitstatic.JitStaticConstants.GIT_REALM;
import static io.jitstatic.JitStaticConstants.JITSTATIC_KEYADMIN_REALM;
import static io.jitstatic.JitStaticConstants.JITSTATIC_KEYUSER_REALM;
import static io.jitstatic.JitStaticConstants.REFS_HEADS_SECRETS;

import java.security.Principal;
import java.util.HashSet;
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
            setPrincipal(requestContext, scheme, JitStaticConstants.ANONYMOUS, Objects::isNull);
            return new Verdict(true, realm);
        }
        if (credentials == null) {
            return testForAnonymousAccess(requestContext, scheme, realm);
        }

        try {
            final String password = getPassword(credentials);
            final String userName = getUserName(credentials);
            if (rootAuthenticator.test(userName, password)) {
                final User root = new User(userName, password, "root");
                root.setAdmin(true);
                setPrincipal(requestContext, scheme, root, role -> true);
                return new Verdict(true, realm);
            }
            for (Realm.Domain domain : realm.getDomains()) {
                final UserData userData = findUserInDomain(ref, userName, domain);
                if (userData != null) {
                    if (hashService.hasSamePassword(userData, password)) {
                        User user = new User(userName, password, domain.getDomainName());
                        switch (realm) {
                        case GIT:
                        case ADMIN_GIT:
                        case USER_ADMIN_GIT:
                        case NONE_USER_ADMIN_GIT: {
                            switch (domain) {
                            case GIT:
                            case KEYADMIN:
                                user.setAdmin(true);
                                // Git users and KeyAdmins can read and write to all keys
                                setPrincipal(requestContext, scheme, user, role -> true);
                                return new Verdict(true, realm);
                            case KEYUSER:
                                // Keyusers can only read or write to the roles they are part of.
                                Set<Role> roles = userData.getRoles();
                                setPrincipal(requestContext, scheme, user, role -> roles.contains(new Role(role)));
                                return new Verdict(true, realm);
                            case NONE:
                            default:
                                throw new IllegalStateException(String.format("Domain %s for user %s is illegal", domain.domainName, user));
                            }
                        }
                        case USERS_GIT:
                        case USERS_ADMIN_GIT:
                        case USERS_USER_ADMIN_GIT:
                            switch (domain) {
                            case GIT: {
                                // Git admin with the appropriate roles can change their own password, keyadmins and keyusers
                                final Set<Role> roles = new HashSet<>(userData.getRoles());
                                roles.add(new Role(createUserKey(user, Realm.Domain.GIT)));
                                roles.add(new Role(Realm.Domain.KEYADMIN.getDomainName()));
                                roles.add(new Role(Realm.Domain.KEYUSER.getDomainName()));
                                setPrincipal(requestContext, scheme, user, role -> roles.contains(new Role(role)));
                                return new Verdict(true, realm);
                            }
                            case KEYADMIN: {
                                // Keyadmins kan change keyuser's data
                                final Set<Role> roles = Set
                                        .of(new Role(createUserKey(user, Realm.Domain.KEYADMIN)), new Role(Realm.Domain.KEYUSER.getDomainName()));
                                setPrincipal(requestContext, scheme, user, role -> roles.contains(new Role(role)));
                                return new Verdict(true, realm);
                            }
                            case KEYUSER: {
                                // TODO Keyusers can only change their password not their roles
                                final Set<Role> roles = Set.of(new Role(createUserKey(user, Realm.Domain.KEYUSER)));
                                setPrincipal(requestContext, scheme, user, role -> roles.contains(new Role(role)));
                                return new Verdict(true, realm);
                            }
                            case NONE:
                                break;
                            default:
                                break;
                            }
                            break;
                        case UNKNOWN:
                            break;
                        default:
                            break;
                        }
                    }
                }
            }
        } catch (RefNotFoundException e) {
            throw new WebApplicationException(e.getMessage(), Status.BAD_REQUEST);
        }
        return new Verdict(false, realm);
    }

    private Verdict testForAnonymousAccess(final ContainerRequestContext requestContext, final String scheme, final Realm realm) {
        if (realm == Realm.NONE_USER_ADMIN_GIT) {
            setPrincipal(requestContext, scheme, JitStaticConstants.ANONYMOUS, Objects::isNull);
            return new Verdict(true, realm);
        }
        return new Verdict(false, realm);
    }

    private String createUserKey(User user, Realm.Domain domain) {
        return String.format("%s/%s", domain.getDomainName(), user.getName());
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
        final String method = request.getMethod();
        switch (matchedURIs.get(matchedURIs.size() - 1).toLowerCase()) {
        case "storage":
            return storage(method);
        case "bulk":
            return Realm.NONE_USER_ADMIN_GIT;
        case "metakey":
            return Realm.USER_ADMIN_GIT;
        case "info":
            return Realm.NONE;
        case "users":
            String base = matchedURIs.get(0);
            if (base.startsWith("users/keyuser/")) {
                return Realm.USERS_USER_ADMIN_GIT;
            }
            if (base.startsWith("users/keyadmin/")) {
                return Realm.USERS_ADMIN_GIT;
            }
            if (base.startsWith("users/git/")) {
                return Realm.USERS_GIT;
            }
            break;
        default:
            return Realm.UNKNOWN;
        }
        return Realm.UNKNOWN;
    }

    private Realm storage(String method) {
        switch (method.toUpperCase()) {
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

        private Realm(List<Domain> domains) {
            this.realmName = domains.stream().map(Domain::getDomainName).collect(Collectors.joining("|"));
            this.domains = domains;
        }

        public String getRealmName() { return realmName; }

        public List<Domain> getDomains() { return domains; }

        protected enum Domain {
            NONE(""),
            GIT(GIT_REALM),
            KEYADMIN(JITSTATIC_KEYADMIN_REALM),
            KEYUSER(JITSTATIC_KEYUSER_REALM);

            private final String domainName;

            Domain(String domainName) {
                this.domainName = domainName;
            }

            public String getDomainName() { return domainName; }
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
