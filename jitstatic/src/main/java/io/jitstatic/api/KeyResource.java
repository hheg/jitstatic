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

import static io.jitstatic.JitStaticConstants.JITSTATIC_KEYADMIN_REALM;
import static io.jitstatic.JitStaticConstants.JITSTATIC_KEYUSER_REALM;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletRequest;
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
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.Response.Status;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.annotation.ExceptionMetered;
import com.codahale.metrics.annotation.Metered;
import com.codahale.metrics.annotation.Timed;
import com.spencerwi.either.Either;

import io.dropwizard.auth.Auth;
import io.jitstatic.CommitMetaData;
import io.jitstatic.HeaderPair;
import io.jitstatic.JitStaticConstants;
import io.jitstatic.MetaData;
import io.jitstatic.Role;
import io.jitstatic.auth.KeyAdminAuthenticator;
import io.jitstatic.auth.User;
import io.jitstatic.auth.UserData;
import io.jitstatic.hosted.FailedToLock;
import io.jitstatic.hosted.StoreInfo;
import io.jitstatic.storage.Storage;
import io.jitstatic.utils.Pair;
import io.jitstatic.utils.WrappingAPIException;

@Path("storage")
public class KeyResource {
    private static final String RESOURCE_IS_DENIED_FOR_USER = "Resource {} is denied for user {}";
    private static final String X_JITSTATIC = "X-jitstatic";
    private static final String X_JITSTATIC_MAIL = X_JITSTATIC + "-mail";
    private static final String X_JITSTATIC_MESSAGE = X_JITSTATIC + "-message";
    private static final String X_JITSTATIC_NAME = X_JITSTATIC + "-name";
    private static final String UTF_8 = "utf-8";
    private static final Logger LOG = LoggerFactory.getLogger(KeyResource.class);
    private final Storage storage;
    private final KeyAdminAuthenticator addKeyAuthenticator;
    private final APIHelper helper;

    public KeyResource(final Storage storage, final KeyAdminAuthenticator addKeyAuthenticator) {
        this.storage = Objects.requireNonNull(storage);
        this.addKeyAuthenticator = Objects.requireNonNull(addKeyAuthenticator);
        this.helper = new APIHelper(LOG);
    }

    @GET
    @Timed(name = "get_storage_time")
    @Metered(name = "get_storage_counter")
    @ExceptionMetered(name = "get_storage_exception")
    @Path("{key : .+}")
    public Response get(final @PathParam("key") String key, final @QueryParam("ref") String ref, final @Auth Optional<User> user,
            final @Context HttpHeaders headers) {

        helper.checkRef(ref);

        final StoreInfo storeInfo = helper.checkIfKeyExist(key, ref, storage);
        final EntityTag tag = new EntityTag(storeInfo.getVersion());
        final Response noChange = helper.checkETag(headers, tag);

        final MetaData data = storeInfo.getStorageData();
        final Set<User> allowedUsers = data.getUsers();
        final Set<Role> roles = data.getRoles();
        if (allowedUsers.isEmpty() && (roles == null || roles.isEmpty())) {
            if (noChange != null) {
                return noChange;
            }
            return buildResponse(storeInfo, tag, data);
        }

        if (!user.isPresent()) {
            LOG.info("Resource {} needs a user", key);
            return helper.respondAuthenticationChallenge(JITSTATIC_KEYUSER_REALM);
        }

        checkIfAllowed(key, user.get(), allowedUsers, ref, storeInfo.getStorageData().getRoles());
        if (noChange != null) {
            return noChange;
        }
        return buildResponse(storeInfo, tag, data);
    }

    @GET
    @Produces({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML })
    public Response getRootList(final @QueryParam("ref") String ref, @QueryParam("recursive") boolean recursive, @QueryParam("light") final boolean light,
            final @Auth Optional<User> user) {
        return getList("/", ref, recursive, light, user);
    }

    @GET
    @Timed(name = "get_list_time")
    @Metered(name = "get_list_counter")
    @ExceptionMetered(name = "get_list_exception")
    @Path("{key : .+/}")
    @Produces({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML })
    public Response getList(final @PathParam("key") String key, final @QueryParam("ref") String ref, @QueryParam("recursive") boolean recursive,
            @QueryParam("light") final boolean light, final @Auth Optional<User> user) {
        helper.checkRef(ref);
        final List<Pair<String, StoreInfo>> list = storage.getListForRef(List.of(Pair.of(key, recursive)), ref, user);
        if (list.isEmpty()) {
            return Response.status(Status.NOT_FOUND).build();
        }
        return Response.ok(list.stream().map(p -> light ? new KeyData(p.getLeft(), p.getRight()) : new KeyData(p)).collect(Collectors.toList())).build();
    }

