package io.jitstatic.api;

import static io.jitstatic.JitStaticConstants.CREATE;

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

import static io.jitstatic.JitStaticConstants.GIT_REALM;
import static io.jitstatic.JitStaticConstants.JITSTATIC_KEYADMIN_REALM;
import static io.jitstatic.JitStaticConstants.JITSTATIC_KEYUSER_REALM;
import static io.jitstatic.JitStaticConstants.REFS_HEADS_SECRETS;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static javax.ws.rs.core.MediaType.APPLICATION_XML;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;

import javax.inject.Inject;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import javax.validation.groups.Default;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.EntityTag;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.Response.Status;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.UserIdentity;
import org.eclipse.jetty.util.security.Password;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.annotation.ExceptionMetered;
import com.codahale.metrics.annotation.Metered;
import com.codahale.metrics.annotation.Timed;

import io.dropwizard.auth.Auth;
import io.dropwizard.validation.Validated;
import io.jitstatic.api.constraints.Adding;
import io.jitstatic.auth.KeyAdminAuthenticator;
import io.jitstatic.auth.User;
import io.jitstatic.hosted.LoginService;
import io.jitstatic.storage.HashService;
import io.jitstatic.storage.Storage;
import io.jitstatic.utils.Pair;

@Path("users")
public class UsersResource {

    final String defaultRef;
    private static final Logger LOG = LoggerFactory.getLogger(UsersResource.class);
    private static final String UTF_8 = "utf-8";
    private final Storage storage;
    private final KeyAdminAuthenticator adminKeyAuthenticator;
    private final APIHelper helper;
    private final LoginService gitAuthenticator;
    private final HashService hashService;
    @Inject
    private ExecutorService executor;

    public UsersResource(final Storage storage, final KeyAdminAuthenticator adminKeyAuthenticator,
            final LoginService gitAuthenticator, final String defaultBranch, final HashService hashService) {
        this.storage = Objects.requireNonNull(storage);
        this.adminKeyAuthenticator = Objects.requireNonNull(adminKeyAuthenticator);
        this.gitAuthenticator = Objects.requireNonNull(gitAuthenticator);
        this.helper = new APIHelper(LOG);
        this.defaultRef = Objects.requireNonNull(defaultBranch);
        this.hashService = Objects.requireNonNull(hashService);
    }

    @GET
    @Timed(name = "get_keyadmin_time")
    @Metered(name = "get_keyadmin_counter")
    @ExceptionMetered(name = "get_keyadmin_exception")
    @Path(JITSTATIC_KEYADMIN_REALM + "/{key : .+}")
    @Consumes({ APPLICATION_JSON, APPLICATION_XML })
    @Produces({ APPLICATION_JSON, APPLICATION_XML })
    public Response get(final @PathParam("key") String key, final @QueryParam("ref") String ref,
            final @Auth Optional<User> remoteUserHolder, final @Context HttpHeaders headers) {
        final User user = remoteUserHolder.orElseThrow(() -> APIHelper.createAuthenticationChallenge(GIT_REALM));
        APIHelper.checkRef(ref);
        authorize(user, null);
        return getUser(key, APIHelper.setToDefaultRefIfNull(ref, defaultRef), headers, JITSTATIC_KEYADMIN_REALM, user.getName());
    }

    @PUT
    @Timed(name = "put_keyadmin_time")
    @Metered(name = "put_keyadmin_counter")
    @ExceptionMetered(name = "put_keyadmin_exception")
    @Path(JITSTATIC_KEYADMIN_REALM + "/{key : .+}")
    @Consumes({ APPLICATION_JSON, APPLICATION_XML })
    @Produces({ APPLICATION_JSON, APPLICATION_XML })
    public void put(@Suspended AsyncResponse asyncResponse, final @PathParam("key") String key, final @QueryParam("ref") String ref,
            final @Valid @NotNull UserData data, final @Auth Optional<User> remoteUserHolder,
            final @Context HttpHeaders headers, final @Context Request request) {
        final User user = remoteUserHolder.orElseThrow(() -> APIHelper.createAuthenticationChallenge(GIT_REALM));
        APIHelper.checkValidRef(ref);
        authorize(user, null);
        modifyUser(key, APIHelper.setToDefaultRefIfNull(ref, defaultRef), data, request, user, JITSTATIC_KEYADMIN_REALM, asyncResponse);
    }

