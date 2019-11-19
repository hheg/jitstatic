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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.validation.Valid;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;

import org.hibernate.validator.constraints.NotEmpty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.annotation.ExceptionMetered;
import com.codahale.metrics.annotation.Metered;
import com.codahale.metrics.annotation.Timed;

import io.dropwizard.auth.Auth;
import io.dropwizard.validation.Validated;
import io.jitstatic.JitStaticConstants;
import io.jitstatic.MetaData;
import io.jitstatic.Role;
import io.jitstatic.auth.User;
import io.jitstatic.injection.configuration.JitstaticConfiguration;
import io.jitstatic.storage.Storage;
import io.jitstatic.utils.Pair;

@Path("bulk")
public class BulkResource {

    private final String defaultRef;
    private static final Logger LOG = LoggerFactory.getLogger(BulkResource.class);
    private final Storage storage;
    
    @Inject
    public BulkResource(final Storage storage, final JitstaticConfiguration config) {
        this(storage, config.getHostedFactory().getBranch());
    }

    public BulkResource(final Storage storage, String defaultBranch) {
        this.storage = Objects.requireNonNull(storage);
        this.defaultRef = Objects.requireNonNull(defaultBranch);
    }

    @POST
    @Path("fetch")
    @Timed(name = "fetch_storage_time")
    @Metered(name = "fetch_storage_counter")
    @ExceptionMetered(name = "fetch_storage_exception")
    @Consumes({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    public void fetch(@Suspended AsyncResponse asyncResponse, final @Validated @NotEmpty @Valid List<BulkSearch> searches, final @Auth User user,
            @Context SecurityContext context, @Context ExecutorService executor) {
        CompletableFuture.supplyAsync(() -> searches.stream()
                .filter(bs -> JitStaticConstants.isRef(bs.getRef()))
                .map(bs -> Pair.of(bs.getPaths().stream()
                        .map(sp -> Pair.of(sp.getPath(), sp.isRecursively()))
                        .collect(Collectors.toList()), bs.getRef()))
                .collect(Collectors.toList()), executor)
                .thenCompose(storage::getList)
                .thenApplyAsync(l -> l.stream()
                        .map(p -> p.getKey().stream()
                                .filter(data -> {
                                    final String ref = APIHelper.setToDefaultRefIfNull(p.getRight(), defaultRef);
                                    final MetaData storageData = data.getRight().getMetaData();
                                    final Set<Role> readRoles = storageData.getRead();
                                    if (readRoles.isEmpty() || APIHelper.isUserInRole(context, readRoles)) {
                                        LOG.info("{} logged in and accessed key {} in {}", user, data.getLeft(), ref);
                                        return true;
                                    }
                                    return false;
                                }).map(ps -> new SearchResult(ps, p.getRight())))
                        .flatMap(Function.identity()).collect(Collectors.toList()))
                .thenApplyAsync(result -> Response.ok(new SearchResultWrapper(result)).build()).exceptionally(t -> {
                    LOG.error("Failed to fetch", t);
                    return Response.serverError().build();
                }).thenAcceptAsync(asyncResponse::resume, executor);
    }
}
