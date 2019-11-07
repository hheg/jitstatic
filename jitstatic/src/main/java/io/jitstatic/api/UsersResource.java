package io.jitstatic.api;

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

import static io.jitstatic.JitStaticConstants.GIT_CREATE;
import static io.jitstatic.JitStaticConstants.GIT_FORCEPUSH;
import static io.jitstatic.JitStaticConstants.JITSTATIC_GIT_REALM;
import static io.jitstatic.JitStaticConstants.JITSTATIC_KEYADMIN_REALM;
import static io.jitstatic.JitStaticConstants.JITSTATIC_KEYUSER_REALM;
import static io.jitstatic.JitStaticConstants.GIT_PULL;
import static io.jitstatic.JitStaticConstants.GIT_PUSH;
import static io.jitstatic.JitStaticConstants.REFS_HEADS_SECRETS;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static javax.ws.rs.core.MediaType.APPLICATION_XML;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

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
import javax.ws.rs.core.SecurityContext;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.annotation.ExceptionMetered;
import com.codahale.metrics.annotation.Metered;
import com.codahale.metrics.annotation.Timed;
import com.spencerwi.either.Either;

import io.dropwizard.auth.Auth;
import io.dropwizard.validation.Validated;
import io.jitstatic.JitStaticConstants;
import io.jitstatic.Role;
import io.jitstatic.api.constraints.Adding;
import io.jitstatic.api.constraints.GitRolesGroup;
import io.jitstatic.auth.User;
import io.jitstatic.hosted.FailedToLock;
import io.jitstatic.storage.HashService;
import io.jitstatic.storage.Storage;
import io.jitstatic.utils.Pair;
import io.jitstatic.utils.WrappingAPIException;

@Path("users")
public class UsersResource {

    final String defaultRef;
    private static final Logger LOG = LoggerFactory.getLogger(UsersResource.class);
    private static final String UTF_8 = "utf-8";
    private final Storage storage;
    private final APIHelper helper;
    private final HashService hashService;

