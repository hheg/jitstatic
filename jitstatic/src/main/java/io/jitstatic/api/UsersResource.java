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

import static io.jitstatic.JitStaticConstants.GIT_REALM;
import static io.jitstatic.JitStaticConstants.JITSTATIC_KEYADMIN_REALM;
import static io.jitstatic.JitStaticConstants.JITSTATIC_KEYUSER_REALM;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static javax.ws.rs.core.MediaType.APPLICATION_XML;

import java.util.Optional;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;
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
import javax.ws.rs.core.Context;
import javax.ws.rs.core.EntityTag;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.Response.Status;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.util.security.Password;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.annotation.ExceptionMetered;
import com.codahale.metrics.annotation.Metered;
import com.codahale.metrics.annotation.Timed;
import com.spencerwi.either.Either;

import io.dropwizard.auth.Auth;
import io.jitstatic.auth.KeyAdminAuthenticator;
import io.jitstatic.auth.User;
import io.jitstatic.auth.UserData;
import io.jitstatic.hosted.FailedToLock;
import io.jitstatic.hosted.LoginService;
import io.jitstatic.storage.Storage;
import io.jitstatic.utils.Pair;

@Path("users")
public class UsersResource {

    private static final Logger LOG = LoggerFactory.getLogger(UsersResource.class);
    private static final String UTF_8 = null;
    private final Storage storage;
    private final KeyAdminAuthenticator adminKeyAuthenticator;
    private final APIHelper helper;
    private final LoginService gitAuthenticator;

    public UsersResource(final Storage storage, final KeyAdminAuthenticator adminKeyAuthenticator, final LoginService gitAuthenticator) {
        this.storage = storage;
        this.adminKeyAuthenticator = adminKeyAuthenticator;
        this.gitAuthenticator = gitAuthenticator;
        this.helper = new APIHelper(LOG);
    }

    @GET
    @Timed(name = "get_keyadmin_time")
    @Metered(name = "get_keyadmin_counter")
    @ExceptionMetered(name = "get_keyadmin_exception")
    @Path(JITSTATIC_KEYADMIN_REALM + "/{key : .+}")
    @Consumes({ APPLICATION_JSON, APPLICATION_XML })
    @Produces({ APPLICATION_JSON, APPLICATION_XML })
    public Response get(final @PathParam("key") String key, final @QueryParam("ref") String ref, final @Auth Optional<User> remoteUserHolder,
            final @Context HttpHeaders headers) {
        if (!remoteUserHolder.isPresent()) {
            return helper.respondAuthenticationChallenge(GIT_REALM);
        }
        helper.checkRef(ref);
        final User user = remoteUserHolder.get();
        authorize(user);
        return getUser(key, ref, headers, JITSTATIC_KEYADMIN_REALM, user.getName());
    }

    private Response getUser(final String key, final String ref, final HttpHeaders headers, final String realm, String user) {
        try {
            final Pair<String, UserData> value = storage.getUserData(key, ref, realm);
            if (value == null || !value.isPresent()) {
                throw new WebApplicationException(Status.NOT_FOUND);
            }

            final EntityTag tag = new EntityTag(value.getKey());
            final Response noChange = helper.checkETag(headers, tag);
            if (noChange != null) {
                return noChange;
            }
            LOG.info("{} logged in and accessed {}", user, key);
            return Response.ok(value.getRight()).tag(new EntityTag(value.getLeft())).encoding("utf-8").build();
        } catch (RefNotFoundException e) {
            throw new WebApplicationException(Status.NOT_FOUND);
        }
    }

    @PUT
    @Timed(name = "put_keyadmin_time")
    @Metered(name = "put_keyadmin_counter")
    @ExceptionMetered(name = "put_keyadmin_exception")
    @Path(JITSTATIC_KEYADMIN_REALM + "/{key : .+}")
    @Consumes({ APPLICATION_JSON, APPLICATION_XML })
    @Produces({ APPLICATION_JSON, APPLICATION_XML })
    public Response put(final @PathParam("key") String key, final @QueryParam("ref") String ref, final @Valid @NotNull UserData data,
            final @Auth Optional<User> remoteUserHolder, final @Context HttpHeaders headers, final @Context Request request) {
        if (!remoteUserHolder.isPresent()) {
            return helper.respondAuthenticationChallenge(GIT_REALM);
        }
        helper.checkValidRef(ref);
        final User user = remoteUserHolder.get();
        authorize(user);
        return modifyUser(key, ref, data, request, user, JITSTATIC_KEYADMIN_REALM);

    }

