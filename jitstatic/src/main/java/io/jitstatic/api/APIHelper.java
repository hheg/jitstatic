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

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.SecurityContext;

import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.lib.Constants;
import org.slf4j.Logger;

import io.jitstatic.JitStaticConstants;
import io.jitstatic.MetaData;
import io.jitstatic.Role;
import io.jitstatic.UpdateFailedException;
import io.jitstatic.auth.User;
import io.jitstatic.hosted.StoreInfo;
import io.jitstatic.storage.KeyAlreadyExist;
import io.jitstatic.storage.Storage;
import io.jitstatic.utils.VersionIsNotSame;
import io.jitstatic.utils.WrappingAPIException;

class APIHelper {

    private static final String UNHANDLED_ERROR = "Unhandled error";
    private final Logger log;

    public APIHelper(final Logger log) {
        this.log = log;
    }

    Response exceptionHandlerPOSTAPI(final Throwable e) {
        if (e instanceof WebApplicationException) {
            WebApplicationException wae = (WebApplicationException) e;
            return wae.getResponse();
        }
        if (e instanceof CompletionException) {
            return exceptionHandlerPOSTAPI(e.getCause());
        }
        if (e instanceof WrappingAPIException) {
            final Throwable apiException = e.getCause();
            if (apiException instanceof KeyAlreadyExist) {
                return new WebApplicationException(apiException.getMessage(), Status.CONFLICT).getResponse();
            } else if (apiException instanceof RefNotFoundException) {
                return new WebApplicationException(apiException.getMessage(), Status.BAD_REQUEST).getResponse();
            } else if (apiException instanceof UnsupportedOperationException) {
                return new WebApplicationException(Status.METHOD_NOT_ALLOWED).getResponse();
            } else if (apiException instanceof IOException) {
                log.error("IO Error while executing command", e);
                return new WebApplicationException("Data is malformed", 422).getResponse();
            }
        }
        log.error(UNHANDLED_ERROR, e);
        return new WebApplicationException(Status.INTERNAL_SERVER_ERROR).getResponse();
    }

    static void checkHeaders(final HttpHeaders headers) {
        final List<String> header = headers.getRequestHeader(HttpHeaders.IF_MATCH);
        if (header == null || header.isEmpty()) {
            throw new WebApplicationException("Required headers are missing", Status.BAD_REQUEST);
        }
        boolean isValid = false;
        for (String headerValue : header) {
            isValid |= !headerValue.isEmpty();
        }
        if (!isValid) {
            throw new WebApplicationException("Header value is empty", Status.BAD_REQUEST);
        }
    }

    static void checkMutableRef(final String ref) {
        if (ref != null) {
            checkRef(ref);
            if (ref.startsWith(Constants.R_TAGS)) {
                throw new WebApplicationException(Status.FORBIDDEN);
            }
        }
    }

    Response execptionHandler(final Throwable e) {
        if (e instanceof WebApplicationException) {
            WebApplicationException wae = (WebApplicationException) e;
            return wae.getResponse();
        }
        if (e instanceof CompletionException) {
            return execptionHandler(e.getCause());
        }
        if (e instanceof WrappingAPIException) {
            final Throwable apiException = e.getCause();
            if (apiException instanceof UnsupportedOperationException) {
                return new WebApplicationException(Status.METHOD_NOT_ALLOWED).getResponse();
            }
        }
        log.error(UNHANDLED_ERROR, e);
        return new WebApplicationException(Status.INTERNAL_SERVER_ERROR).getResponse();
    }