    @POST
    @Timed(name = "post_keyadmin_time")
    @Metered(name = "post_keyadmin_counter")
    @ExceptionMetered(name = "post_keyadmin_exception")
    @Path(JITSTATIC_KEYADMIN_REALM + "/{key : .+}")
    @Consumes({ APPLICATION_JSON, APPLICATION_XML })
    @Produces({ APPLICATION_JSON, APPLICATION_XML })
    public void post(final @Suspended AsyncResponse asyncResponse, final @PathParam("key") String key, final @QueryParam("ref") String ref,
            final @Valid @NotNull @Validated({ Adding.class, Default.class }) UserData data,
            final @Auth Optional<User> remoteUserHolder) {
        final User user = remoteUserHolder.orElseThrow(() -> APIHelper.createAuthenticationChallenge(GIT_REALM));
        APIHelper.checkRef(ref);
        authorize(user, null);
        addUser(key, APIHelper.setToDefaultRefIfNull(ref, defaultRef), data, user, JITSTATIC_KEYADMIN_REALM, asyncResponse);
    }

    @DELETE
    @Timed(name = "delete_keyadmin_time")
    @Metered(name = "delete_keyadmin_counter")
    @ExceptionMetered(name = "delete_keyadmin_exception")
    @Path(JITSTATIC_KEYADMIN_REALM + "/{key : .+}")
    @Consumes({ APPLICATION_JSON, APPLICATION_XML })
    @Produces({ APPLICATION_JSON, APPLICATION_XML })
    public void delete(final @Suspended AsyncResponse asyncResponse, final @PathParam("key") String key, final @QueryParam("ref") String ref,
            final @Auth Optional<User> remoteUserHolder) {
        final User user = remoteUserHolder.orElseThrow(() -> APIHelper.createAuthenticationChallenge(GIT_REALM));
        APIHelper.checkRef(ref);
        authorize(user, null);
        deleteUser(key, APIHelper.setToDefaultRefIfNull(ref, defaultRef), user, JITSTATIC_KEYADMIN_REALM, asyncResponse);
    }

    @GET
    @Timed(name = "get_keyuser_time")
    @Metered(name = "get_keyuser_counter")
    @ExceptionMetered(name = "get_keyuser_exception")
    @Path(JITSTATIC_KEYUSER_REALM + "/{key : .+}")
    @Consumes({ APPLICATION_JSON, APPLICATION_XML })
    @Produces({ APPLICATION_JSON, APPLICATION_XML })
    public Response getUser(final @PathParam("key") String key, final @QueryParam("ref") String askedRef,
            final @Auth Optional<User> remoteUserHolder, final @Context HttpHeaders headers) {
        final User user = remoteUserHolder.orElseThrow(() -> APIHelper.createAuthenticationChallenge(JITSTATIC_KEYADMIN_REALM));
        APIHelper.checkRef(askedRef);
        final String ref = APIHelper.setToDefaultRefIfNull(askedRef, defaultRef);
        authorize(ref, user);
        return getUser(key, ref, headers, JITSTATIC_KEYUSER_REALM, user.getName());
    }

