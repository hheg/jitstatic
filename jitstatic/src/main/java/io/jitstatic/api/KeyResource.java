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
import static io.jitstatic.JitStaticConstants.X_JITSTATIC_MAIL;
import static io.jitstatic.JitStaticConstants.X_JITSTATIC_MESSAGE;
import static io.jitstatic.JitStaticConstants.X_JITSTATIC_NAME;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.ConstraintViolation;
import javax.validation.ConstraintViolationException;
import javax.validation.Valid;
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
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;
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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.dropwizard.auth.Auth;
import io.dropwizard.validation.Validated;
import io.jitstatic.CommitMetaData;
import io.jitstatic.HeaderPair;
import io.jitstatic.MetaData;
import io.jitstatic.Role;
import io.jitstatic.auth.KeyAdminAuthenticator;
import io.jitstatic.auth.User;
import io.jitstatic.hosted.StoreInfo;
import io.jitstatic.storage.HashService;
import io.jitstatic.storage.Storage;
import io.jitstatic.utils.Pair;

@Path("storage")
//TODO Remove this SpotBugs Error
@SuppressFBWarnings(value = "RCN_REDUNDANT_NULLCHECK_WOULD_HAVE_BEEN_A_NPE", justification = "This is a false positive in Java 11, should be removed")
public class KeyResource {
    private static final User ANONYMOUS = new User("anonymous", null);
    private static final String ACCESS_CONTROL_EXPOSE_HEADERS = "Access-Control-Expose-Headers";
    private static final String COMMA_REGEX = ",";
    private static final String ACCESS_CONTROL_REQUEST_METHOD = "Access-Control-Request-Method";
    private static final String LOGGED_IN_AND_ACCESSED_KEY = "{} logged in and accessed key {} in {}";
    private static final String RESOURCE_IS_DENIED_FOR_USER = "Resource {} in {} is denied for user {}";
    private static final String UTF_8 = "utf-8";
    private static final Logger LOG = LoggerFactory.getLogger(KeyResource.class);
    private final String defaultRef;
    private final Storage storage;
    private final KeyAdminAuthenticator addKeyAuthenticator;
    private final APIHelper helper;
    private final boolean cors;
    private final HashService hashService;
    @Inject
    private ExecutorService executor;

    public KeyResource(final Storage storage, final KeyAdminAuthenticator adminKeyAuthenticator, final boolean cors, final String defaultBranch, 
            final HashService hashService) {
        this.storage = Objects.requireNonNull(storage);
        this.addKeyAuthenticator = Objects.requireNonNull(adminKeyAuthenticator);
        this.helper = new APIHelper(LOG);
        this.cors = cors;
        this.defaultRef = Objects.requireNonNull(defaultBranch);
        this.hashService = Objects.requireNonNull(hashService);
    }

    @GET
    @Timed(name = "get_storage_time")
    @Metered(name = "get_storage_counter")
    @ExceptionMetered(name = "get_storage_exception")
    @Path("{key : .+}")
    public void get(@Suspended AsyncResponse asyncResponse,
            final @PathParam("key") String key,
            final @QueryParam("ref") String askedRef,
            final @Auth Optional<User> userHolder,
            final @Context HttpHeaders headers,
            final @Context HttpServletResponse response) {
        APIHelper.checkRef(askedRef);
        final String ref = APIHelper.setToDefaultRefIfNull(askedRef, defaultRef);
        CompletableFuture.supplyAsync(() -> helper.checkIfKeyExist(key, ref, storage), executor)
                .thenCompose(s -> s)
                .thenApplyAsync(storeInfo -> {
                    final EntityTag tag = new EntityTag(storeInfo.getVersion());
                    final Response noChange = APIHelper.checkETag(headers, tag);
                    final MetaData data = storeInfo.getMetaData();
                    final Set<User> allowedUsers = data.getUsers();
                    final Set<Role> roles = data.getRead();
                    if (allowedUsers.isEmpty() && (roles == null || roles.isEmpty())) {
                        if (noChange != null) {
                            return noChange;
                        }
                        return buildResponse(storeInfo, tag, data, response);
                    }
                    final User user = userHolder.orElseThrow(() -> {
                        LOG.info("Resource {} in {} needs a user", key, ref);
                        return APIHelper.createAuthenticationChallenge(JITSTATIC_KEYUSER_REALM);
                    });
                    checkIfAllowed(key, user, allowedUsers, ref, storeInfo.getMetaData().getRead());
                    if (noChange != null) {
                        return noChange;
                    }
                    LOG.info(LOGGED_IN_AND_ACCESSED_KEY, user, key, ref);
                    return buildResponse(storeInfo, tag, data, response);
                }, executor).exceptionally(helper::execptionHandler).thenAcceptAsync(asyncResponse::resume, executor);
    }

