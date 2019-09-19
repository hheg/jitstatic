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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import org.hibernate.validator.constraints.NotEmpty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.annotation.ExceptionMetered;
import com.codahale.metrics.annotation.Metered;
import com.codahale.metrics.annotation.Timed;

import io.dropwizard.auth.Auth;
import io.jitstatic.MetaData;
import io.jitstatic.Role;
import io.jitstatic.auth.KeyAdminAuthenticator;
import io.jitstatic.auth.User;
import io.jitstatic.hosted.StoreInfo;
import io.jitstatic.storage.HashService;
import io.jitstatic.storage.Storage;
import io.jitstatic.utils.Pair;

@Path("bulk")
public class BulkResource {

    private final String defaultRef;
    private static final Logger LOG = LoggerFactory.getLogger(BulkResource.class);
    private final Storage storage;
    private final KeyAdminAuthenticator addKeyAuthenticator;
    private final HashService hashService;
    @Inject
    private ExecutorService executor;

    public BulkResource(final Storage storage, KeyAdminAuthenticator adminKeyAuthenticator, String defaultBranch, HashService hashService) {
        this.storage = Objects.requireNonNull(storage);
        this.addKeyAuthenticator = Objects.requireNonNull(adminKeyAuthenticator);
        this.defaultRef = Objects.requireNonNull(defaultBranch);
        this.hashService = Objects.requireNonNull(hashService);
    }

    @POST
    @Path("fetch")
    @Timed(name = "fetch_storage_time")
    @Metered(name = "fetch_storage_counter")
    @ExceptionMetered(name = "fetch_storage_exception")
    @Consumes({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    public void fetch(@Suspended AsyncResponse asyncResponse,
            final @NotNull @NotEmpty @Valid List<BulkSearch> searches,
            final @Auth Optional<User> userHolder) {
        CompletableFuture.supplyAsync(() -> searches.stream()
                .filter(bs -> APIHelper.isRef(bs.getRef()))
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
                                    final Set<User> allowedUsers = storageData.getUsers();
                                    final Set<Role> keyRoles = storageData.getRead();
                                    if (allowedUsers.isEmpty() && (keyRoles == null || keyRoles.isEmpty())) {
                                        LOG.info("{} logged in and accessed key {} in {}", userHolder.orElse(new User("anonymous", null)), data.getLeft(), ref);
                                        return true;
                                    }
                                    if (!userHolder.isPresent()) {
                                        return false;
                                    }
                                    final User user = userHolder.get();
                                    if (allowedUsers.contains(user) || APIHelper.isKeyUserAllowed(storage, hashService, user, ref, keyRoles)
                                            || addKeyAuthenticator.authenticate(user, ref)) {
                                        LOG.info("{} logged in and accessed key {} in {}", user, p.getLeft().stream()
                                                .map(Pair::getLeft)
                                                .collect(Collectors.toList()), ref);
                                        return true;
                                    }
                                    return false;
                                }).map(ps -> new SearchResult(ps, p.getRight())))
                        .flatMap(Function.identity()).collect(Collectors.toList()))
                .thenApplyAsync(result -> Response.ok(new SearchResultWrapper(result)).build()).thenAcceptAsync(asyncResponse::resume, executor);
    }
}
