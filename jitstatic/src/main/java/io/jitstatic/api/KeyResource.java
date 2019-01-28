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

import static io.jitstatic.JitStaticConstants.DECLAREDHEADERS;
import static io.jitstatic.JitStaticConstants.JITSTATIC_KEYADMIN_REALM;
import static io.jitstatic.JitStaticConstants.JITSTATIC_KEYUSER_REALM;
import static io.jitstatic.JitStaticConstants.JITSTATIC_NOWHERE;
import static io.jitstatic.JitStaticConstants.X_JITSTATIC_MAIL;
import static io.jitstatic.JitStaticConstants.X_JITSTATIC_MESSAGE;
import static io.jitstatic.JitStaticConstants.X_JITSTATIC_NAME;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.ConstraintViolation;
import javax.validation.ConstraintViolationException;
import javax.validation.Validator;
import javax.validation.constraints.NotNull;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.OPTIONS;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.EntityTag;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.StreamingOutput;

import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.annotation.ExceptionMetered;
import com.codahale.metrics.annotation.Metered;
import com.codahale.metrics.annotation.Timed;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spencerwi.either.Either;

import io.dropwizard.auth.Auth;
import io.jitstatic.CommitMetaData;
import io.jitstatic.HeaderPair;
import io.jitstatic.JitStaticConstants;
import io.jitstatic.MetaData;
import io.jitstatic.Role;
import io.jitstatic.auth.KeyAdminAuthenticator;
import io.jitstatic.auth.User;
//import io.jitstatic.auth.UserData;
import io.jitstatic.hosted.FailedToLock;
import io.jitstatic.hosted.StoreInfo;
import io.jitstatic.storage.HashService;
import io.jitstatic.storage.Storage;
import io.jitstatic.utils.Pair;
import io.jitstatic.utils.WrappingAPIException;

@Path("storage")
public class KeyResource {
    private final String defaultRef;
    private static final String ACCESS_CONTROL_EXPOSE_HEADERS = "Access-Control-Expose-Headers";
    private static final String LOGGED_IN_AND_ACCESSED_KEY = "{} logged in and accessed key {} in {}";
    private static final String RESOURCE_IS_DENIED_FOR_USER = "Resource {} in {} is denied for user {}";
    private static final String UTF_8 = "utf-8";
    private static final Logger LOG = LoggerFactory.getLogger(KeyResource.class);
    private final Storage storage;
    private final KeyAdminAuthenticator addKeyAuthenticator;
    private final APIHelper helper;
    private final boolean cors;
    private final ObjectMapper mapper;
    private final Validator validator;
    private final HashService hashService;

    public KeyResource(final Storage storage, final KeyAdminAuthenticator adminKeyAuthenticator, final boolean cors, final String defaultBranch,
            final ObjectMapper mapper, final Validator validator, final HashService hashService) {
        this.storage = Objects.requireNonNull(storage);
        this.addKeyAuthenticator = Objects.requireNonNull(adminKeyAuthenticator);
        this.helper = new APIHelper(LOG);
        this.cors = cors;
        this.defaultRef = Objects.requireNonNull(defaultBranch);
        this.mapper = Objects.requireNonNull(mapper);
        this.validator = Objects.requireNonNull(validator);
        this.hashService = hashService;
    }

