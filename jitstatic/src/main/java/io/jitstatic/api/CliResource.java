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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.EntityTag;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.annotation.ExceptionMetered;
import com.codahale.metrics.annotation.Metered;
import com.codahale.metrics.annotation.Timed;
import com.google.common.hash.Hashing;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

@Path("cli")
@SuppressFBWarnings(value = "RCN_REDUNDANT_NULLCHECK_WOULD_HAVE_BEEN_A_NPE", justification = "This is a false positive in Java 11, should be removed")
public class CliResource {

    private static final Logger LOG = LoggerFactory.getLogger(CliResource.class);
    private final CachedResource createUser;
    private final CachedResource deleteUser;
    private final CachedResource fetch;
    private final CachedResource updateUser;

    public CliResource() throws IOException {
        createUser = new CachedResource("assets/createuser.sh");
        deleteUser = new CachedResource("assets/deleteuser.sh");
        updateUser = new CachedResource("assets/updateuser.sh");
        fetch = new CachedResource("assets/fetch.sh");
    }

    @GET
    @Timed(name = "get_script_time")
    @Metered(name = "get_script_counter")
    @ExceptionMetered(name = "get_script_exception")
    @Path("{script : .+}")
    public void getScript(final @Suspended AsyncResponse asyncResponse, final @PathParam("script") String script, final @Context ExecutorService executor) {
        CompletableFuture.supplyAsync(() -> {
            switch (script) {
            case "createuser.sh":
                return createUser;
            case "deleteuser.sh":
                return deleteUser;
            case "fetch.sh":
                return fetch;
            case "updateuser.sh":
                return updateUser;
            default:
                throw new WebApplicationException(Status.NOT_FOUND);
            }
        }, executor)
        .thenApplyAsync(c -> Response.ok(c.data).encoding("utf-8").type(MediaType.TEXT_PLAIN).tag(c.eTag).build(), executor)
        .exceptionally(t -> {
            if (t instanceof WebApplicationException) {
                return ((WebApplicationException) t).getResponse();
            }
            LOG.error("Internal error", t);
            return new WebApplicationException(Status.INTERNAL_SERVER_ERROR).getResponse();
        }).thenAcceptAsync(asyncResponse::resume, executor);
    }

    private static class CachedResource {
        private final byte[] data;
        private final EntityTag eTag;

        public CachedResource(String resource) throws IOException {
            try (InputStream is = CliResource.class.getClassLoader().getResourceAsStream(resource)) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                is.transferTo(baos);
                data = baos.toByteArray();
                this.eTag = new EntityTag(Hashing.murmur3_128().hashBytes(data).toString());
            }
        }
    }
}
