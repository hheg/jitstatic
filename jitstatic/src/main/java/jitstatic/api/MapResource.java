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

import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
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
	private static final String REF = "ref";
	private final Storage storage;
	
	@Context
	private HttpHeaders httpHeaders;


	public MapResource(final Storage storage) {
		this.storage = Objects.requireNonNull(storage);
	}
	
	@GET
	@Timed(name = "get_storage_time")
	@Metered(name = "get_storage_counter")
	@ExceptionMetered(name = "get_storage_exception")
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
		final StorageData o = unwrap(future);
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
	
	@PUT
	@Timed(name = "put_storage_time")
	@Metered(name = "put_storage_counter")
	@ExceptionMetered(name = "put_storage_exception")
	@Path("/{key : .+}")
	@Produces({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML })
	public Response modifyKey(final @PathParam("key") String key, final @Auth Optional<User> user, final ModifyKeyData data) {
		final String ref = getRef(key);
		final Future<StorageData> future = storage.get(key, ref);
		final StorageData o = unwrap(future);
		
		return Response.ok().build();
	}

	@POST
	@Timed(name = "post_storage_time")
	@Metered(name = "post_storage_counter")
	@ExceptionMetered(name = "post_storage_exception")
	@Path("/{key : .+}")
	@Produces({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML })
	public Response addKey(final @PathParam("key") String key, final @Auth Optional<User> user, final AddKeyData data) {
		final String ref = getRef(key);
		final Future<StorageData> future = storage.get(key, ref);
		final StorageData o = unwrap(future);
		if(o != null) {
			throw new WebApplicationException("Key already exist",Status.CONFLICT);
		}
		return Response.ok().build();
	}

	@DELETE
	@Timed(name = "delete_storage_time")
	@Metered(name = "delete_storage_counter")
	@ExceptionMetered(name = "delete_storage_exception")
	@Path("/{key : .+}")
	@Produces({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML })
	public Response deleteKey(final @PathParam("key") String key, final @Auth Optional<User> user) {
		final String ref = getRef(key);
		final Future<StorageData> future = storage.delete(key,ref);
		unwrap(future);
		return Response.ok().build();
	}
	
	private String getRef(String key) {
		final String[] split = key.split("\\?");
		String ref = null;
		if (split.length == 2) {
			if (!split[1].startsWith(REF)) {
				throw new WebApplicationException(Status.NOT_FOUND);
			}
			final String refSplit[] = split[1].split("=");
			if (refSplit.length != 2) {
				throw new WebApplicationException(Status.NOT_FOUND);
			}
			ref = refSplit[1];
			if (!(ref.startsWith(Constants.R_HEADS) ^ ref.startsWith(Constants.R_TAGS))) {
				throw new WebApplicationException(Status.NOT_FOUND);
			}

		}
		return ref;
	}

	private static <T> T unwrap(Future<T> t) {
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
