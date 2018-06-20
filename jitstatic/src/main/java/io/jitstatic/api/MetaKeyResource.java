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

import java.util.Optional;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
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
import io.jitstatic.auth.AddKeyAuthenticator;
import io.jitstatic.auth.User;
import io.jitstatic.hosted.FailedToLock;
import io.jitstatic.hosted.StoreInfo;
import io.jitstatic.storage.Storage;

@Path("metakey")
public class MetaKeyResource {

    private static final String UTF_8 = "utf-8";
    private static final Logger LOG = LoggerFactory.getLogger(MapResource.class);
    private final Storage storage;
    private final AddKeyAuthenticator addKeyAuthenticator;
    private final APIHelper helper;

    public MetaKeyResource(final Storage storage, final AddKeyAuthenticator addKeyAuthenticator) {
        this.addKeyAuthenticator = addKeyAuthenticator;
        this.storage = storage;
        this.helper = new APIHelper(LOG);
    }

    @GET
    @Timed(name = "get_metakey_time")
    @Metered(name = "get_metakey_counter")
    @ExceptionMetered(name = "get_metakey_exception")
    @Path("/{key : .+}")
    @Produces({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML })
    public Response get(final @PathParam("key") String key, final @QueryParam("ref") String ref, final @Auth Optional<User> user,
            final @Context Request request, final @Context HttpHeaders headers) {
        if (!user.isPresent()) {
            throw new WebApplicationException(Status.UNAUTHORIZED);
        }

        if (!addKeyAuthenticator.authenticate(user.get())) {
            throw new WebApplicationException(Status.UNAUTHORIZED);
        }

        helper.checkRef(ref);

        final Optional<StoreInfo> si = helper.unwrap(() -> storage.getKey(key, ref));
        if (si == null || !si.isPresent()) {
            throw new WebApplicationException(Status.NOT_FOUND);
        }
        final StoreInfo storeInfo = si.get();
        final EntityTag tag = new EntityTag(storeInfo.getMetaDataVersion());
        final Response noChange = helper.checkETag(headers, tag);
        if (noChange != null) {
            return noChange;
        }

        return Response.ok(storeInfo.getStorageData()).header(HttpHeaders.CONTENT_ENCODING, UTF_8).tag(tag).build();
    }

    @PUT
    @Timed(name = "put_metakey_time")
    @Metered(name = "put_metakey_counter")
    @ExceptionMetered(name = "put_metakey_exception")
    @Path("/{key : .+}")
    @Consumes({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML })
    @Produces({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML })
    public Response modifyUserKey(final @PathParam("key") String key, final @QueryParam("ref") String ref, final @Auth Optional<User> user,
            final @Valid @NotNull ModifyMetaKeyData data, final @Context Request request, final @Context HttpHeaders headers) {
        if (!user.isPresent()) {
            throw new WebApplicationException(Status.UNAUTHORIZED);
        }

        if (!addKeyAuthenticator.authenticate(user.get())) {
            throw new WebApplicationException(Status.UNAUTHORIZED);
        }

        helper.checkHeaders(headers);

        helper.checkRef(ref);

        final Optional<StoreInfo> si = helper.unwrap(() -> storage.getKey(key, ref));
        if (si == null || !si.isPresent()) {
            throw new WebApplicationException(Status.NOT_FOUND);
        }
        final StoreInfo storeInfo = si.get();
        final String currentVersion = storeInfo.getMetaDataVersion();
        final EntityTag tag = new EntityTag(currentVersion);
        final ResponseBuilder noChangeBuilder = request.evaluatePreconditions(tag);

        if (noChangeBuilder != null) {
            return noChangeBuilder.tag(tag).build();
        }

        final Either<String, FailedToLock> result = helper.unwrapWithPUTApi(() -> storage.putMetaData(key, ref, data.getMetaData(),
                currentVersion, data.getMessage(), data.getUserInfo(), data.getUserMail()));

        if (result.isRight()) {
            return Response.status(Status.PRECONDITION_FAILED).tag(tag).build();
        }
        final String newVersion = result.getLeft();
        if (newVersion == null) {
            throw new WebApplicationException(Status.NOT_FOUND);
        }
        return Response.ok().tag(new EntityTag(newVersion)).header(HttpHeaders.CONTENT_ENCODING, UTF_8).build();
    }

}