    @GET
    @Produces({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML })
    public void getRootList(@Suspended AsyncResponse asyncResponse,
            final @QueryParam("ref") String ref,
            @QueryParam("recursive") boolean recursive,
            @QueryParam("light") final boolean light,
            final @Auth Optional<User> user) {
        getList(asyncResponse, "/", ref, recursive, light, user);
    }

    @GET
    @Timed(name = "get_list_time")
    @Metered(name = "get_list_counter")
    @ExceptionMetered(name = "get_list_exception")
    @Path("{key : .+/}")
    @Produces({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML })
    public void getList(@Suspended AsyncResponse asyncResponse,
            final @PathParam("key") String key,
            final @QueryParam("ref") String askedRef,
            @QueryParam("recursive") boolean recursive,
            @QueryParam("light") final boolean light,
            final @Auth Optional<User> userHolder) {
        APIHelper.checkRef(askedRef);
        final String ref = APIHelper.setToDefaultRefIfNull(askedRef, defaultRef);
        storage.getListForRef(List.of(Pair.of(key, recursive)), ref)
                .thenApplyAsync(list -> list.stream()
                        .filter(data -> {
                            final MetaData storageData = data.getRight().getMetaData();
                            final Set<User> allowedUsers = storageData.getUsers();
                            final Set<Role> keyRoles = storageData.getRead();
                            if (allowedUsers.isEmpty() && (keyRoles == null || keyRoles.isEmpty())) {
                                LOG.info(LOGGED_IN_AND_ACCESSED_KEY, userHolder.orElse(ANONYMOUS), data.getLeft(), ref);
                                return true;
                            }
                            if (!userHolder.isPresent()) {
                                return false;
                            }
                            final User user = userHolder.get();
                            if (isUserAllowed(ref, user, allowedUsers, keyRoles)) {
                                LOG.info(LOGGED_IN_AND_ACCESSED_KEY, user, data.getLeft(), ref);
                                return true;
                            }
                            return false;
                        }).collect(Collectors.toList()), executor)
                .thenApplyAsync(list -> {
                    if (list.isEmpty()) {
                        return Response.status(Status.NOT_FOUND).build();
                    }
                    return Response.ok(new KeyDataWrapper(list.stream()
                            .map(p -> light ? new KeyData(p.getLeft(), p.getRight()) : new KeyData(p))
                            .collect(Collectors.toList())))
                            .build();
                }, executor).exceptionally(helper::execptionHandler).thenAcceptAsync(asyncResponse::resume, executor);
    }