    private void checkKey(final String key) {
        if (key.endsWith("/")) {
            throw new WebApplicationException(Status.NOT_FOUND);
        }
    }

    private Response buildResponse(final StoreInfo storeInfo, final EntityTag tag, final MetaData data) {
        final ResponseBuilder responseBuilder = Response.ok(storeInfo.getData()).header(HttpHeaders.CONTENT_TYPE, data.getContentType())
                .header(HttpHeaders.CONTENT_ENCODING, UTF_8).tag(tag);
        final List<HeaderPair> headers = data.getHeaders();
        if (headers != null) {
            for (HeaderPair headerPair : headers) {
                responseBuilder.header(headerPair.getHeader(), headerPair.getValue());
            }
        }
        return responseBuilder.build();
    }

    @PUT
    @Timed(name = "put_storage_time")
    @Metered(name = "put_storage_counter")
    @ExceptionMetered(name = "put_storage_exception")
    @Path("{key : .+}")
    @Consumes({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML })
    public Response modifyKey(final @PathParam("key") String key, final @QueryParam("ref") String ref, final @Auth Optional<User> userholder,
            final @Valid @NotNull ModifyKeyData data, final @Context Request request, final @Context HttpHeaders headers) {
        // All resources without a user cannot be modified with this method. It has to
        // be done through directly changing the file in the Git repository.
        if (!userholder.isPresent()) {
            return helper.respondAuthenticationChallenge(JITSTATIC_KEYUSER_REALM);
        }
        final User user = userholder.get();

        checkKey(key);

        helper.checkHeaders(headers);

        helper.checkValidRef(ref);

        final StoreInfo storeInfo = helper.checkIfKeyExist(key, ref, storage);
        final Set<User> allowedUsers = storeInfo.getStorageData().getUsers();
        final Set<Role> roles = storeInfo.getStorageData().getRoles();
        boolean isAuthenticated = addKeyAuthenticator.authenticate(user, ref);
        if (allowedUsers.isEmpty() && (roles == null || roles.isEmpty()) && !isAuthenticated) {
            throw new WebApplicationException(Status.BAD_REQUEST);
        }

        if (!isAuthenticated && !allowedUsers.contains(user) && !isKeyUserAllowed(user, ref, roles)) {
            LOG.info(RESOURCE_IS_DENIED_FOR_USER, key, user);
            throw new WebApplicationException(Status.FORBIDDEN);
        }

        final String currentVersion = storeInfo.getVersion();
        final EntityTag entityTag = new EntityTag(currentVersion);
        final ResponseBuilder response = request.evaluatePreconditions(entityTag);

        if (response != null) {
            return response.header(HttpHeaders.CONTENT_ENCODING, UTF_8).tag(entityTag).build();
        }

        final Either<String, FailedToLock> result = helper.unwrapWithPUTApi(
                () -> storage.put(key, ref, data.getData(), currentVersion, new CommitMetaData(data.getUserInfo(), data.getUserMail(), data.getMessage())));

        if (result == null) {
            throw new WebApplicationException(Status.NOT_FOUND);
        }

        if (result.isRight()) {
            return Response.status(Status.PRECONDITION_FAILED).tag(entityTag).build();
        }
        final String newVersion = result.getLeft();

        if (newVersion == null) {
            throw new WebApplicationException(Status.NOT_FOUND);
        }
        return Response.ok().tag(new EntityTag(newVersion)).header(HttpHeaders.CONTENT_ENCODING, UTF_8).build();
    }

    void checkIfAllowed(final String key, final User user, final Set<User> allowedUsers, final String ref, final Set<Role> keyRoles) {
        if (!allowedUsers.contains(user) && !isKeyUserAllowed(user, ref, keyRoles) && !addKeyAuthenticator.authenticate(user, ref)) {
            LOG.info(RESOURCE_IS_DENIED_FOR_USER, key, user);
            throw new WebApplicationException(Status.FORBIDDEN);
        }
    }

    boolean isKeyUserAllowed(final User user, final String ref, Set<Role> keyRoles) {
        keyRoles = keyRoles == null ? Set.of() : keyRoles;
        final UserData userData = storage.getUser(user.getName(), ref, JitStaticConstants.JITSTATIC_KEYUSER_REALM);
        if (userData == null) {
            return false;
        }
        final Set<Role> userRoles = userData.getRoles();
        return (!keyRoles.stream().noneMatch(userRoles::contains) && userData.getBasicPassword().equals(user.getPassword()));
    }