    @GET
    @Timed(name = "get_storage_time")
    @Metered(name = "get_storage_counter")
    @ExceptionMetered(name = "get_storage_exception")
    @Path("{key : .+}")
    public Response get(final @PathParam("key") String key, final @QueryParam("ref") String ref, final @Auth Optional<User> user,
            final @Context HttpHeaders headers, final @Context HttpServletResponse response) {

        helper.checkRef(ref);

        final StoreInfo storeInfo = helper.checkIfKeyExist(key, ref, storage);
        final EntityTag tag = new EntityTag(storeInfo.getVersion());
        final Response noChange = helper.checkETag(headers, tag);

        final MetaData data = storeInfo.getMetaData();
        final Set<User> allowedUsers = data.getUsers();
        final Set<Role> roles = data.getRead();
        if (allowedUsers.isEmpty() && (roles == null || roles.isEmpty())) {
            if (noChange != null) {
                return noChange;
            }
            return buildResponse(storeInfo, tag, data, response);
        }

        if (!user.isPresent()) {
            LOG.info("Resource {} in {} needs a user", key, helper.setToDefaultRef(defaultRef, ref));
            return helper.respondAuthenticationChallenge(JITSTATIC_KEYUSER_REALM);
        }

        checkIfAllowed(key, user.get(), allowedUsers, ref, storeInfo.getMetaData().getRead());
        if (noChange != null) {
            return noChange;
        }
        LOG.info(LOGGED_IN_AND_ACCESSED_KEY, user.get(), key, helper.setToDefaultRef(defaultRef, ref));
        return buildResponse(storeInfo, tag, data, response);
    }

    @GET
    @Produces({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML })
    public Response getRootList(final @QueryParam("ref") String ref, @QueryParam("recursive") boolean recursive, @QueryParam("light") final boolean light,
            final @Auth Optional<User> user) {
        return getList("/", ref, recursive, light, user);
    }

    @GET
    @Timed(name = "get_list_time")
    @Metered(name = "get_list_counter")
    @ExceptionMetered(name = "get_list_exception")
    @Path("{key : .+/}")
    @Produces({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML })
    public Response getList(final @PathParam("key") String key, final @QueryParam("ref") String ref, @QueryParam("recursive") boolean recursive,
            @QueryParam("light") final boolean light, final @Auth Optional<User> userHolder) {
        helper.checkRef(ref);

        final List<Pair<String, StoreInfo>> list = storage.getListForRef(List.of(Pair.of(key, recursive)), ref).stream()
                .filter(data -> {
                    final MetaData storageData = data.getRight().getMetaData();
                    final Set<User> allowedUsers = storageData.getUsers();
                    final Set<Role> keyRoles = storageData.getRead();
                    if (allowedUsers.isEmpty() && (keyRoles == null || keyRoles.isEmpty())) {
                        LOG.info(LOGGED_IN_AND_ACCESSED_KEY, userHolder.isPresent() ? userHolder.get() : "anonymous", data.getLeft(),
                                helper.setToDefaultRef(defaultRef, ref));
                        return true;
                    }
                    if (!userHolder.isPresent()) {
                        return false;
                    }
                    final User user = userHolder.get();
                    if (isUserAllowed(ref, user, allowedUsers, keyRoles)) {
                        LOG.info(LOGGED_IN_AND_ACCESSED_KEY, user, data.getLeft(), helper.setToDefaultRef(defaultRef, ref));
                        return true;
                    }
                    return false;
                }).collect(Collectors.toList());

        if (list.isEmpty()) {
            return Response.status(Status.NOT_FOUND).build();
        }

        return Response
                .ok(new KeyDataWrapper(list.stream().map(p -> light ? new KeyData(p.getLeft(), p.getRight()) : new KeyData(p)).collect(Collectors.toList())))
                .build();
    }

    private Response buildResponse(final StoreInfo storeInfo, final EntityTag tag, final MetaData data, HttpServletResponse response) {
        final StreamingOutput so = output -> {
            try (InputStream is = storeInfo.getStreamProvider().getInputStream()) {
                is.transferTo(output);
            }
        };
        final ResponseBuilder responseBuilder = Response.ok(so).header(HttpHeaders.CONTENT_TYPE, data.getContentType())
                .header(HttpHeaders.CONTENT_ENCODING, UTF_8).tag(tag);
        final List<HeaderPair> headers = data.getHeaders();
        if (headers != null) {
            final Set<String> declaredHeaders = new HashSet<>();
            for (HeaderPair headerPair : headers) {
                final String header = headerPair.getHeader();
                responseBuilder.header(header, headerPair.getValue());
                declaredHeaders.add(header.toLowerCase(Locale.ROOT));
            }
            if (cors && !declaredHeaders.isEmpty()) {
                final String defaultExposedHeaders = response.getHeader(ACCESS_CONTROL_EXPOSE_HEADERS);
                final Set<String> exposedHeaders = defaultExposedHeaders != null
                        ? Arrays.stream(defaultExposedHeaders.split(",")).map(deh -> deh.toLowerCase(Locale.ROOT)).collect(Collectors.toSet())
                        : Set.of();
                declaredHeaders.addAll(exposedHeaders);
                response.setHeader(ACCESS_CONTROL_EXPOSE_HEADERS, declaredHeaders.stream().collect(Collectors.joining(",")));
            }
        }
        return responseBuilder.build();
    }

