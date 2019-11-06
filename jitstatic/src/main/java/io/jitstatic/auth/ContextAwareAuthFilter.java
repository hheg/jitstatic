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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
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
import io.jitstatic.auth.ContextAwareAuthFilter.Realm.Domain;
import io.jitstatic.storage.Storage;
import io.jitstatic.utils.ShouldNeverHappenException;
import io.jitstatic.utils.WrappingAPIException;

@Priority(Priorities.AUTHENTICATION)
public abstract class ContextAwareAuthFilter<C> implements ContainerRequestFilter {

    protected final String prefix;
    protected final UnauthorizedHandler unauthorizedHandler = new DefaultUnauthorizedHandler();

    private final Storage storage;

    public ContextAwareAuthFilter(final Storage storage, final String prefix) {
        this.storage = Objects.requireNonNull(storage);
        this.prefix = Objects.requireNonNull(prefix);
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
            final String userName = getUserName(credentials);
            if (isRoot(credentials)) {
                final User root = new User(userName, null, "root", true);
                setPrincipal(requestContext, scheme, root, role -> true);
                return realm.accept;
            }
            return setupInRealm(requestContext, scheme, ref, realm, userName, credentials);
        } catch (RefNotFoundException e) {
            throw new WebApplicationException(e.getMessage(), Status.BAD_REQUEST);
        }
    }

    private Verdict setupInRealm(final ContainerRequestContext requestContext, final String scheme, final String ref, final Realm realm, final String userName,
            C credentials) throws RefNotFoundException {
        final CompletableFuture<Data> resultReceiver = new CompletableFuture<>();
        final CompletableFuture<Verdict> resultEmitter = resultReceiver.thenApply(result -> {
            realm.invokeInRealm(requestContext, scheme, result.userData, userName, result.domain);
            return result.verdict;
        }).completeOnTimeout(realm.denied, 1, TimeUnit.SECONDS);
        realm.getDomains().stream().forEach(d -> invokeInDomain(d, ref, realm, userName, credentials, resultReceiver));
        try {
            return resultEmitter.join();
        } catch (CompletionException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof WrappingAPIException) {
                Throwable rootCause = cause.getCause();
                if (rootCause instanceof RefNotFoundException) {
                    throw (RefNotFoundException) rootCause;
                }
            } else if (cause instanceof RefNotFoundException) {
                throw (RefNotFoundException) cause;
            }
            throw e;
        }
    }

    private static class Data {
        final UserData userData;
        final Realm.Domain domain;
        final Verdict verdict;

        Data(final UserData userData, final Realm.Domain domain, final Verdict verdict) {
            this.userData = userData;
            this.domain = domain;
            this.verdict = verdict;
        }
    }

    private void invokeInDomain(final Domain domain, final String ref, final Realm realm, final String userName, final C credentials,
            final CompletableFuture<Data> cf) {
        try {
            domain.findUser(storage, ref, userName).thenApplyAsync(userData -> {
                if (validate(userData, credentials)) {
                    return new Data(userData, domain, realm.accept);
                }
                return new Data(userData, domain, realm.denied);
            }).whenComplete((result, throwable) -> {
                // Race to complete
                if (throwable != null) {
                    cf.completeExceptionally(throwable);
                } else if (result.verdict.isAllowed) {
                    cf.complete(result);
                }
            });
        } catch (RefNotFoundException e) {
            cf.completeExceptionally(e);
        }
    }

    private Verdict testForAnonymousAccess(final ContainerRequestContext requestContext, final String scheme, final Realm realm) {
        if (realm == Realm.NONE_USER_ADMIN_GIT) {
            setPrincipal(requestContext, scheme, ANONYMOUS, Objects::isNull);
            return realm.accept;
        }
        return realm.denied;
    }

    protected abstract String getUserName(C credentials);

    protected abstract boolean validate(@Nullable UserData userData, C credentials);

    protected abstract boolean isRoot(C credentials);

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

    private Realm matchBaseUrl(final List<String> matchedURIs) {
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

    private String extractRef(final ExtendedUriInfo uriInfo) {
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
        USERS_USER_ADMIN_GIT(List.of(Domain.KEYUSER, Domain.KEYADMIN, Domain.GIT)) {
            @Override
            void invokeInRealm(ContainerRequestContext requestContext, String scheme, UserData userData, String userName, Domain domain) {
                domain.setUsersPrincipal(requestContext, scheme, userData, userName);
            }
        },
        USERS_ADMIN_GIT(List.of(Domain.KEYADMIN, Domain.GIT)) {
            @Override
            void invokeInRealm(ContainerRequestContext requestContext, String scheme, UserData userData, String userName, Domain domain) {
                domain.setUsersPrincipal(requestContext, scheme, userData, userName);
            }
        },
        USERS_GIT(List.of(Domain.GIT)) {
            @Override
            void invokeInRealm(ContainerRequestContext requestContext, String scheme, UserData userData, String userName, Domain domain) {
                domain.setUsersPrincipal(requestContext, scheme, userData, userName);
            }
        },
        NONE_USER_ADMIN_GIT(List.of(Domain.KEYUSER, Domain.KEYADMIN, Domain.GIT)) {
            @Override
            void invokeInRealm(ContainerRequestContext requestContext, String scheme, UserData userData, String userName, Domain domain) {
                domain.setKeysPrincipal(requestContext, scheme, userData, userName);
            }
        },
        USER_ADMIN_GIT(List.of(Domain.KEYUSER, Domain.KEYADMIN, Domain.GIT)) {
            @Override
            void invokeInRealm(ContainerRequestContext requestContext, String scheme, UserData userData, String userName, Domain domain) {
                domain.setKeysPrincipal(requestContext, scheme, userData, userName);
            }
        },
        ADMIN_GIT(List.of(Domain.KEYADMIN, Domain.GIT)) {
            @Override
            void invokeInRealm(ContainerRequestContext requestContext, String scheme, UserData userData, String userName, Domain domain) {
                domain.setKeysPrincipal(requestContext, scheme, userData, userName);
            }
        },
        GIT(List.of(Domain.GIT)) {
            @Override
            void invokeInRealm(ContainerRequestContext requestContext, String scheme, UserData userData, String userName, Domain domain) {
                domain.setKeysPrincipal(requestContext, scheme, userData, userName);
            }
        },
        NONE(List.of(Domain.NONE)) {
            @Override
            void invokeInRealm(ContainerRequestContext requestContext, String scheme, UserData userData, String userName, Domain domain) {
                throw new IllegalStateException(String.format("Realm %s for user %s is illegal", getRealmName(), userName));
            }
        },
        UNKNOWN(List.of()) {
            @Override
            void invokeInRealm(ContainerRequestContext requestContext, String scheme, UserData userData, String userName, Domain domain) {
                throw new IllegalStateException(String.format("Realm %s for user %s is illegal", getRealmName(), userName));
            }
        };

        private final String realmName;
        private final List<Domain> domains;
        private final Verdict denied;
        private final Verdict accept;

        private Realm(final List<Domain> domains) {
            this.realmName = domains.stream().map(Domain::getDomainName).collect(Collectors.joining("|"));
            this.domains = domains;
            this.denied = new Verdict(false, this);
            this.accept = new Verdict(true, this);
        }

        abstract void invokeInRealm(final ContainerRequestContext requestContext, final String scheme, final UserData userData, final String userName,
                Domain domain);

        public String getRealmName() { return realmName; }

        public List<Domain> getDomains() { return domains; }

        public Verdict accept() {
            return accept;
        }

        public Verdict denied() {
            return denied;
        }

        protected enum Domain {
            NONE("none") {
                @Override
                void setUsersPrincipal(final ContainerRequestContext requestContext, final String scheme, final UserData userData, final String userName) {
                    throw new IllegalStateException("Unreachable");
                }

                @Override
                void setKeysPrincipal(final ContainerRequestContext requestContext, final String scheme, final UserData userData, final String userName) {
                    throw new IllegalStateException(String.format("Domain %s for user %s is illegal", getDomainName(), userName));
                }

                @Override
                protected CompletableFuture<UserData> findUser(Storage storage, String ref, String userName) throws RefNotFoundException {
                    throw new IllegalStateException(String.format("Domain %s for user %s is illegal", getDomainName(), userName));
                }
            },
            GIT(JITSTATIC_GIT_REALM) {
                @Override
                void setUsersPrincipal(final ContainerRequestContext requestContext, final String scheme, final UserData userData, final String userName) {
                    final Set<Role> roles = userData.getRoles();
                    // Git admin with the appropriate roles can change their own password, keyadmins
                    // and keyusers
                    setPrincipal(requestContext, scheme, new User(userName, null, getDomainName(), true), role -> Realm.Domain.KEYADMIN.getDomainName()
                            .equals(role)
                            || Realm.Domain.KEYUSER.getDomainName().equals(role)
                            || roles.contains(new Role(role))
                            || Realm.Domain.GIT.createUserKey(userName).equals(role)
                            || (roles.contains(new Role(JitStaticConstants.GIT_CREATE)) && role.equals(JitStaticConstants.ROLERROLES)));
                }

                @Override
                void setKeysPrincipal(final ContainerRequestContext requestContext, final String scheme, final UserData userData, final String userName) {
                    // Git users and KeyAdmins can read and write to all keys
                    setPrincipal(requestContext, scheme, new User(userName, null, getDomainName(), true), role -> true);
                }

                @Override
                protected CompletableFuture<UserData> findUser(final Storage storage, final String ref, final String userName) {
                    try {
                        return super.findUser(storage, JitStaticConstants.REFS_HEADS_SECRETS, userName).handle((ud, t) -> {
                            if (t != null) {
                                if (t instanceof RefNotFoundException) {
                                    return null;
                                }
                                throw new ShouldNeverHappenException(String.format("Failed to load user %s in ref %s", userName, ref), t);
                            }
                            return ud;
                        });
                    } catch (RefNotFoundException e) {
                        return CompletableFuture.completedFuture(null);
                    }
                }
            },
            KEYADMIN(JITSTATIC_KEYADMIN_REALM) {
                @Override
                void setUsersPrincipal(final ContainerRequestContext requestContext, final String scheme, final UserData userData, final String userName) {
                    // Keyadmins can change keyuser's data
                    setPrincipal(requestContext, scheme, new User(userName, null, getDomainName(), true), role -> Realm.Domain.KEYUSER.getDomainName()
                            .equals(role)
                            || role.equals(JitStaticConstants.ROLERROLES)
                            || Realm.Domain.KEYADMIN.createUserKey(userName).equals(role));
                }

                @Override
                void setKeysPrincipal(final ContainerRequestContext requestContext, final String scheme, final UserData userData, final String userName) {
                    GIT.setKeysPrincipal(requestContext, scheme, userData, userName);
                }
            },
            KEYUSER(JITSTATIC_KEYUSER_REALM) {
                @Override
                void setUsersPrincipal(final ContainerRequestContext requestContext, final String scheme, final UserData userData, final String userName) {
                    setPrincipal(requestContext, scheme, new User(userName, null, getDomainName(), false), role -> Realm.Domain.KEYUSER
                            .createUserKey(userName).equals(role));
                }

                @Override
                void setKeysPrincipal(final ContainerRequestContext requestContext, final String scheme, final UserData userData, final String userName) {
                    // Keyusers can only read or write to the roles they are part of.
                    Set<Role> roles = userData.getRoles();
                    setPrincipal(requestContext, scheme, new User(userName, null, getDomainName(), false), role -> roles.contains(new Role(role)));
                }
            };

            private final String domainName;

            Domain(String domainName) {
                this.domainName = domainName;
            }

            protected CompletableFuture<UserData> findUser(final Storage storage, final String ref, final String userName) throws RefNotFoundException {
                return storage.getUser(userName, ref, getDomainName());
            }

            abstract void setUsersPrincipal(final ContainerRequestContext requestContext, final String scheme, final UserData userData, final String userName);

            abstract void setKeysPrincipal(final ContainerRequestContext requestContext, final String scheme, final UserData userData, final String userName);

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

    private static void setPrincipal(ContainerRequestContext requestContext, String scheme, Principal principal, Predicate<String> authorizer) {
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