    Response exceptionHandlerPUTAPI(Throwable e) {
        if (e instanceof WebApplicationException) {
            WebApplicationException wae = (WebApplicationException) e;
            return wae.getResponse();
        }
        if (e instanceof CompletionException) {
            return exceptionHandlerPUTAPI(e.getCause());
        }
        if (e instanceof WrappingAPIException) {
            final Throwable apiException = e.getCause();
            if (apiException instanceof UnsupportedOperationException) {
                return new WebApplicationException(Status.NOT_FOUND).getResponse();
            }
            if (apiException instanceof RefNotFoundException) {
                return new WebApplicationException(apiException.getMessage(), Status.BAD_REQUEST).getResponse();
            }
            if (apiException instanceof VersionIsNotSame) {
                return new WebApplicationException(apiException.getMessage(), Status.PRECONDITION_FAILED).getResponse();
            }
            if (apiException instanceof KeyAlreadyExist) {
                return new WebApplicationException(apiException.getMessage(), Status.CONFLICT).getResponse();
            }
            log.error(UNHANDLED_ERROR, e);
            return new WebApplicationException(Status.INTERNAL_SERVER_ERROR).getResponse();
        } else if (e instanceof UpdateFailedException) {
            return new WebApplicationException("Key is being updated", Status.PRECONDITION_FAILED).getResponse();
        } else {
            log.error(UNHANDLED_ERROR, e);
            return new WebApplicationException(Status.INTERNAL_SERVER_ERROR).getResponse();
        }
    }

    <T> Function<Throwable, T> keyExceptionHandler(final Supplier<T> alternative) {
        return t -> unwrap(t, alternative);
    }

    private <T> T unwrap(final Throwable t, final Supplier<T> alternative) {
        if (t instanceof CompletionException) {
            return unwrap(t.getCause(), alternative);
        } else if (t instanceof WrappingAPIException) {
            final Throwable cause = t.getCause();
            if (cause instanceof UnsupportedOperationException) {
                throw new WebApplicationException(Status.METHOD_NOT_ALLOWED);
            }
            if (cause instanceof RefNotFoundException) {
                throw new WebApplicationException(cause.getMessage(), Status.BAD_REQUEST);
            }
            log.error("Unknown api error", t);
            return alternative.get();
        }
        log.error(UNHANDLED_ERROR, t);
        return alternative.get();
    }

    static void checkRef(final String ref) {
        if (ref == null) {
            return;
        }
        if (!JitStaticConstants.isRef(ref)) {
            throw new WebApplicationException(String.format("Ref %s doesn't exit", ref), Status.BAD_REQUEST);
        }
    }

    static WebApplicationException createAuthenticationChallenge(final String realm) {
        return new WebApplicationException(Response.status(Status.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"" + realm + "\", charset=\"UTF-8\"").build());
    }

    CompletableFuture<StoreInfo> checkIfKeyExist(final String key, final String ref, final Storage storage) throws RefNotFoundException {
        return storage.getKey(key, ref)
                .exceptionally(this.keyExceptionHandler(Optional::empty))
                .thenApply(storeInfo -> storeInfo.orElseThrow(() -> new WebApplicationException(key, Status.NOT_FOUND)));
    }

    boolean canAdministrate(final User user, final Set<Role> writeRoles) {
        return writeRoles.isEmpty() && user.isAdmin();
    }

    void checkWritePermission(final String key, final User user, SecurityContext context, final String ref, final MetaData metaData) {
        final Set<Role> writeRoles = metaData.getWrite();
        if (!(APIHelper.isUserInRole(context, writeRoles) || (canAdministrate(user, writeRoles)))) {
            log.info(KeyResource.RESOURCE_IS_DENIED_FOR_USER, key, ref, user);
            throw new WebApplicationException(Status.FORBIDDEN);
        }
    }

    static String setToDefaultRefIfNull(final String ref, final String defaultRef) {
        return ref == null ? defaultRef : ref;
    }

    public static String compileUserOrigin(final User user, final HttpServletRequest req) {
        return user.getName() + "@" + req.getRemoteHost();
    }

    public static boolean isUserInRole(final SecurityContext context, final Set<Role> roles) {
        for (Role role : roles) {
            if (context.isUserInRole(role.getRole())) {
                return true;
            }
        }
        return false;
    }

}