    @PUT
    @Timed(name = "put_storage_time")
    @Metered(name = "put_storage_counter")
    @ExceptionMetered(name = "put_storage_exception")
    @Path("{key : .+}")
    @Consumes({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML })
    public Response modifyKey(final @PathParam("key") String key, final @QueryParam("ref") String ref, final @Auth Optional<User> userholder,
            final @Context HttpServletRequest httpRequest, final @Context Request request, final @Context HttpHeaders headers)
            throws JsonParseException, JsonMappingException, IOException {
        // All resources without a user cannot be modified with this method. It has to
        // be done through directly changing the file in the Git repository.
        final ModifyKeyData data;
        final String currentVersion;
        final User user;
        try {
            if (!userholder.isPresent()) {
                return helper.respondAuthenticationChallenge(JITSTATIC_KEYADMIN_REALM);
            }
            user = userholder.get();

            helper.checkHeaders(headers);

            helper.checkValidRef(ref);

            final StoreInfo storeInfo = helper.checkIfKeyExist(key, ref, storage);
            final Set<User> allowedUsers = storeInfo.getMetaData().getUsers();
            final Set<Role> roles = storeInfo.getMetaData().getWrite();
            boolean isAuthenticated = addKeyAuthenticator.authenticate(user, ref);
            if (allowedUsers.isEmpty() && (roles == null || roles.isEmpty()) && !isAuthenticated) {
                throw new WebApplicationException(Status.BAD_REQUEST);
            }

            if (!(isAuthenticated || allowedUsers.contains(user) || isKeyUserAllowed(user, ref, roles))) {
                LOG.info(RESOURCE_IS_DENIED_FOR_USER, key, helper.setToDefaultRef(defaultRef, ref), user);
                throw new WebApplicationException(Status.FORBIDDEN);
            }

            currentVersion = storeInfo.getVersion();
            final EntityTag entityTag = new EntityTag(currentVersion);
            final ResponseBuilder response = request.evaluatePreconditions(entityTag);

            if (response != null) {
                return response.header(HttpHeaders.CONTENT_ENCODING, UTF_8).tag(entityTag).build();
            }
        } finally {
            data = mapper.readValue(httpRequest.getInputStream(), ModifyKeyData.class);
        }
        validateData(data);
        final Either<String, FailedToLock> result = helper.unwrapWithPUTApi(
                () -> storage.put(key, ref, data.getData(), currentVersion,
                        new CommitMetaData(data.getUserInfo(), data.getUserMail(), data.getMessage(), user.getName(), JITSTATIC_NOWHERE)));

        if (result == null) {
            throw new WebApplicationException(Status.NOT_FOUND);
        }

        if (result.isRight()) {
            return Response.status(Status.PRECONDITION_FAILED).build();
        }
        final String newVersion = result.getLeft();

        if (newVersion == null) {
            throw new WebApplicationException(Status.NOT_FOUND);
        }
        LOG.info("{} logged in and modified key {} in {}", user, key, helper.setToDefaultRef(defaultRef, ref));
        return Response.ok().tag(new EntityTag(newVersion)).header(HttpHeaders.CONTENT_ENCODING, UTF_8).build();
    }

