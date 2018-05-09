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

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
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

import io.dropwizard.auth.Auth;
import io.jitstatic.HeaderPair;
import io.jitstatic.StorageData;
import io.jitstatic.auth.AddKeyAuthenticator;
import io.jitstatic.auth.User;
import io.jitstatic.storage.Storage;
import io.jitstatic.storage.StoreInfo;

@Path("storage")
public class MapResource {

    private static final String UTF_8 = "utf-8";
    static final Logger LOG = LoggerFactory.getLogger(MapResource.class);
    private final Storage storage;
    private final AddKeyAuthenticator addKeyAuthenticator;
    private final APIHelper helper;

    public MapResource(final Storage storage, final AddKeyAuthenticator addKeyAuthenticator) {
        this.storage = Objects.requireNonNull(storage);
        this.addKeyAuthenticator = Objects.requireNonNull(addKeyAuthenticator);
        this.helper = new APIHelper(LOG);
    }

    @GET
    @Timed(name = "get_storage_time")
    @Metered(name = "get_storage_counter")
    @ExceptionMetered(name = "get_storage_exception")
    @Path("/{key : .+}")
    public Response get(final @PathParam("key") String key, final @QueryParam("ref") String ref, final @Auth Optional<User> user,
            final @Context Request request, final @Context HttpHeaders headers) {

        checkKey(key);

        helper.checkRef(ref);

        final Optional<StoreInfo> si = helper.unwrap(storage.get(key, ref));
        if (si == null || !si.isPresent()) {
            throw new WebApplicationException(Status.NOT_FOUND);
        }
        final StoreInfo storeInfo = si.get();
        final EntityTag tag = new EntityTag(storeInfo.getVersion());

        final Response noChange = helper.checkETag(headers, tag);
        if (noChange != null) {
            return noChange;
        }

        final StorageData data = storeInfo.getStorageData();
        final Set<User> allowedUsers = data.getUsers();
        if (allowedUsers.isEmpty()) {
            return buildResponse(storeInfo, tag, data);
        }

        if (!user.isPresent()) {
            LOG.info("Resource " + key + " needs a user");
            throw new WebApplicationException(Status.UNAUTHORIZED);
        }

        checkIfAllowed(key, user, allowedUsers);
        return buildResponse(storeInfo, tag, data);
    }

    private void checkKey(final String key) {
        if (key.endsWith("/")) {
            throw new WebApplicationException(Status.NOT_FOUND);
        }
    }

    private Response buildResponse(final StoreInfo storeInfo, final EntityTag tag, final StorageData data) {
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
    @Path("/{key : .+}")
    @Consumes({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML })
    public Response modifyKey(final @PathParam("key") String key, final @QueryParam("ref") String ref, final @Auth Optional<User> user,
            final @Valid @NotNull ModifyKeyData data, final @Context Request request, final @Context HttpHeaders headers) {
        // All resources without a user cannot be modified with this method. It has to
        // be done through directly changing the file in the git repository.
        if (!user.isPresent()) {
            throw new WebApplicationException(Status.UNAUTHORIZED);
        }

        checkKey(key);

        helper.checkHeaders(headers);

        helper.checkValidRef(ref);

        final Optional<StoreInfo> si = helper.unwrap(storage.get(key, ref));

        if (si == null || !si.isPresent()) {
            throw new WebApplicationException(Status.NOT_FOUND);
        }
        final StoreInfo storeInfo = si.get();
        final Set<User> allowedUsers = storeInfo.getStorageData().getUsers();
        if (allowedUsers.isEmpty()) {
            throw new WebApplicationException(Status.BAD_REQUEST);
        }

        checkIfAllowed(key, user, allowedUsers);

        final String currentVersion = storeInfo.getVersion();
        final ResponseBuilder response = request.evaluatePreconditions(new EntityTag(currentVersion));

        if (response != null) {
            return response.header(HttpHeaders.CONTENT_ENCODING, UTF_8).build();
        }

        final String newVersion = helper.unwrapWithPUTApi(
                storage.put(key, ref, data.getData(), currentVersion, data.getMessage(), data.getUserInfo(), data.getUserMail()));
        if (newVersion == null) {
            throw new WebApplicationException(Status.NOT_FOUND);
        }
        return Response.ok().tag(new EntityTag(newVersion)).header(HttpHeaders.CONTENT_ENCODING, UTF_8).build();

    }

    private void checkIfAllowed(final String key, final Optional<User> user, final Set<User> allowedUsers) {
        if (!allowedUsers.contains(user.get())) {
            LOG.info("Resource " + key + " is denied for user " + user.get());
            throw new WebApplicationException(Status.UNAUTHORIZED);
        }
    }

    @POST
    @Timed(name = "post_storage_time")
    @Metered(name = "post_storage_counter")
    @ExceptionMetered(name = "post_storage_exception")
    @Consumes({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML })
    public Response addKey(@Valid @NotNull final AddKeyData data, final @Auth Optional<User> user) {

        if (!user.isPresent()) {
            throw new WebApplicationException(Status.UNAUTHORIZED);
        }
        if (!addKeyAuthenticator.authenticate(user.get())) {
            LOG.info("Resource " + data.getKey() + " is denied for user " + user.get());
            throw new WebApplicationException(Status.UNAUTHORIZED);
        }

        if (data.getKey().endsWith("/")) {
            throw new WebApplicationException(Status.FORBIDDEN);
        }

        final Optional<StoreInfo> si = helper.unwrap(storage.get(data.getKey(), data.getBranch()));
        if (si != null && si.isPresent()) {
            throw new WebApplicationException(String.format("Key '%s' already exist in branch %s", data.getKey(), data.getBranch()),
                    Status.CONFLICT);
        }
        final StoreInfo result = helper.unwrapWithPOSTApi(storage.add(data.getKey(), data.getBranch(), data.getData(), data.getMetaData(),
                data.getMessage(), data.getUserInfo(), data.getUserMail()));
        return Response.ok().tag(new EntityTag(result.getVersion()))
                .header(HttpHeaders.CONTENT_TYPE, result.getStorageData().getContentType()).header(HttpHeaders.CONTENT_ENCODING, UTF_8)
                .entity(result.getData()).build();
    }
}