    public UsersResource(final Storage storage, final String defaultBranch, final HashService hashService) {
        this.storage = Objects.requireNonNull(storage);
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
    public void get(@Suspended AsyncResponse asyncResponse, final @PathParam("key") String key, final @QueryParam("ref") String ref, final @Auth User user,
            final @Context HttpHeaders headers, @Context SecurityContext context, @Context ExecutorService executor) {
        APIHelper.checkRef(ref);
        authorize(Set.of(createUserRole(key, JITSTATIC_KEYADMIN_REALM), JITSTATIC_KEYADMIN_REALM), context);
        getUser(key, APIHelper.setToDefaultRefIfNull(ref, defaultRef), headers, JITSTATIC_KEYADMIN_REALM, user, asyncResponse, executor);
    }

    @PUT
    @Timed(name = "put_keyadmin_time")
    @Metered(name = "put_keyadmin_counter")
    @ExceptionMetered(name = "put_keyadmin_exception")
    @Path(JITSTATIC_KEYADMIN_REALM + "/{key : .+}")
    @Consumes({ APPLICATION_JSON, APPLICATION_XML })
    @Produces({ APPLICATION_JSON, APPLICATION_XML })
    public void put(@Suspended AsyncResponse asyncResponse, final @PathParam("key") String key, final @QueryParam("ref") String ref,
            final @Validated @Valid @NotNull UserData data, final @Auth User user, final @Context HttpHeaders headers, final @Context Request request,
            @Context SecurityContext context, @Context ExecutorService executor) {
        APIHelper.checkValidRef(ref);
        authorize(Set.of(createUserRole(key, JITSTATIC_KEYADMIN_REALM), JITSTATIC_KEYADMIN_REALM), context);
        modifyUser(key, APIHelper.setToDefaultRefIfNull(ref, defaultRef), data, request, user, JITSTATIC_KEYADMIN_REALM, asyncResponse, context, executor);
    }

    @POST
    @Timed(name = "post_keyadmin_time")
    @Metered(name = "post_keyadmin_counter")
    @ExceptionMetered(name = "post_keyadmin_exception")
    @Path(JITSTATIC_KEYADMIN_REALM + "/{key : .+}")
    @Consumes({ APPLICATION_JSON, APPLICATION_XML })
    @Produces({ APPLICATION_JSON, APPLICATION_XML })
    public void post(final @Suspended AsyncResponse asyncResponse, final @PathParam("key") String key, final @QueryParam("ref") String ref,
            final @Valid @NotNull @Validated({ Adding.class, Default.class }) UserData data, final @Auth User user, @Context SecurityContext context,
            @Context ExecutorService executor) {
        APIHelper.checkRef(ref);
        authorize(Set.of(createUserRole(key, JITSTATIC_KEYADMIN_REALM), JITSTATIC_KEYADMIN_REALM), context);
        addUser(key, APIHelper.setToDefaultRefIfNull(ref, defaultRef), data, user, JITSTATIC_KEYADMIN_REALM, asyncResponse, executor);
    }

    @DELETE
    @Timed(name = "delete_keyadmin_time")
    @Metered(name = "delete_keyadmin_counter")
    @ExceptionMetered(name = "delete_keyadmin_exception")
    @Path(JITSTATIC_KEYADMIN_REALM + "/{key : .+}")
    @Consumes({ APPLICATION_JSON, APPLICATION_XML })
    @Produces({ APPLICATION_JSON, APPLICATION_XML })
    public void delete(final @Suspended AsyncResponse asyncResponse, final @PathParam("key") String key, final @QueryParam("ref") String ref,
            final @Auth User user, @Context SecurityContext context, @Context ExecutorService executor) {
        APIHelper.checkRef(ref);
        authorize(Set.of(createUserRole(key, JITSTATIC_KEYADMIN_REALM), JITSTATIC_KEYADMIN_REALM), context);
        deleteUser(key, APIHelper.setToDefaultRefIfNull(ref, defaultRef), user, JITSTATIC_KEYADMIN_REALM, asyncResponse, executor);
    }

    @GET
    @Timed(name = "get_keyuser_time")
    @Metered(name = "get_keyuser_counter")
    @ExceptionMetered(name = "get_keyuser_exception")
    @Path(JITSTATIC_KEYUSER_REALM + "/{key : .+}")
    @Consumes({ APPLICATION_JSON, APPLICATION_XML })
    @Produces({ APPLICATION_JSON, APPLICATION_XML })
    public void getUser(@Suspended AsyncResponse asyncResponse, final @PathParam("key") String key, final @QueryParam("ref") String askedRef,
            final @Auth User user, final @Context HttpHeaders headers, @Context SecurityContext context, @Context ExecutorService executor) {
        APIHelper.checkRef(askedRef);
        final String ref = APIHelper.setToDefaultRefIfNull(askedRef, defaultRef);
        authorize(Set.of(createUserRole(key, JITSTATIC_KEYUSER_REALM), JITSTATIC_KEYUSER_REALM, JITSTATIC_GIT_REALM), context);
        getUser(key, ref, headers, JITSTATIC_KEYUSER_REALM, user, asyncResponse, executor);
    }

    @PUT
    @Timed(name = "put_keyuser_time")
    @Metered(name = "put_keyuser_counter")
    @ExceptionMetered(name = "put_keyuser_exception")
    @Path(JITSTATIC_KEYUSER_REALM + "/{key : .+}")
    @Consumes({ APPLICATION_JSON, APPLICATION_XML })
    @Produces({ APPLICATION_JSON, APPLICATION_XML })
    public void putUser(final @Suspended AsyncResponse asyncResponse, final @PathParam("key") String key, final @QueryParam("ref") String askedRef,
            final @Validated @Valid @NotNull UserData data, final @Auth User user,
            final @Context HttpHeaders headers, final @Context Request request, @Context SecurityContext context, @Context ExecutorService executor) {
        APIHelper.checkRef(askedRef);
        final String ref = APIHelper.setToDefaultRefIfNull(askedRef, defaultRef);
        authorize(Set.of(createUserRole(key, JITSTATIC_KEYUSER_REALM), JITSTATIC_KEYUSER_REALM, JITSTATIC_GIT_REALM), context);
        modifyUser(key, ref, data, request, user, JITSTATIC_KEYUSER_REALM, asyncResponse, context, executor);
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
            final @Auth User user, @Context SecurityContext context, @Context ExecutorService executor) {
        APIHelper.checkRef(askedRef);
        final String ref = APIHelper.setToDefaultRefIfNull(askedRef, defaultRef);
        authorize(Set.of(createUserRole(key, JITSTATIC_KEYUSER_REALM), JITSTATIC_KEYUSER_REALM, JITSTATIC_GIT_REALM), context);
        addUser(key, ref, data, user, JITSTATIC_KEYUSER_REALM, asyncResponse, executor);
    }

    @DELETE
    @Timed(name = "delete_keyuser_time")
    @Metered(name = "delete_keyuser_counter")
    @ExceptionMetered(name = "delete_keyuser_exception")
    @Path(JITSTATIC_KEYUSER_REALM + "/{key : .+}")
    @Consumes({ APPLICATION_JSON, APPLICATION_XML })
    @Produces({ APPLICATION_JSON, APPLICATION_XML })
    public void deleteUser(final @Suspended AsyncResponse asyncResponse, final @PathParam("key") String key, final @QueryParam("ref") String askedRef,
            final @Auth User user, @Context SecurityContext context, @Context ExecutorService executor) {
        APIHelper.checkRef(askedRef);
        final String ref = APIHelper.setToDefaultRefIfNull(askedRef, defaultRef);
        authorize(Set.of(createUserRole(key, JITSTATIC_KEYUSER_REALM), JITSTATIC_KEYUSER_REALM, JITSTATIC_GIT_REALM), context);
        deleteUser(key, ref, user, JITSTATIC_KEYUSER_REALM, asyncResponse, executor);
    }

    @GET
    @Timed(name = "get_gituser_time")
    @Metered(name = "get_gituser_counter")
    @ExceptionMetered(name = "get_gituser_exception")
    @Path(JITSTATIC_GIT_REALM + "/{key : .+}")
    @Consumes({ APPLICATION_JSON, APPLICATION_XML })
    @Produces({ APPLICATION_JSON, APPLICATION_XML })
    public void getGitUser(@Suspended AsyncResponse asyncResponse, final @PathParam("key") String key, final @Auth User user,
            final @Context HttpHeaders headers, @Context SecurityContext context, @Context ExecutorService executor) {
        authorize(Set.of(createUserRole(key, JITSTATIC_GIT_REALM), GIT_CREATE, GIT_PUSH, GIT_FORCEPUSH, GIT_PULL), context);
        getUser(key, REFS_HEADS_SECRETS, headers, JITSTATIC_GIT_REALM, user, asyncResponse, executor);
    }

    @PUT
    @Timed(name = "put_gituser_time")
    @Metered(name = "put_gituser_counter")
    @ExceptionMetered(name = "put_gituser_exception")
    @Path(JITSTATIC_GIT_REALM + "/{key : .+}")
    @Consumes({ APPLICATION_JSON, APPLICATION_XML })
    @Produces({ APPLICATION_JSON, APPLICATION_XML })
    public void putGitUser(@Suspended AsyncResponse asyncResponse, final @PathParam("key") String key,
            final @Validated({ GitRolesGroup.class, Default.class }) @Valid @NotNull UserData data, final @Auth User user, final @Context HttpHeaders headers,
            final @Context Request request, @Context SecurityContext context, @Context ExecutorService executor) {
        authorize(Set.of(createUserRole(key, JITSTATIC_GIT_REALM), GIT_CREATE, GIT_PUSH, GIT_FORCEPUSH), context);
        modifyUser(key, REFS_HEADS_SECRETS, data, request, user, JITSTATIC_GIT_REALM, asyncResponse, context, executor);
    }

    @POST
    @Timed(name = "post_gituser_time")
    @Metered(name = "post_gituser_counter")
    @ExceptionMetered(name = "post_gituser_exception")
    @Path(JITSTATIC_GIT_REALM + "/{key : .+}")
    @Consumes({ APPLICATION_JSON, APPLICATION_XML })
    @Produces({ APPLICATION_JSON, APPLICATION_XML })
    public void postGitUser(final @Suspended AsyncResponse asyncResponse, final @PathParam("key") String key,
            final @Valid @NotNull @Validated({ GitRolesGroup.class, Adding.class, Default.class }) UserData data,
            final @Auth User user, @Context SecurityContext context, @Context ExecutorService executor) {
        authorize(Set.of(createUserRole(key, JITSTATIC_GIT_REALM), GIT_CREATE, GIT_PUSH, GIT_FORCEPUSH), context);
        addUser(key, REFS_HEADS_SECRETS, data, user, JITSTATIC_GIT_REALM, asyncResponse, executor);
    }

    @DELETE
    @Timed(name = "delete_gituser_time")
    @Metered(name = "delete_gituser_counter")
    @ExceptionMetered(name = "delete_gituser_exception")
    @Path(JITSTATIC_GIT_REALM + "/{key : .+}")
    @Consumes({ APPLICATION_JSON, APPLICATION_XML })
    @Produces({ APPLICATION_JSON, APPLICATION_XML })
    public void deleteGitUser(final @Suspended AsyncResponse asyncResponse, final @PathParam("key") String key, final @Auth User user,
            @Context SecurityContext context, @Context ExecutorService executor) {
        authorize(Set.of(createUserRole(key, JITSTATIC_GIT_REALM), GIT_CREATE, GIT_PUSH, GIT_FORCEPUSH), context);
        deleteUser(key, REFS_HEADS_SECRETS, user, JITSTATIC_GIT_REALM, asyncResponse, executor);
    }

    private void modifyUser(final String key, final String ref, final UserData data, final Request request, final User user, final String realm,
            final AsyncResponse asyncResponse, SecurityContext context, ExecutorService executor) {
        try {
            storage.getUserData(key, ref, realm)
                    .thenApplyAsync(userData -> {
                        if (userData == null || !userData.isPresent()) {
                            throw new WebApplicationException(key, HttpStatus.NOT_FOUND_404);
                        }
                        final String version = userData.getLeft();
                        final EntityTag entityTag = new EntityTag(version);
                        final ResponseBuilder evaluatePreconditions = request.evaluatePreconditions(entityTag);
                        if (evaluatePreconditions != null) {
                            throw new WebApplicationException(evaluatePreconditions.header(HttpHeaders.CONTENT_ENCODING, UTF_8).tag(entityTag).build());
                        }
                        try {
                            return storage.updateUser(key, ref, realm, user.getName(), getRoles(data, context, userData), version);
                        } catch (RefNotFoundException e) {
                            return CompletableFuture.<Either<String, FailedToLock>>failedFuture(new WrappingAPIException(e));
                        }
                    }, executor)
                    .thenComposeAsync(s -> s)
                    .thenApplyAsync(result -> {
                        if (result.isRight()) {
                            return Response.status(Status.PRECONDITION_FAILED).build();
                        }
                        final String newVersion = result.getLeft();

                        if (newVersion == null) {
                            throw new WebApplicationException(Status.NOT_FOUND);
                        }
                        LOG.info("{} logged in and modified key {}/{} in {}", user, realm, key, ref);
                        return Response.ok().tag(new EntityTag(newVersion)).header(HttpHeaders.CONTENT_ENCODING, UTF_8).build();
                    }, executor).exceptionally(helper::exceptionHandlerPUTAPI).thenAcceptAsync(asyncResponse::resume, executor);
        } catch (RefNotFoundException e) {
            throw new WebApplicationException(String.format("Ref %s not found", ref), Status.BAD_REQUEST);
        }

    }

    io.jitstatic.auth.UserData getRoles(final UserData data, SecurityContext context, Pair<String, io.jitstatic.auth.UserData> userData) {
        if (context.isUserInRole(JitStaticConstants.ROLERROLES)) {
            return new io.jitstatic.auth.UserData(data.getRoles(), data.getBasicPassword(), null, null);
        } else {
            return new io.jitstatic.auth.UserData(userData.getRight().getRoles(), data.getBasicPassword(), null, null);
        }
    }

    private void addUser(final String key, final String ref, final UserData data, final User user, final String realm, final AsyncResponse asyncResponse,
            final ExecutorService executor) {
        try {
            storage.getUserData(key, ref, realm)
                    .thenApplyAsync(userData -> {
                        if (userData != null && userData.isPresent()) {
                            throw new WebApplicationException(key + " already exist", Status.CONFLICT);
                        }
                        return addUser(key, ref, data, user, realm);
                    }, executor)
                    .thenCompose(s -> s)
                    .thenApplyAsync(newVersion -> {
                        if (newVersion == null) {
                            throw new WebApplicationException(Status.NOT_FOUND);
                        }
                        LOG.info("{} logged in and added key {}/{} in {}", user, realm, key, ref);
                        return Response.ok().tag(new EntityTag(newVersion)).header(HttpHeaders.CONTENT_ENCODING, UTF_8).build();
                    }, executor).exceptionally(helper::exceptionHandlerPOSTAPI).thenAcceptAsync(asyncResponse::resume, executor);
        } catch (RefNotFoundException e) {
            throw new WebApplicationException(e.getMessage(), Status.BAD_REQUEST);
        }
    }

    private CompletableFuture<String> addUser(final String key, final String ref, final UserData data, final User user, final String realm) {
        try {
            return storage.addUser(key, ref, realm, user.getName(), hashService.constructUserData(data.getRoles(), data.getBasicPassword()));
        } catch (RefNotFoundException e) {
            return CompletableFuture.failedFuture(new WrappingAPIException(e));
        }
    }

    private void deleteUser(final String key, final String ref, final User user, final String realm, final AsyncResponse asyncResponse,
            final ExecutorService executor) {
        try {
            storage.getUserData(key, ref, realm)
                    .thenApplyAsync(userData -> {
                        if (userData != null && userData.isPresent()) {
                            try {
                                storage.deleteUser(key, ref, realm, user.getName());
                                LOG.info("{} logged in and deleted key {}/{} in {}", user, realm, key, ref);
                            } catch (RefNotFoundException e) {
                                return new WebApplicationException(String.format("Ref %s not found", ref), Status.BAD_REQUEST);
                            }
                            return Response.ok().build();
                        }
                        throw new WebApplicationException(key, Status.NOT_FOUND);
                    }, executor).exceptionally(helper::execptionHandler).thenAcceptAsync(asyncResponse::resume, executor);
        } catch (RefNotFoundException e) {
            throw new WebApplicationException(String.format("Ref %s not found", ref), Status.BAD_REQUEST);
        }

    }

    private void authorize(final Set<String> roles, SecurityContext context) {
        if (!APIHelper.isUserInRole(context, roles.stream().map(Role::new).collect(Collectors.toSet()))) {
            throw new WebApplicationException(Status.FORBIDDEN);
        }
    }

    private void getUser(final String key, final String ref, final HttpHeaders headers, final String realm, final User user, AsyncResponse asyncResponse,
            final ExecutorService executor) {
        try {
            storage.getUserData(key, ref, realm).thenApplyAsync(value -> {
                if (value == null || !value.isPresent()) {
                    throw new WebApplicationException(Status.NOT_FOUND);
                }

                final EntityTag tag = new EntityTag(value.getKey());
                final Response noChange = APIHelper.checkETag(headers, tag);
                if (noChange != null) {
                    return noChange;
                }
                LOG.info("{} logged in and accessed {}/{} in {}", user, realm, key, ref);
                return Response.ok(new UserData(value.getRight())).tag(new EntityTag(value.getLeft())).encoding(UTF_8).build();
            }, executor).exceptionally(helper::execptionHandler).thenAcceptAsync(asyncResponse::resume, executor);
        } catch (RefNotFoundException e) {
            throw new WebApplicationException(e.getMessage(), Status.BAD_REQUEST);
        }
    }

    private String createUserRole(final String key, final String realm) {
        return realm + "/" + key;
    }

}
