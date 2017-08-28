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

import java.util.Optional;
import java.util.Set;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response.Status;

import com.codahale.metrics.annotation.Metered;
import com.fasterxml.jackson.databind.JsonNode;

import io.dropwizard.auth.Auth;
import jitstatic.auth.User;
import jitstatic.storage.Storage;
import jitstatic.storage.StorageData;

@Path("storage")
public class MapResource {
	private final Storage storage;

	public MapResource(final Storage storage) {
		this.storage = storage;
	}

	@Path("/{key}")
	@Metered
	@GET
	@Produces({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML })
	public JsonNode get(final @PathParam("key") String key, @Auth Optional<User> user) {
		final StorageData o = storage.get(key);
		if (o == null) {
			throw new WebApplicationException(Status.NOT_FOUND);
		}
		Set<User> allowedUsers = o.getUsers();
		if (allowedUsers.isEmpty()) {
			return o.getData();
		}
		if (!user.isPresent()) {
			throw new WebApplicationException(Status.UNAUTHORIZED);
		}
		if (!allowedUsers.contains(user.get())) {
			throw new WebApplicationException(Status.UNAUTHORIZED);
		}
		return o.getData();
	}
}