    private Response modifyUser(final String key, final String ref, final UserData data, final Request request, final User user, final String realm) {
        try {
            final Pair<String, UserData> userData = storage.getUserData(key, ref, realm);
            if (userData != null && userData.isPresent()) {
                final String version = userData.getLeft();
                final EntityTag entityTag = new EntityTag(version);
                final ResponseBuilder evaluatePreconditions = request.evaluatePreconditions(entityTag);
                if (evaluatePreconditions != null) {
                    return evaluatePreconditions.header(HttpHeaders.CONTENT_ENCODING, UTF_8).tag(entityTag).build();
                }
                final Either<String, FailedToLock> result = helper.unwrapWithPUTApi(() -> storage.update(key, ref, realm, user.getName(), data, version));

                if (result.isRight()) {
                    return Response.status(Status.PRECONDITION_FAILED).build();
                }
                final String newVersion = result.getLeft();

                if (newVersion == null) {
                    throw new WebApplicationException(Status.NOT_FOUND);
                }
                LOG.info("{} logged in and modified key {}", user, key);
                return Response.ok().tag(new EntityTag(newVersion)).header(HttpHeaders.CONTENT_ENCODING, UTF_8).build();
            }
            throw new WebApplicationException(key, HttpStatus.NOT_FOUND_404);
        } catch (RefNotFoundException e) {
            throw new WebApplicationException(HttpStatus.NOT_FOUND_404);
        }
    }

    @POST
    @Timed(name = "post_keyadmin_time")
    @Metered(name = "post_keyadmin_counter")
    @ExceptionMetered(name = "post_keyadmin_exception")
    @Path(JITSTATIC_KEYADMIN_REALM + "/{key : .+}")
    @Consumes({ APPLICATION_JSON, APPLICATION_XML })
    @Produces({ APPLICATION_JSON, APPLICATION_XML })
    public Response post(final @PathParam("key") String key, final @QueryParam("ref") String ref, final @Valid @NotNull UserData data,
            final @Auth Optional<User> remoteUserHolder) {
        if (!remoteUserHolder.isPresent()) {
            return helper.respondAuthenticationChallenge(GIT_REALM);
        }
        helper.checkRef(ref);
        final User user = remoteUserHolder.get();
        authorize(user);
        return addUser(key, ref, data, user, JITSTATIC_KEYADMIN_REALM);
    }

    private Response addUser(final String key, final String ref, final UserData data, final User user, String realm) {
        try {
            final Pair<String, UserData> userData = storage.getUserData(key, ref, realm);
            if (userData == null) {
                final String newVersion = helper.unwrapWithPOSTApi(() -> storage.addUser(key, ref, realm, user.getName(), data));
                if (newVersion == null) {
                    throw new WebApplicationException(Status.NOT_FOUND);
                }
                LOG.info("{} logged in and added key {}", user, key);
                return Response.ok().tag(new EntityTag(newVersion)).header(HttpHeaders.CONTENT_ENCODING, UTF_8).build();
            }
            throw new WebApplicationException(key + " already exist", Status.CONFLICT);
        } catch (RefNotFoundException e) {
            throw new WebApplicationException(Status.NOT_FOUND);
        }
    }

    @DELETE
    @Timed(name = "delete_keyadmin_time")
    @Metered(name = "delete_keyadmin_counter")
    @ExceptionMetered(name = "delete_keyadmin_exception")
    @Path(JITSTATIC_KEYADMIN_REALM + "/{key : .+}")
    @Consumes({ APPLICATION_JSON, APPLICATION_XML })
    @Produces({ APPLICATION_JSON, APPLICATION_XML })
    public Response delete(final @PathParam("key") String key, final @QueryParam("ref") String ref, final @Auth Optional<User> remoteUserHolder) {
        if (!remoteUserHolder.isPresent()) {
            return helper.respondAuthenticationChallenge(GIT_REALM);
        }
        helper.checkRef(ref);
        final User user = remoteUserHolder.get();
        authorize(user);
        return delete(key, ref, user, JITSTATIC_KEYADMIN_REALM);
    }

