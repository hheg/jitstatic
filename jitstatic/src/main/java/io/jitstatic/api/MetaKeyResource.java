package io.jitstatic.api;

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

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
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
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.EntityTag;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.SecurityContext;

import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.annotation.ExceptionMetered;
import com.codahale.metrics.annotation.Metered;
import com.codahale.metrics.annotation.Timed;
import com.spencerwi.either.Either;

import io.dropwizard.auth.Auth;
import io.dropwizard.validation.Validated;
import io.jitstatic.CommitMetaData;
import io.jitstatic.MetaData;
import io.jitstatic.auth.User;
import io.jitstatic.hosted.FailedToLock;
import io.jitstatic.injection.configuration.JitstaticConfiguration;
import io.jitstatic.storage.Storage;

@Path("metakey")
public class MetaKeyResource {

    private final String defaultRef;
    private static final String UTF_8 = "utf-8";
    private static final Logger LOG = LoggerFactory.getLogger(MetaKeyResource.class);
    private final Storage storage;
    private final APIHelper helper;
    
    @Inject
    public MetaKeyResource(final Storage storage, final JitstaticConfiguration config) {
        this(storage,config.getHostedFactory().getBranch());
    }

    public MetaKeyResource(final Storage storage, final String defaultBranch) {
        this.storage = Objects.requireNonNull(storage);
        this.helper = new APIHelper(LOG);
        this.defaultRef = Objects.requireNonNull(defaultBranch);
    }

    @GET
    @Timed(name = "get_metakey_time")
    @Metered(name = "get_metakey_counter")
    @ExceptionMetered(name = "get_metakey_exception")
    @Path("/{key : .+}")
    @Produces({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML })
    public void getMetaKey(@Suspended AsyncResponse asyncResponse, final @PathParam("key") String key, final @QueryParam("ref") String askedRef,
            final @Auth User user, final @Context Request request, final @Context HttpHeaders headers, @Context SecurityContext context,
            @Context ExecutorService executor) {
        APIHelper.checkRef(askedRef);
        final String ref = APIHelper.setToDefaultRefIfNull(askedRef, defaultRef);
        try {
            storage.getMetaKey(key, ref)
                    .thenApplyAsync(metaDataInfo -> metaDataInfo.orElseThrow(() -> new WebApplicationException(Status.NOT_FOUND)), executor)
                    .thenApplyAsync(metaDataInfo -> {
                        final MetaData metaData = metaDataInfo.getLeft();
                        helper.checkWritePermission(key, user, context, ref, metaData);
                        final EntityTag tag = new EntityTag(metaDataInfo.getRight());
                        final ResponseBuilder noChange = request.evaluatePreconditions(tag);
                        if (noChange != null) {
                            return noChange.build();
                        }
                        LOG.info("{} logged in and accessed key {} in {}", user, key, ref);
                        return Response.ok(metaData)
                                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)
                                .header(HttpHeaders.CONTENT_ENCODING, UTF_8)
                                .tag(tag)
                                .build();
                    }, executor).exceptionally(helper::execptionHandler).thenAcceptAsync(asyncResponse::resume, executor);
        } catch (RefNotFoundException e) {
            throw new WebApplicationException(e.getMessage(), Status.BAD_REQUEST);
        }
    }

    @PUT
    @Timed(name = "put_metakey_time")
    @Metered(name = "put_metakey_counter")
    @ExceptionMetered(name = "put_metakey_exception")
    @Path("/{key : .+}")
    @Consumes({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML })
    @Produces({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML })
    public void updateMetaKey(@Suspended AsyncResponse asyncResponse, final @PathParam("key") String key, final @QueryParam("ref") String askedRef,
            final @Auth User user, final @Validated @Valid @NotNull ModifyMetaKeyData data, final @Context Request request,
            final @Context HttpServletRequest httpRequest, final @Context HttpHeaders headers, final @Context SecurityContext context,
            @Context ExecutorService executor) {
        final String ref = APIHelper.setToDefaultRefIfNull(askedRef, defaultRef);
        APIHelper.checkHeaders(headers);
        APIHelper.checkRef(ref);
        try {
            storage.getMetaKey(key, ref)
                    .thenApplyAsync(metaKeyData -> {
                        if (!metaKeyData.isPresent()) {
                            throw new WebApplicationException(key, Status.NOT_FOUND);
                        }
                        helper.checkWritePermission(key, user, context, ref, metaKeyData.getLeft());
                        final String currentVersion = metaKeyData.getRight();

                        final EntityTag tag = new EntityTag(currentVersion);
                        final ResponseBuilder noChangeBuilder = request.evaluatePreconditions(tag);

                        if (noChangeBuilder != null) {
                            throw new WebApplicationException(noChangeBuilder.header(HttpHeaders.CONTENT_ENCODING, UTF_8).tag(tag).build());
                        }

                        return updateMetaData(key, data, httpRequest, user, ref, currentVersion);
                    }, executor)
                    .thenComposeAsync(c -> c, executor)
                    .thenApplyAsync(result -> {
                        if (result.isRight()) {
                            throw new WebApplicationException(Status.PRECONDITION_FAILED);
                        }
                        final String newVersion = result.getLeft();
                        if (newVersion == null) {
                            throw new WebApplicationException(Status.NOT_FOUND);
                        }
                        LOG.info("{} logged in and modified key {} in {}", user, key, ref);
                        return Response.ok().tag(new EntityTag(newVersion)).header(HttpHeaders.CONTENT_ENCODING, UTF_8).build();
                    }, executor).exceptionally(helper::exceptionHandlerPUTAPI).thenAcceptAsync(asyncResponse::resume, executor);
        } catch (RefNotFoundException e) {
            throw new WebApplicationException(e.getMessage(), Status.BAD_REQUEST);
        }
    }

    private CompletableFuture<Either<String, FailedToLock>> updateMetaData(final String key, final ModifyMetaKeyData data, final HttpServletRequest httpRequest,
            final User user, final String ref, final String currentVersion) {
        try {
            return storage.updateMetaData(key, ref, data.getMetaData(), currentVersion, new CommitMetaData(data.getUserInfo(), data.getUserMail(), data
                    .getMessage(), user.getName(), APIHelper.compileUserOrigin(user, httpRequest)));
        } catch (RefNotFoundException e) {
            throw new WebApplicationException(e.getMessage(), Status.BAD_REQUEST);
        }
    }

}
