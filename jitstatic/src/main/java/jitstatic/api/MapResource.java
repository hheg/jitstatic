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
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response.Status;

import org.eclipse.jgit.api.errors.RefNotFoundException;
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
import jitstatic.utils.VersionIsNotSameException;
import jitstatic.utils.WrappingAPIException;

@Path("storage")
public class MapResource {

	private static final Logger LOG = LoggerFactory.getLogger(MapResource.class);
	private final Storage storage;

	public MapResource(final Storage storage) {
		this.storage = Objects.requireNonNull(storage);
	}

	@GET
	@Timed(name = "get_storage_time")
	@Metered(name = "get_storage_counter")
	@ExceptionMetered(name = "get_storage_exception")
	@Path("/{key : .+}")
	@Produces({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML })
	public KeyData get(final @PathParam("key") String key, final @QueryParam("ref") String ref, final @Auth Optional<User> user) {

		checkRef(ref);

		final Future<StoreInfo> future = storage.get(key, ref);
		final StoreInfo si = unwrap(future);
		if (si == null) {
			throw new WebApplicationException(Status.NOT_FOUND);
		}
		final StorageData data = si.getStorageData();
		final Set<User> allowedUsers = data.getUsers();
		if (allowedUsers.isEmpty()) {
			return new KeyData(si.getVersion(), si.getData());
		}

		if (!user.isPresent()) {
			LOG.info("Resource " + key + " needs a user");
			throw new WebApplicationException(Status.UNAUTHORIZED);
		}

		if (!allowedUsers.contains(user.get())) {
			LOG.info("Resource " + key + "is denied for user " + user);
			throw new WebApplicationException(Status.UNAUTHORIZED);
		}
		return new KeyData(si.getVersion(), si.getData());
	}

	@PUT
	@Timed(name = "put_storage_time")
	@Metered(name = "put_storage_counter")
	@ExceptionMetered(name = "put_storage_exception")
	@Path("/{key : .+}")
	@Produces({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML })
	public VersionData modifyKey(final @PathParam("key") String key, final @QueryParam("ref") String ref, final @Auth Optional<User> user,
			final @Valid ModifyKeyData data) {
		// All resources without a user cannot be modified with this method. It has to
		// be done through directly changing the file in the git repository.
		if (!user.isPresent()) {
			throw new WebApplicationException(Status.UNAUTHORIZED);
		}
		checkValidRef(ref);

		final StoreInfo storeInfo = unwrap(storage.get(key, ref));

		if (storeInfo == null) {
			throw new WebApplicationException(Status.NOT_FOUND);
		}

		final Set<User> allowedUsers = storeInfo.getStorageData().getUsers();
		if (allowedUsers.isEmpty()) {
			throw new WebApplicationException(Status.BAD_REQUEST);
		}

		if (!allowedUsers.contains(user.get())) {
			LOG.info("Resource " + key + "is denied for user " + user);
			throw new WebApplicationException(Status.UNAUTHORIZED);
		}

		final String currentVersion = storeInfo.getVersion();
		final String requestedVersion = data.getHaveVersion();
		if (!currentVersion.equals(requestedVersion)) {
			throw new WebApplicationException(currentVersion, Status.BAD_REQUEST);
		}

		final String newVersion = unwrapWithApi(
				storage.put(data.getData(), data.getHaveVersion(), data.getMessage(), user.get().getName(), data.getUserMail(), key, ref));
		if (newVersion == null) {
			throw new WebApplicationException(Status.NOT_FOUND);
		}
		return new VersionData(newVersion);
	}

	private void checkValidRef(final String ref) {
		if(ref != null) {
			checkRef(ref);
			if(ref.startsWith(Constants.R_TAGS)) {
				throw new WebApplicationException(Status.FORBIDDEN);
			}
		}
	}

	private static <T> T unwrapWithApi(final Future<T> future) {
		if (future == null) {
			return null;
		}
		try {
			return future.get();
		} catch (final InterruptedException e) {
			LOG.error("Instruction was interrupted", e);
			throw new WebApplicationException(Status.REQUEST_TIMEOUT);
		} catch (final ExecutionException e) {
			final Throwable cause = e.getCause();
			if (cause instanceof WrappingAPIException) {
				final Exception apiException = (Exception) cause.getCause();
				if (apiException instanceof UnsupportedOperationException) {
					throw new WebApplicationException(Status.NOT_FOUND);
				}
				if (apiException instanceof RefNotFoundException) {
					return null;
				}
				if (apiException instanceof VersionIsNotSameException) {
					throw new WebApplicationException(apiException.getMessage(), Status.CONFLICT);
				}
			}
			LOG.error("Error while unwrapping future", e);
			throw new WebApplicationException(Status.INTERNAL_SERVER_ERROR);
		}
	}

	private static <T> T unwrap(Future<T> future) {
		if (future == null) {
			return null;
		}
		try {
			return future.get();
		} catch (final InterruptedException | ExecutionException e) {
			LOG.error("Error while unwrapping future", e);
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
