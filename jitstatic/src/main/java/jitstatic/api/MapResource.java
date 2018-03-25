package jitstatic.api;

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

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

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

import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.lib.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.annotation.ExceptionMetered;
import com.codahale.metrics.annotation.Metered;
import com.codahale.metrics.annotation.Timed;

import io.dropwizard.auth.Auth;
import jitstatic.StorageData;
import jitstatic.auth.AddKeyAuthenticator;
import jitstatic.auth.User;
import jitstatic.storage.KeyAlreadyExist;
import jitstatic.storage.Storage;
import jitstatic.storage.StoreInfo;
import jitstatic.utils.VersionIsNotSameException;
import jitstatic.utils.WrappingAPIException;

@Path("storage")
public class MapResource {

    private static final String UTF_8 = "utf-8";
    private static final Logger LOG = LoggerFactory.getLogger(MapResource.class);
    private final Storage storage;
    private final AddKeyAuthenticator addKeyAuthenticator;

    public MapResource(final Storage storage, final AddKeyAuthenticator addKeyAuthenticator) {
        this.storage = Objects.requireNonNull(storage);
        this.addKeyAuthenticator = Objects.requireNonNull(addKeyAuthenticator);
    }

    @GET
    @Timed(name = "get_storage_time")
    @Metered(name = "get_storage_counter")
    @ExceptionMetered(name = "get_storage_exception")
    @Path("/{key : .+}")
    public Response get(final @PathParam("key") String key, final @QueryParam("ref") String ref, final @Auth Optional<User> user,
            final @Context Request request) {

        checkRef(ref);
        
        final Optional<StoreInfo> si = unwrap(storage.get(key, ref));
        if (!si.isPresent()) {
            throw new WebApplicationException(Status.NOT_FOUND);
        }
        final StoreInfo storeInfo = si.get();
        final EntityTag tag = new EntityTag(storeInfo.getVersion());
        final ResponseBuilder noChangeBuilder = request.evaluatePreconditions(tag);

        if (noChangeBuilder != null) {
            return noChangeBuilder.tag(tag).build();
        }

        final StorageData data = storeInfo.getStorageData();
        final Set<User> allowedUsers = data.getUsers();
        if (allowedUsers.isEmpty()) {
            return Response.ok(storeInfo.getData()).header(HttpHeaders.CONTENT_TYPE, data.getContentType())
                    .header(HttpHeaders.CONTENT_ENCODING, UTF_8).tag(tag).build();
        }

        if (!user.isPresent()) {
            LOG.info("Resource " + key + " needs a user");
            throw new WebApplicationException(Status.UNAUTHORIZED);
        }

        if (!allowedUsers.contains(user.get())) {
            LOG.info("Resource " + key + "is denied for user " + user);
            throw new WebApplicationException(Status.UNAUTHORIZED);
        }
        return Response.ok(storeInfo.getData()).header(HttpHeaders.CONTENT_TYPE, data.getContentType()).header(HttpHeaders.CONTENT_ENCODING, UTF_8)
                .tag(tag).build();
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
        checkHeaders(headers);

        checkValidRef(ref);

        final Optional<StoreInfo> si = unwrap(storage.get(key, ref));

        if (!si.isPresent()) {
            throw new WebApplicationException(Status.NOT_FOUND);
        }
        final StoreInfo storeInfo = si.get();
        final Set<User> allowedUsers = storeInfo.getStorageData().getUsers();
        if (allowedUsers.isEmpty()) {
            throw new WebApplicationException(Status.BAD_REQUEST);
        }

        if (!allowedUsers.contains(user.get())) {
            LOG.info("Resource " + key + "is denied for user " + user);
            throw new WebApplicationException(Status.UNAUTHORIZED);
        }

        final String currentVersion = storeInfo.getVersion();
        final ResponseBuilder response = request.evaluatePreconditions(new EntityTag(currentVersion));

        // TODO this should be idempotent. So if version is equals, answer 200.
        if (response == null) {
            final String newVersion = unwrapWithPUTApi(
                    storage.put(data.getData(), currentVersion, data.getMessage(), data.getUserInfo(), data.getUserMail(), key, ref));
            if (newVersion == null) {
                throw new WebApplicationException(Status.NOT_FOUND);
            }
            return Response.ok().tag(new EntityTag(newVersion)).header(HttpHeaders.CONTENT_ENCODING, UTF_8).build();
        }
        return response.header(HttpHeaders.CONTENT_ENCODING, UTF_8).build();
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
            LOG.info("Resource " + data.getKey() + "is denied for user " + user);
            throw new WebApplicationException(Status.UNAUTHORIZED);
        }
        final Optional<StoreInfo> si = unwrap(storage.get(data.getKey(), data.getBranch()));
        if (si.isPresent()) {
            throw new WebApplicationException(Status.CONFLICT);
        }
        final StoreInfo result = unwrapWithPOSTApi(storage.add(data.getKey(), data.getBranch(), data.getData(), data.getMetaData(),
                data.getMessage(), data.getUserInfo(), data.getUserMail()));
        return Response.ok().tag(new EntityTag(result.getVersion()))
                .header(HttpHeaders.CONTENT_TYPE, result.getStorageData().getContentType()).header(HttpHeaders.CONTENT_ENCODING, UTF_8)
                .entity(result.getData()).build();
    }

    private StoreInfo unwrapWithPOSTApi(final Future<StoreInfo> add) {
        try {
            return add.get();
        } catch (final InterruptedException e) {
            LOG.error("Instruction was interrupted", e);
            throw new WebApplicationException(Status.REQUEST_TIMEOUT);
        } catch (final ExecutionException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof WrappingAPIException) {
                final Exception apiException = (Exception) cause.getCause();
                if (apiException instanceof KeyAlreadyExist) {
                    throw new WebApplicationException(Status.CONFLICT);
                } else if (apiException instanceof RefNotFoundException) {
                    // Error message here means that the branch is not found.
                    throw new WebApplicationException(Status.NOT_FOUND);
                } else if (apiException instanceof IOException) {
                    throw new WebApplicationException("Data is malformed", 422);
                }
            }
            LOG.error("Error while unwrapping future", e);
            throw new WebApplicationException(Status.INTERNAL_SERVER_ERROR);
        }
    }

    private void checkHeaders(final HttpHeaders headers) {
        final List<String> header = headers.getRequestHeader(HttpHeaders.IF_MATCH);
        if (header == null || header.isEmpty()) {
            throw new WebApplicationException(Status.BAD_REQUEST);
        }
        boolean isValid = false; 
        for(String headerValue : header) {
            isValid |= !headerValue.isEmpty();
        }
        if(!isValid) {
            throw new WebApplicationException(Status.BAD_REQUEST);
        }
    }

    private void checkValidRef(final String ref) {
        if (ref != null) {
            checkRef(ref);
            if (ref.startsWith(Constants.R_TAGS)) {
                throw new WebApplicationException(Status.FORBIDDEN);
            }
        }
    }

    private static <T> T unwrapWithPUTApi(final Future<T> future) {
        try {
            return future.get();
        } catch (final InterruptedException e) {
            LOG.error("Instruction was interrupted", e);
            throw new WebApplicationException(Status.REQUEST_TIMEOUT);
        } catch (final ExecutionException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof WrappingAPIException) {
                final Exception apiException = (Exception) cause.getCause();
                if (apiException instanceof UnsupportedOperationException) {
                    throw new WebApplicationException(Status.NOT_FOUND);
                }
                if (apiException instanceof RefNotFoundException) {
                    return null;
                }
                if (apiException instanceof VersionIsNotSameException) {
                    throw new WebApplicationException(apiException.getMessage(), Status.CONFLICT);
                }
            }
            LOG.error("Error while unwrapping future", e);
            throw new WebApplicationException(Status.INTERNAL_SERVER_ERROR);
        }
    }

    private static <T> Optional<T> unwrap(final Future<Optional<T>> future) {        
        try {            
            return future.get();
        } catch (final InterruptedException | ExecutionException e) {
            LOG.error("Error while unwrapping future", e);
            return Optional.empty();
        }
    }

    private void checkRef(final String ref) {
        if (ref != null) {
            if (!(ref.startsWith(Constants.R_HEADS) ^ ref.startsWith(Constants.R_TAGS))) {
                throw new WebApplicationException(Status.NOT_FOUND);
            }
        }
    }
}