    @PUT
    @Timed(name = "put_storage_time")
    @Metered(name = "put_storage_counter")
    @ExceptionMetered(name = "put_storage_exception")
    @Path("{key : .+}")
    @Consumes({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML })
    public void modifyKey(@Suspended AsyncResponse asyncResponse,
            final @PathParam("key") String key,
            final @QueryParam("ref") String askedRef,
            final @Auth Optional<User> userholder,
            final @Validated @Valid @NotNull ModifyKeyData data,
            final @Context HttpServletRequest httpRequest,
            final @Context Request request,
            final @Context HttpHeaders headers) {
        // All resources without a user cannot be modified with this method. It has to
        // be done through directly changing the file in the Git repository.
        final User user = userholder.orElseThrow(() -> APIHelper.createAuthenticationChallenge(JITSTATIC_KEYADMIN_REALM));
        APIHelper.checkValidRef(askedRef);
        final String ref = APIHelper.setToDefaultRefIfNull(askedRef, defaultRef);
        CompletableFuture.supplyAsync(() -> {
            APIHelper.checkHeaders(headers);
            return helper.checkIfKeyExist(key, ref, storage);
        }, executor)
                .thenComposeAsync(s -> s)
                .thenApplyAsync(storeInfo -> {
                    final Set<User> allowedUsers = storeInfo.getMetaData().getUsers();
                    final Set<Role> roles = storeInfo.getMetaData().getWrite();
                    final boolean isAuthenticated = addKeyAuthenticator.authenticate(user, ref);
                    if (allowedUsers.isEmpty() && (roles == null || roles.isEmpty()) && !isAuthenticated) {
                        throw new WebApplicationException(Status.BAD_REQUEST);
                    }
                    if (!(isAuthenticated || allowedUsers.contains(user) || APIHelper.isKeyUserAllowed(storage, hashService, user, ref, roles))) {
                        LOG.info(RESOURCE_IS_DENIED_FOR_USER, key, ref, user);
                        throw new WebApplicationException(Status.FORBIDDEN);
                    }
                    final String currentVersion = storeInfo.getVersion();
                    final EntityTag entityTag = new EntityTag(currentVersion);
                    final ResponseBuilder response = request.evaluatePreconditions(entityTag);
                    if (response != null) {
                        throw new WebApplicationException(response.header(HttpHeaders.CONTENT_ENCODING, UTF_8).tag(entityTag).build());
                    }
                    return currentVersion;
                }, executor)
                .thenApplyAsync(currentVersion -> storage.putKey(key, ref, data.getData(), currentVersion, new CommitMetaData(data.getUserInfo(), data.getUserMail(), data
                                .getMessage(), user.getName(), APIHelper.compileUserOrigin(user, httpRequest))), executor)
                .thenComposeAsync(Function.identity())
                .thenApplyAsync(result -> {
                    if (result == null) {
                        throw new WebApplicationException(Status.NOT_FOUND);
                    }
                    if (result.isRight()) {
                        throw new WebApplicationException(Status.PRECONDITION_FAILED);
                    }
                    final String newVersion = result.getLeft();

                    if (newVersion == null) {
                        throw new WebApplicationException(Status.NOT_FOUND);
                    }
                    LOG.info("{} logged in and modified key {} in {}", user, key, ref);
                    return Response.ok().tag(new EntityTag(newVersion)).header(HttpHeaders.CONTENT_ENCODING, UTF_8).build();
                }, executor).exceptionally(helper::exceptionHandlerPUTAPI).thenAcceptAsync(asyncResponse::resume, executor);
    }