    @PUT
    @Timed(name = "put_keyuser_time")
    @Metered(name = "put_keyuser_counter")
    @ExceptionMetered(name = "put_keyuser_exception")
    @Path(JITSTATIC_KEYUSER_REALM + "/{key : .+}")
    @Consumes({ APPLICATION_JSON, APPLICATION_XML })
    @Produces({ APPLICATION_JSON, APPLICATION_XML })
    public void putUser(final @Suspended AsyncResponse asyncResponse, final @PathParam("key") String key, final @QueryParam("ref") String askedRef,
            final @Valid @NotNull UserData data, final @Auth Optional<User> remoteUserHolder,
            final @Context HttpHeaders headers, final @Context Request request) {
        final User user = remoteUserHolder.orElseThrow(() -> APIHelper.createAuthenticationChallenge(JITSTATIC_KEYADMIN_REALM));
        APIHelper.checkRef(askedRef);
        final String ref = APIHelper.setToDefaultRefIfNull(askedRef, defaultRef);
        authorize(ref, user);
        modifyUser(key, ref, data, request, user, JITSTATIC_KEYUSER_REALM, asyncResponse);
    }

    @POST
    @Timed(name = "post_keyuser_time")
    @Metered(name = "post_keyuser_counter")
    @ExceptionMetered(name = "post_keyuser_exception")
    @Path(JITSTATIC_KEYUSER_REALM + "/{key : .+}")
    @Consumes({ APPLICATION_JSON, APPLICATION_XML })
    @Produces({ APPLICATION_JSON, APPLICATION_XML })
    public void postUser(final @Suspended AsyncResponse asyncResponse, final @PathParam("key") String key, final @QueryParam("ref") String askedRef,
            final @Valid @NotNull @Validated({ Adding.class, Default.class }) UserData data,
            final @Auth Optional<User> remoteUserHolder) {
        final User user = remoteUserHolder.orElseThrow(() -> APIHelper.createAuthenticationChallenge(JITSTATIC_KEYADMIN_REALM));
        APIHelper.checkRef(askedRef);
        final String ref = APIHelper.setToDefaultRefIfNull(askedRef, defaultRef);
        authorize(ref, user);
        addUser(key, ref, data, user, JITSTATIC_KEYUSER_REALM, asyncResponse);
    }

    @DELETE
    @Timed(name = "delete_keyuser_time")
    @Metered(name = "delete_keyuser_counter")
    @ExceptionMetered(name = "delete_keyuser_exception")
    @Path(JITSTATIC_KEYUSER_REALM + "/{key : .+}")
    @Consumes({ APPLICATION_JSON, APPLICATION_XML })
    @Produces({ APPLICATION_JSON, APPLICATION_XML })
    public void deleteUser(final @Suspended AsyncResponse asyncResponse, final @PathParam("key") String key, final @QueryParam("ref") String askedRef,
            final @Auth Optional<User> remoteUserHolder) {
        final User user = remoteUserHolder.orElseThrow(() -> APIHelper.createAuthenticationChallenge(JITSTATIC_KEYADMIN_REALM));
        APIHelper.checkRef(askedRef);
        final String ref = APIHelper.setToDefaultRefIfNull(askedRef, defaultRef);
        authorize(ref, user);
        deleteUser(key, ref, user, JITSTATIC_KEYUSER_REALM, asyncResponse);
    }

    @GET
    @Timed(name = "get_gituser_time")
    @Metered(name = "get_gituser_counter")
    @ExceptionMetered(name = "get_gituser_exception")
    @Path(GIT_REALM + "/{key : .+}")
    @Consumes({ APPLICATION_JSON, APPLICATION_XML })
    @Produces({ APPLICATION_JSON, APPLICATION_XML })
    public Response getGitUser(final @PathParam("key") String key, final @Auth Optional<User> remoteUserHolder,
            final @Context HttpHeaders headers) {
        final User user = remoteUserHolder.orElseThrow(() -> APIHelper.createAuthenticationChallenge(GIT_REALM));
        authorize(user, CREATE);
        return getUser(key, REFS_HEADS_SECRETS, headers, GIT_REALM, user.getName());
    }

