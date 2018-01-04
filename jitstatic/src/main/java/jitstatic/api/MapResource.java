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

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.Function;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response.Status;

import org.eclipse.jgit.lib.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.annotation.ExceptionMetered;
import com.codahale.metrics.annotation.Metered;
import com.codahale.metrics.annotation.Timed;
import com.fasterxml.jackson.databind.JsonNode;

import io.dropwizard.auth.Auth;
import jitstatic.auth.User;
import jitstatic.storage.Storage;
import jitstatic.storage.StorageData;

@Path("storage")
public class MapResource {

	private static final Logger log = LoggerFactory.getLogger(MapResource.class);
	private final Storage storage;

	public MapResource(final Storage storage) {
		this.storage = Objects.requireNonNull(storage);
	}

	@GET
	@Timed(name = "storage_time")
	@Metered(name = "storage_counter")
	@ExceptionMetered(name = "storage_exception")
	@Path("/{key : .+}")
	@Produces({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML })
	public JsonNode get(final @PathParam("key") String key, final @QueryParam("ref") String ref,
			final @Auth Optional<User> user) {

		if (ref != null) {
			if (!(ref.startsWith(Constants.R_HEADS) ^ ref.startsWith(Constants.R_TAGS))) {
				throw new WebApplicationException(Status.NOT_FOUND);
			}
		}

		final Future<StorageData> future = storage.get(key, ref);
		final StorageData o = unwrap.apply(future);
		if (o == null) {
			throw new WebApplicationException(Status.NOT_FOUND);
		}

		final Set<User> allowedUsers = o.getUsers();
		if (allowedUsers.isEmpty()) {
			return o.getData();
		}

		if (!user.isPresent()) {
			log.info("Resource " + key + " needs a user");
			throw new WebApplicationException(Status.UNAUTHORIZED);
		}

		if (!allowedUsers.contains(user.get())) {
			log.info("Resource " + key + "is denied with user " + user);
			throw new WebApplicationException(Status.UNAUTHORIZED);
		}
		return o.getData();
	}

	private static final Function<Future<StorageData>, StorageData> unwrap = (t) -> {
		if (t == null) {
			return null;
		}
		try {
			return t.get();
		} catch (final InterruptedException | ExecutionException e) {
			return null;
		}
	};
}