    private <T> void validateData(final T data) {
        if (data == null) {
            throw new WebApplicationException("entity is null", 422);
        }
        final Set<ConstraintViolation<T>> violations = validator.validate(data);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
    }

    void checkIfAllowed(final String key, final User user, final Set<User> allowedUsers, final String ref, final Set<Role> keyRoles) {
        if (!isUserAllowed(ref, user, allowedUsers, keyRoles)) {
            LOG.info(RESOURCE_IS_DENIED_FOR_USER, key, helper.setToDefaultRef(defaultRef, ref), user);
            throw new WebApplicationException(Status.FORBIDDEN);
        }
    }

    boolean isKeyUserAllowed(final User user, final String ref, Set<Role> keyRoles) {
        keyRoles = keyRoles == null ? Set.of() : keyRoles;
        try {
            io.jitstatic.auth.UserData userData = storage.getUser(user.getName(), ref, JitStaticConstants.JITSTATIC_KEYUSER_REALM);
            if (userData == null) {
                return false;
            }
            final Set<Role> userRoles = userData.getRoles();
            return (!keyRoles.stream().noneMatch(userRoles::contains) && hashService.hasSamePassword(userData, user.getPassword()));
        } catch (RefNotFoundException e) {
            return false;
        }
    }

    @POST
    @Timed(name = "post_storage_time")
    @Metered(name = "post_storage_counter")
    @ExceptionMetered(name = "post_storage_exception")
    @Path("{key : .+}")
    @Consumes({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML })
    @Produces({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML, MediaType.TEXT_PLAIN })
    public Response addKey(@NotNull final @PathParam("key") String key, final @QueryParam("ref") String ref, final @Context HttpServletRequest httpRequest,
            final @Auth Optional<User> userHolder) throws JsonParseException, JsonMappingException, IOException {
        final AddKeyData data;
        final User user;
        try {
            if (!userHolder.isPresent()) {
                return helper.respondAuthenticationChallenge(JITSTATIC_KEYADMIN_REALM);
            }

            helper.checkValidRef(ref);

            user = userHolder.get();
            if (!addKeyAuthenticator.authenticate(user, ref)) {
                try {
                    final io.jitstatic.auth.UserData userData = storage.getUser(user.getName(), ref, JITSTATIC_KEYUSER_REALM);
                    if (userData == null || !hashService.hasSamePassword(userData, user.getPassword())) {
                        LOG.info(RESOURCE_IS_DENIED_FOR_USER, key, helper.setToDefaultRef(defaultRef, ref), user);
                        throw new WebApplicationException(Status.FORBIDDEN);
                    }
                } catch (RefNotFoundException e) {
                    throw new WebApplicationException(Status.FORBIDDEN);
                }
            }

            final Optional<StoreInfo> storeInfo = helper.unwrap(() -> storage.getKey(key, ref));

            if (storeInfo.isPresent()) {
                throw new WebApplicationException(key + " already exist in " + (ref == null ? defaultRef : ref), Status.CONFLICT);
            }
        } finally {
            data = mapper.readValue(httpRequest.getInputStream(), AddKeyData.class);
        }
        validateData(data);
        final String version = helper.unwrapWithPOSTApi(() -> storage.addKey(key, ref, data.getData(), data.getMetaData(),
                new CommitMetaData(data.getUserInfo(), data.getUserMail(), data.getMessage(), user.getName(), JITSTATIC_NOWHERE)));
        LOG.info("{} logged in and added key {} in {}", user, key, helper.setToDefaultRef(defaultRef, ref));
        return Response.ok().tag(new EntityTag(version)).header(HttpHeaders.CONTENT_ENCODING, UTF_8).build();
    }

