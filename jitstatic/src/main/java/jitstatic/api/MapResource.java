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

import javax.validation.Valid;
import javax.ws.rs.GET;
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

import io.dropwizard.auth.Auth;
import jitstatic.auth.User;
import jitstatic.storage.Storage;
import jitstatic.storage.StorageData;
import jitstatic.storage.StoreInfo;

@Path("storage")
public class MapResource {

	private static final Logger log = LoggerFactory.getLogger(MapResource.class);
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
	public KeyData get(final @PathParam("key") String key, final @QueryParam("ref") String ref,
			final @Auth Optional<User> user) {

		checkRef(ref);

		final Future<StoreInfo> future = storage.get(key, ref);
		final StoreInfo si = unwrap(future);
		if (si == null) {
			throw new WebApplicationException(Status.NOT_FOUND);
		}
		final StorageData data = si.getStorageData();
		final Set<User> allowedUsers = data.getUsers();
		if (allowedUsers.isEmpty()) {
			return new KeyData(si.getVersion(), data.getData());
		}

		if (!user.isPresent()) {
			log.info("Resource " + key + " needs a user");
			throw new WebApplicationException(Status.UNAUTHORIZED);
		}

		if (!allowedUsers.contains(user.get())) {
			log.info("Resource " + key + "is denied for user " + user);
			throw new WebApplicationException(Status.UNAUTHORIZED);
		}
		return new KeyData(si.getVersion(), data.getData());
	}

	@PUT
	@Timed(name = "put_storage_time")
	@Metered(name = "put_storage_counter")
	@ExceptionMetered(name = "put_storage_exception")
	@Path("/{key : .+}")
	@Produces({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML })
	public Response modifyKey(final @PathParam("key") String key, final @QueryParam("ref") String ref,
			final @Auth Optional<User> user, final @Valid ModifyKeyData data) {

		if (!user.isPresent()) {
			throw new WebApplicationException(Status.UNAUTHORIZED);
		}
		checkRef(ref);
		final Future<StoreInfo> future = storage.get(key, ref);
		final StoreInfo storeInfo = unwrap(future);
		if(storeInfo == null) {
			throw new WebApplicationException(Status.NOT_FOUND);
		}
		final String currentVersion = storeInfo.getVersion();
		final String requestedVersion = data.getVersion();
		if (!currentVersion.equals(requestedVersion)) {
			throw new WebApplicationException(currentVersion, Status.BAD_REQUEST);
		}
		final Future<Void> exec = storage.put(data.getData(), data.getVersion(), data.getMessage(), user.get(), key,
				ref);
		unwrap(exec); // TODO fix this.
		return Response.ok().build();
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
	}

	private void checkRef(final String ref) {
		if (ref != null) {
			if (!(ref.startsWith(Constants.R_HEADS) ^ ref.startsWith(Constants.R_TAGS))) {
				throw new WebApplicationException(Status.NOT_FOUND);
			}
		}
	}
}
