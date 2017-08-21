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



import java.util.Map;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response.Status;

import com.codahale.metrics.annotation.Metered;

import jitstatic.storage.Storage;

@Path("storage")
public class MapResource {
	private final Storage storage;

	public MapResource(final Storage storage) {
		this.storage = storage;
	}

	@Path("/{key}")
	@Metered
	@GET
	@Produces(MediaType.APPLICATION_JSON)
	@RolesAllowed(value = { "keystorage" })
	public Map<String, Object> get(final @PathParam("key") String key) {
		final Map<String, Object> o = storage.get(key);
		if (o == null) {
			throw new WebApplicationException("Key not found",Status.NOT_FOUND);
		}
		return o;
	}
}