    @POST
    @Timed(name = "post_storage_time")
    @Metered(name = "post_storage_counter")
    @ExceptionMetered(name = "post_storage_exception")
    @Path("{key : .+}")
    @Consumes({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML })
    @Produces({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML, MediaType.TEXT_PLAIN })
    public void addKey(@Suspended AsyncResponse asyncResponse,
            final @NotNull @PathParam("key") String key,
            final @QueryParam("ref") String askedRef,
            final @Validated @Valid @NotNull AddKeyData data,
            final @Context HttpServletRequest httpRequest,
            final @Auth Optional<User> userHolder) throws JsonParseException, JsonMappingException, IOException {
        final User user = userHolder.orElseThrow(() -> APIHelper.createAuthenticationChallenge(JITSTATIC_KEYADMIN_REALM));
        APIHelper.checkValidRef(askedRef);
        final String ref = APIHelper.setToDefaultRefIfNull(askedRef, defaultRef);
        CompletableFuture.runAsync(() -> {
            if (!addKeyAuthenticator.authenticate(user, ref)) {
                try {
                    final io.jitstatic.auth.UserData userData = storage.getUser(user.getName(), ref, JITSTATIC_KEYUSER_REALM);
                    if (userData == null || !hashService.hasSamePassword(userData, user.getPassword())) {
                        LOG.info(RESOURCE_IS_DENIED_FOR_USER, key, ref, user);
                        throw new WebApplicationException(Status.FORBIDDEN);
                    }
                } catch (RefNotFoundException e) {
                    throw new WebApplicationException(Status.FORBIDDEN);
                }
            }
        }, executor)
                .thenComposeAsync(ignore -> storage.getKey(key, ref).exceptionally(helper.keyExceptionHandler(Optional::empty)), executor)
                .thenApplyAsync(storeInfo -> {
                    if (storeInfo.isPresent()) {
                        throw new WebApplicationException(key + " already exist in " + ref, Status.CONFLICT);
                    }
                    return storage.addKey(key, ref, data.getData(), data
                            .getMetaData(), new CommitMetaData(data.getUserInfo(), data.getUserMail(), data.getMessage(), user.getName(), APIHelper.compileUserOrigin(user, httpRequest)));
                }, executor)
                .thenComposeAsync(s -> s, executor)
                .thenApplyAsync(version -> {
                    LOG.info("{} logged in and added key {} in {}", user, key, ref);
                    return Response.ok().tag(new EntityTag(version)).header(HttpHeaders.CONTENT_ENCODING, UTF_8).build();
                }, executor)
                .exceptionally(helper::exceptionHandlerPOSTAPI)
                .thenAcceptAsync(asyncResponse::resume, executor);
    }

    @DELETE
    @Path("{key : .+}")
    @Timed(name = "delete_storage_time")
    @Metered(name = "delete_storage_counter")
    @ExceptionMetered(name = "delete_storage_exception")
    public void delete(@Suspended AsyncResponse asyncResponse,
            final @PathParam("key") String key,
            final @QueryParam("ref") String askedRef,
            final @Auth Optional<User> userHolder,
            final @Context HttpServletRequest httpRequest,
            final @Context HttpHeaders headers) {
        final User user = userHolder.orElseThrow(() -> APIHelper.createAuthenticationChallenge(JITSTATIC_KEYADMIN_REALM));
        APIHelper.checkValidRef(askedRef);
        final String ref = APIHelper.setToDefaultRefIfNull(askedRef, defaultRef);
        CompletableFuture.supplyAsync(() -> helper.checkIfKeyExist(key, ref, storage), executor)
                .thenCompose(s -> s)
                .thenApplyAsync(storeInfo -> {
                    final String userHeader = notEmpty(headers, X_JITSTATIC_NAME);
                    final String message = notEmpty(headers, X_JITSTATIC_MESSAGE);
                    final String userMail = notEmpty(headers, X_JITSTATIC_MAIL);

                    final Set<User> allowedUsers = storeInfo.getMetaData().getUsers();
                    final Set<Role> roles = storeInfo.getMetaData().getWrite();

                    if (!isUserAllowed(ref, user, allowedUsers, roles)) {
                        if (allowedUsers.isEmpty() && (roles == null || roles.isEmpty())) {
                            throw new WebApplicationException(Status.BAD_REQUEST);
                        }
                        LOG.info(RESOURCE_IS_DENIED_FOR_USER, key, ref, user);
                        throw new WebApplicationException(Status.FORBIDDEN);
                    }
                    return storage.delete(key, ref, new CommitMetaData(userHeader, userMail, message, user.getName(), APIHelper.compileUserOrigin(user, httpRequest)));
                }, executor)
                .thenCompose(c -> c)
                .thenApplyAsync(ignore -> {
                    LOG.info("{} logged in and deleted key {} in {}", user, key, ref);
                    return Response.ok().build();
                }, executor).exceptionally(helper::execptionHandler).thenAcceptAsync(asyncResponse::resume, executor);
    }