    @PUT
    @Timed(name = "put_gituser_time")
    @Metered(name = "put_gituser_counter")
    @ExceptionMetered(name = "put_gituser_exception")
    @Path(GIT_REALM + "/{key : .+}")
    @Consumes({ APPLICATION_JSON, APPLICATION_XML })
    @Produces({ APPLICATION_JSON, APPLICATION_XML })
    public void putGitUser(@Suspended AsyncResponse asyncResponse, final @PathParam("key") String key, final @Valid @NotNull UserData data,
            final @Auth Optional<User> remoteUserHolder, final @Context HttpHeaders headers,
            final @Context Request request) {
        final User user = remoteUserHolder.orElseThrow(() -> APIHelper.createAuthenticationChallenge(GIT_REALM));
        authorize(user, CREATE);
        modifyUser(key, REFS_HEADS_SECRETS, data, request, user, GIT_REALM, asyncResponse);
    }

    @POST
    @Timed(name = "post_gituser_time")
    @Metered(name = "post_gituser_counter")
    @ExceptionMetered(name = "post_gituser_exception")
    @Path(GIT_REALM + "/{key : .+}")
    @Consumes({ APPLICATION_JSON, APPLICATION_XML })
    @Produces({ APPLICATION_JSON, APPLICATION_XML })
    public void postGitUser(final @Suspended AsyncResponse asyncResponse, final @PathParam("key") String key,
            final @Valid @NotNull @Validated({ Adding.class, Default.class }) UserData data,
            final @Auth Optional<User> remoteUserHolder) {
        final User user = remoteUserHolder.orElseThrow(() -> APIHelper.createAuthenticationChallenge(GIT_REALM));
        authorize(user, CREATE);
        addUser(key, REFS_HEADS_SECRETS, data, user, GIT_REALM, asyncResponse);
    }

    @DELETE
    @Timed(name = "delete_gituser_time")
    @Metered(name = "delete_gituser_counter")
    @ExceptionMetered(name = "delete_gituser_exception")
    @Path(GIT_REALM + "/{key : .+}")
    @Consumes({ APPLICATION_JSON, APPLICATION_XML })
    @Produces({ APPLICATION_JSON, APPLICATION_XML })
    public void deleteGitUser(final @Suspended AsyncResponse asyncResponse, final @PathParam("key") String key, final @Auth Optional<User> remoteUserHolder) {
        final User user = remoteUserHolder.orElseThrow(() -> APIHelper.createAuthenticationChallenge(GIT_REALM));
        authorize(user, CREATE);
        deleteUser(key, REFS_HEADS_SECRETS, user, GIT_REALM, asyncResponse);
    }

    private void modifyUser(final String key, final String ref, final UserData data, final Request request, final User user, final String realm,
            final AsyncResponse asyncResponse) {
        CompletableFuture.supplyAsync(() -> {
            try {
                return storage.getUserData(key, ref, realm);
            } catch (RefNotFoundException e) {
                throw new WebApplicationException(HttpStatus.NOT_FOUND_404);
            }
        }, executor).thenApplyAsync(userData -> {
            if (userData == null || !userData.isPresent()) {
                throw new WebApplicationException(key, HttpStatus.NOT_FOUND_404);
            }
            final String version = userData.getLeft();
            final EntityTag entityTag = new EntityTag(version);
            final ResponseBuilder evaluatePreconditions = request.evaluatePreconditions(entityTag);
            if (evaluatePreconditions != null) {
                throw new WebApplicationException(evaluatePreconditions.header(HttpHeaders.CONTENT_ENCODING, UTF_8).tag(entityTag).build());
            }
            return storage.updateUser(key, ref, realm, user.getName(), new io.jitstatic.auth.UserData(data.getRoles(), data.getBasicPassword(), null, null),
                    version);
        }, executor).thenComposeAsync(Function.identity()).thenApplyAsync(result -> {
            if (result.isRight()) {
                return Response.status(Status.PRECONDITION_FAILED).build();
            }
            final String newVersion = result.getLeft();

            if (newVersion == null) {
                throw new WebApplicationException(Status.NOT_FOUND);
            }
            LOG.info("{} logged in and modified key {} in {}", user, key, ref);
            return Response.ok().tag(new EntityTag(newVersion)).header(HttpHeaders.CONTENT_ENCODING, UTF_8).build();
        }, executor).exceptionally(helper::exceptionHandlerPUTAPI).thenAcceptAsync(asyncResponse::resume, executor);
    }