    @DELETE
    @Path("{key : .+}")
    @Timed(name = "delete_storage_time")
    @Metered(name = "delete_storage_counter")
    @ExceptionMetered(name = "delete_storage_exception")
    public Response delete(final @PathParam("key") String key, final @QueryParam("ref") String ref, final @Auth Optional<User> userHolder,
            final @Context HttpHeaders headers) {
        if (!userHolder.isPresent()) {
            return helper.respondAuthenticationChallenge(JITSTATIC_KEYADMIN_REALM);
        }

        final String userHeader = notEmpty(headers, X_JITSTATIC_NAME);
        final String message = notEmpty(headers, X_JITSTATIC_MESSAGE);
        final String userMail = notEmpty(headers, X_JITSTATIC_MAIL);

        helper.checkValidRef(ref);
        final User user = userHolder.get();
        final StoreInfo storeInfo = helper.checkIfKeyExist(key, ref, storage);
        final Set<User> allowedUsers = storeInfo.getMetaData().getUsers();
        final Set<Role> roles = storeInfo.getMetaData().getWrite();

        if (!isUserAllowed(ref, user, allowedUsers, roles)) {
            if (allowedUsers.isEmpty() && (roles == null || roles.isEmpty())) {
                throw new WebApplicationException(Status.BAD_REQUEST);
            }
            LOG.info(RESOURCE_IS_DENIED_FOR_USER, key, helper.setToDefaultRef(defaultRef, ref), user);
            throw new WebApplicationException(Status.FORBIDDEN);
        }

        try {
            storage.delete(key, ref, new CommitMetaData(userHeader, userMail, message, user.getName(), JITSTATIC_NOWHERE));
        } catch (final WrappingAPIException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof UnsupportedOperationException) {
                throw new WebApplicationException(key, Status.METHOD_NOT_ALLOWED);
            }
            LOG.error("Unknown API error", e);
            throw new WebApplicationException(Status.INTERNAL_SERVER_ERROR);
        }
        LOG.info("{} logged in and deleted key {} in {}", user, key, helper.setToDefaultRef(defaultRef, ref));
        return Response.ok().build();
    }

    @OPTIONS
    @Path("{key : .+}")
    @Timed(name = "options_storage_time")
    @Metered(name = "options_storage_counter")
    @ExceptionMetered(name = "options_storage_exception")
    public void options(final @PathParam("key") String key, final @QueryParam("ref") String ref, final @Context HttpServletRequest request) {
        if (!cors) {
            return;
        }
        List<String> declared = new ArrayList<>();
        if (requestingDelete(request.getHeader("Access-Control-Request-Method"))) {
            if (ref != null && ref.startsWith("refs/tags/")) {
                return;
            }
            declared = Arrays.asList(X_JITSTATIC_NAME, X_JITSTATIC_MESSAGE, X_JITSTATIC_MAIL);
        }

        final Pair<MetaData, String> metaKey = storage.getMetaKey(key, ref);
        if (metaKey != null && metaKey.isPresent()) {
            final List<HeaderPair> headPairList = metaKey.getLeft().getHeaders();
            if (headPairList != null && !headPairList.isEmpty()) {
                declared.addAll(headPairList.stream().map(HeaderPair::getHeader).collect(Collectors.toList()));
            }
        }

        if (!declared.isEmpty()) {
            request.setAttribute(DECLAREDHEADERS, declared);
        }
    }

    private boolean requestingDelete(final String requestMethod) {
        if (requestMethod == null) {
            return false;
        }
        final String[] methods = requestMethod.split(",");
        for (String m : methods) {
            if ("DELETE".equalsIgnoreCase(m.trim())) {
                return true;
            }
        }
        return false;
    }

    private boolean isUserAllowed(final String ref, final User user, final Set<User> allowedUsers, final Set<Role> roles) {
        return allowedUsers.contains(user) || isKeyUserAllowed(user, ref, roles) || addKeyAuthenticator.authenticate(user, ref);
    }

    private String notEmpty(final HttpHeaders httpHeaders, final String headerName) {
        String headers = httpHeaders.getHeaderString(headerName);
        if (headers == null || headers.isEmpty()) {
            throw new WebApplicationException("Missing " + headerName, Status.BAD_REQUEST);
        }
        return headers;
    }
}
