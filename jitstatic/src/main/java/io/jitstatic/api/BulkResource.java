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
import java.util.Optional;
import java.util.stream.Collectors;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import org.hibernate.validator.constraints.NotEmpty;

import com.codahale.metrics.annotation.ExceptionMetered;
import com.codahale.metrics.annotation.Metered;
import com.codahale.metrics.annotation.Timed;

import io.dropwizard.auth.Auth;
import io.jitstatic.auth.User;
import io.jitstatic.hosted.StoreInfo;
import io.jitstatic.storage.Storage;
import io.jitstatic.utils.Pair;

@Path("bulk")
public class BulkResource {

    private final Storage storage;

    public BulkResource(final Storage storage) {
        this.storage = storage;
    }

    @POST
    @Path("fetch")
    @Timed(name = "fetch_storage_time")
    @Metered(name = "fetch_storage_counter")
    @ExceptionMetered(name = "fetch_storage_exception")
    @Consumes({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    public Response fetch(@NotNull @NotEmpty @Valid final List<BulkSearch> searches, final @Auth Optional<User> user) {
        final List<Pair<List<Pair<String, StoreInfo>>, String>> searchResults = storage.getList(searches.stream()
                .map(bs -> Pair.of(bs.getPaths().stream().map(sp -> Pair.of(sp.getPath(), sp.isRecursively())).collect(Collectors.toList()), bs.getRef())).collect(Collectors.toList()), user);

        if (searchResults.isEmpty()) {
            Response.status(Status.NOT_FOUND).build();
        }

        return Response.ok(searchResults.stream()
                .map(p -> p.getKey().stream().map(ps -> new SearchResult(ps, p.getRight())).collect(Collectors.toList()))
                .flatMap(List::stream).collect(Collectors.toList())).build();
    }

}