    private void addUser(final String key, final String ref, final UserData data, final User user, final String realm, final AsyncResponse asyncResponse) {
        CompletableFuture.supplyAsync(() -> {
            try {
                return storage.getUserData(key, ref, realm);
            } catch (RefNotFoundException e) {
                throw new WebApplicationException(Status.NOT_FOUND);
            }
        }, executor).thenApplyAsync(userData -> {
            if (userData != null && userData.isPresent()) {
                throw new WebApplicationException(key + " already exist", Status.CONFLICT);
            }
            return storage.addUser(key, ref, realm, user.getName(),
                    hashService.constructUserData(data.getRoles(), data.getBasicPassword()));
        }, executor).thenCompose(Function.identity()).thenApplyAsync(newVersion -> {
            if (newVersion == null) {
                throw new WebApplicationException(Status.NOT_FOUND);
            }
            LOG.info("{} logged in and added key {} in {}", user, key, ref);
            return Response.ok().tag(new EntityTag(newVersion)).header(HttpHeaders.CONTENT_ENCODING, UTF_8).build();
        }, executor).exceptionally(helper::exceptionHandlerPOSTAPI).thenAcceptAsync(asyncResponse::resume, executor);

    }

    private void deleteUser(final String key, final String ref, final User user, final String realm, final AsyncResponse asyncResponse) {
        CompletableFuture.supplyAsync(() -> {
            try {
                return storage.getUserData(key, ref, realm);
            } catch (RefNotFoundException e) {
                throw new WebApplicationException(Status.NOT_FOUND);
            }
        }, executor).thenApplyAsync(userData -> {
            if (userData != null && userData.isPresent()) {
                storage.deleteUser(key, ref, realm, user.getName());
                LOG.info("{} logged in and deleted key {} in {}", user, key, ref);
                return Response.ok().build();
            }
            throw new WebApplicationException(key, Status.NOT_FOUND);
        }, executor).exceptionally(helper::execptionHandler).thenAcceptAsync(asyncResponse::resume, executor);

    }

    private UserIdentity authorize(final User user, final String role) {
        final UserIdentity uid = gitAuthenticator.login(user.getName(), new Password(user.getPassword()), null);
        if (uid == null) {
            throw new WebApplicationException(Status.FORBIDDEN);
        }
        if (role != null && !uid.isUserInRole(role, null)) {
            throw new WebApplicationException(Status.FORBIDDEN);
        }
        return uid;
    }

    private Response getUser(final String key, final String ref, final HttpHeaders headers, final String realm, final String user) {
        try {
            final Pair<String, io.jitstatic.auth.UserData> value = storage.getUserData(key, ref, realm);
            if (value == null || !value.isPresent()) {
                throw new WebApplicationException(Status.NOT_FOUND);
            }

            final EntityTag tag = new EntityTag(value.getKey());
            final Response noChange = APIHelper.checkETag(headers, tag);
            if (noChange != null) {
                return noChange;
            }
            LOG.info("{} logged in and accessed {} in {}", user, key, ref);
            return Response.ok(new UserData(value.getRight())).tag(new EntityTag(value.getLeft())).encoding(UTF_8).build();
        } catch (RefNotFoundException e) {
            throw new WebApplicationException(Status.NOT_FOUND);
        }
    }

    private void authorize(final String ref, final User user) {
        if (!adminKeyAuthenticator.authenticate(user, ref)) {
            throw new WebApplicationException(Status.FORBIDDEN);
        }
    }
}