    @OPTIONS
    @Path("{key : .+}")
    @Timed(name = "options_storage_time")
    @Metered(name = "options_storage_counter")
    @ExceptionMetered(name = "options_storage_exception")
    public void options(final @PathParam("key") String key,
            final @QueryParam("ref") String ref,
            final @Context HttpServletRequest request) {
        if (!cors) {
            return;
        }
        final List<String> declared = new ArrayList<>();
        if (requestingDelete(request.getHeader(ACCESS_CONTROL_REQUEST_METHOD))) {
            if (ref != null && ref.startsWith("refs/tags/")) {
                return;
            }
            declared.addAll(List.of(X_JITSTATIC_NAME, X_JITSTATIC_MESSAGE, X_JITSTATIC_MAIL));
        }

        final Pair<MetaData, String> metaKey = storage.getMetaKey(key, ref).join();
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

    private Response buildResponse(final StoreInfo storeInfo,
            final EntityTag tag,
            final MetaData data,
            final HttpServletResponse response) {
        final StreamingOutput so = output -> {
            try (InputStream is = storeInfo.getStreamProvider().getInputStream()) {
                is.transferTo(output);
            }
        };
        final ResponseBuilder responseBuilder = Response.ok(so)
                .header(HttpHeaders.CONTENT_TYPE, data.getContentType())
                .header(HttpHeaders.CONTENT_ENCODING, UTF_8)
                .tag(tag);
        extractResponseHeaders(data, response, responseBuilder);
        return responseBuilder.build();
    }

    void checkIfAllowed(final String key,
            final User user,
            final Set<User> allowedUsers,
            final String ref,
            final Set<Role> keyRoles) {
        if (!isUserAllowed(ref, user, allowedUsers, keyRoles)) {
            LOG.info(RESOURCE_IS_DENIED_FOR_USER, key, ref, user);
            throw new WebApplicationException(Status.FORBIDDEN);
        }
    }

    private boolean requestingDelete(final String requestMethod) {
        if (requestMethod == null) {
            return false;
        }
        final String[] methods = requestMethod.split(COMMA_REGEX);
        for (String m : methods) {
            if ("DELETE".equalsIgnoreCase(m.trim())) {
                return true;
            }
        }
        return false;
    }

    private boolean isUserAllowed(final String ref,
            final User user,
            final Set<User> allowedUsers,
            final Set<Role> roles) {
        return allowedUsers.contains(user) || APIHelper.isKeyUserAllowed(storage, hashService, user, ref, roles) || addKeyAuthenticator.authenticate(user, ref);
    }

    private String notEmpty(final HttpHeaders httpHeaders,
            final String headerName) {
        final String headers = httpHeaders.getHeaderString(headerName);
        if (headers == null || headers.isEmpty()) {
            throw new WebApplicationException("Missing " + headerName, Status.BAD_REQUEST);
        }
        return headers;
    }

    private void extractResponseHeaders(final MetaData data,
            final HttpServletResponse response,
            final ResponseBuilder responseBuilder) {
        final List<HeaderPair> headers = data.getHeaders();
        if (headers != null) {
            final Set<String> declaredHeaders = new HashSet<>();
            for (HeaderPair headerPair : headers) {
                final String header = headerPair.getHeader();
                responseBuilder.header(header, headerPair.getValue());
                declaredHeaders.add(header.toLowerCase(Locale.ROOT));
            }
            final Collection<String> accessControlExposeHeaders = response.getHeaders(ACCESS_CONTROL_EXPOSE_HEADERS);
            if (cors && !declaredHeaders.isEmpty() && (accessControlExposeHeaders != null && !accessControlExposeHeaders.isEmpty())) {
                final Set<String> exposedHeaders = accessControlExposeHeaders.stream()
                        .map(aceh -> Arrays.stream(aceh.split(","))
                                .map(deh -> deh.trim().toLowerCase(Locale.ROOT)))
                        .flatMap(s -> s)
                        .collect(Collectors.toSet());
                declaredHeaders.addAll(exposedHeaders);
                response.setHeader(ACCESS_CONTROL_EXPOSE_HEADERS, declaredHeaders.stream().collect(Collectors.joining(",")));
            }
        }
    }
}