    // TODO addKey should use ref like all other endpoints,
    // TODO addKey should use the full endpoint key and post it.

    @POST
    @Timed(name = "post_storage_time")
    @Metered(name = "post_storage_counter")
    @ExceptionMetered(name = "post_storage_exception")
    @Consumes({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML })
    @Produces({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML, MediaType.TEXT_PLAIN })
    public Response addKey(@Valid @NotNull final AddKeyData data, final @Auth Optional<User> userHolder) {

        if (!userHolder.isPresent()) {
            return helper.respondAuthenticationChallenge(JITSTATIC_KEYADMIN_REALM);
        }
        final String key = data.getKey();
        final String ref = data.getBranch();
        helper.checkValidRef(ref);

        if (key.endsWith("/")) {
            throw new WebApplicationException(key, Status.FORBIDDEN);
        }

        final User user = userHolder.get();
        if (!addKeyAuthenticator.authenticate(user, ref)) {
            final UserData userData = storage.getUser(user.getName(), ref, JITSTATIC_KEYUSER_REALM);
            if (userData == null || !userData.getBasicPassword().equals(user.getPassword())) {
                LOG.info(RESOURCE_IS_DENIED_FOR_USER, key, user);
                throw new WebApplicationException(Status.FORBIDDEN);
            }
        }

        final Optional<StoreInfo> storeInfo = helper.unwrap(() -> storage.getKey(key, ref));

        if (storeInfo.isPresent()) {
            throw new WebApplicationException(key + " already exist", Status.CONFLICT);
        }

        final String version = helper.unwrapWithPOSTApi(() -> storage.addKey(key, ref, data.getData(), data.getMetaData(),
                new CommitMetaData(data.getUserInfo(), data.getUserMail(), data.getMessage())));
        return Response.ok().tag(new EntityTag(version)).header(HttpHeaders.CONTENT_ENCODING, UTF_8).build();
    }

    @DELETE
    @Path("{key : .+}")
    @Timed(name = "delete_storage_time")
    @Metered(name = "delete_storage_counter")
    @ExceptionMetered(name = "delete_storage_exception")
    public Response delete(final @PathParam("key") String key, final @QueryParam("ref") String ref, final @Auth Optional<User> userHolder,
            final @Context HttpServletRequest request, final @Context HttpHeaders headers) {
        if (!userHolder.isPresent()) {
            return helper.respondAuthenticationChallenge(JITSTATIC_KEYUSER_REALM);
        }

        final String userHeader = notEmpty(headers.getHeaderString(X_JITSTATIC_NAME), X_JITSTATIC_NAME);
        final String message = notEmpty(headers.getHeaderString(X_JITSTATIC_MESSAGE), X_JITSTATIC_MESSAGE);
        final String userMail = notEmpty(headers.getHeaderString(X_JITSTATIC_MAIL), X_JITSTATIC_MAIL);

        checkKey(key);

        helper.checkValidRef(ref);
        final User user = userHolder.get();
        final StoreInfo storeInfo = helper.checkIfKeyExist(key, ref, storage);
        final Set<User> allowedUsers = storeInfo.getStorageData().getUsers();

        if (!allowedUsers.contains(user) && !addKeyAuthenticator.authenticate(user, ref)
                && !isKeyUserAllowed(user, ref, storeInfo.getStorageData().getRoles())) {
            final Set<Role> roles = storeInfo.getStorageData().getRoles();
            if (allowedUsers.isEmpty() && (roles == null || roles.isEmpty())) {
                throw new WebApplicationException(Status.BAD_REQUEST);
            }
            LOG.info(RESOURCE_IS_DENIED_FOR_USER, key, user);
            throw new WebApplicationException(Status.FORBIDDEN);
        }

        try {
            storage.delete(key, ref, new CommitMetaData(userHeader, userMail, message));
        } catch (final WrappingAPIException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof UnsupportedOperationException) {
                throw new WebApplicationException(key, Status.METHOD_NOT_ALLOWED);
            }
        }

        return Response.ok().build();
    }

    private String notEmpty(final String headerString, final String headerName) {
        if (headerString == null || headerString.isEmpty()) {
            throw new WebApplicationException("Missing " + headerName, Status.BAD_REQUEST);
        }
        return headerString;
    }
}