    private Response delete(final String key, final String ref, final User user, String realm) {
        try {
            final Pair<String, UserData> userData = storage.getUserData(key, ref, realm);
            if (userData != null) {
                storage.deleteUser(key, ref, realm, user.getName());
                return Response.ok().build();
            }
            throw new WebApplicationException(key, Status.NOT_FOUND);
        } catch (RefNotFoundException e) {
            throw new WebApplicationException(Status.NOT_FOUND);
        }
    }

    private void authorize(final User user) {
        if (gitAuthenticator.login(user.getName(), new Password(user.getPassword()), null) == null) {
            throw new WebApplicationException(Status.FORBIDDEN);
        }
    }

    @GET
    @Timed(name = "get_keyuser_time")
    @Metered(name = "get_keyuser_counter")
    @ExceptionMetered(name = "get_keyuser_exception")
    @Path(JITSTATIC_KEYUSER_REALM + "/{key : .+}")
    @Consumes({ APPLICATION_JSON, APPLICATION_XML })
    @Produces({ APPLICATION_JSON, APPLICATION_XML })
    public Response getUser(final @PathParam("key") String key, final @QueryParam("ref") String ref, final @Auth Optional<User> remoteUserHolder,
            final @Context HttpHeaders headers) {
        if (!remoteUserHolder.isPresent()) {
            return helper.respondAuthenticationChallenge(JITSTATIC_KEYADMIN_REALM);
        }
        helper.checkRef(ref);
        final User user = remoteUserHolder.get();
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
    public Response putUser(final @PathParam("key") String key, final @QueryParam("ref") String ref,
            final @Valid @NotNull UserData data,
            final @Auth Optional<User> remoteUserHolder, final @Context HttpHeaders headers, final @Context Request request) {
        if (!remoteUserHolder.isPresent()) {
            return helper.respondAuthenticationChallenge(JITSTATIC_KEYADMIN_REALM);
        }
        helper.checkRef(ref);
        final User user = remoteUserHolder.get();
        authorize(ref, user);
        return modifyUser(key, ref, data, request, user, JITSTATIC_KEYUSER_REALM);
    }

    @POST
    @Timed(name = "post_keyuser_time")
    @Metered(name = "post_keyuser_counter")
    @ExceptionMetered(name = "post_keyuser_exception")
    @Path(JITSTATIC_KEYUSER_REALM + "/{key : .+}")
    @Consumes({ APPLICATION_JSON, APPLICATION_XML })
    @Produces({ APPLICATION_JSON, APPLICATION_XML })
    public Response postUser(final @PathParam("key") String key, final @QueryParam("ref") String ref, final @Valid @NotNull UserData data,
            final @Auth Optional<User> remoteUserHolder) {
        if (!remoteUserHolder.isPresent()) {
            return helper.respondAuthenticationChallenge(JITSTATIC_KEYADMIN_REALM);
        }
        helper.checkRef(ref);
        final User user = remoteUserHolder.get();
        authorize(ref, user);
        return addUser(key, ref, data, user, JITSTATIC_KEYUSER_REALM);
    }

    @DELETE
    @Timed(name = "delete_keyuser_time")
    @Metered(name = "delete_keyuser_counter")
    @ExceptionMetered(name = "delete_keyuser_exception")
    @Path(JITSTATIC_KEYUSER_REALM + "/{key : .+}")
    @Consumes({ APPLICATION_JSON, APPLICATION_XML })
    @Produces({ APPLICATION_JSON, APPLICATION_XML })
    public Response deleteUser(final @PathParam("key") String key, final @QueryParam("ref") String ref, final @Auth Optional<User> remoteUserHolder) {
        if (!remoteUserHolder.isPresent()) {
            return helper.respondAuthenticationChallenge(GIT_REALM);
        }
        helper.checkRef(ref);
        final User user = remoteUserHolder.get();
        authorize(ref, user);
        return delete(key, ref, user, JITSTATIC_KEYUSER_REALM);
    }

    private void authorize(final String ref, final User user) {
        if (!adminKeyAuthenticator.authenticate(user, ref)) {
            throw new WebApplicationException(Status.FORBIDDEN);
        }
    }
}
