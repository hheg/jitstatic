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
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.EntityTag;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.lib.Constants;
import org.slf4j.Logger;

import io.jitstatic.JitStaticConstants;
import io.jitstatic.Role;
import io.jitstatic.UpdateFailedException;
import io.jitstatic.auth.User;
import io.jitstatic.auth.UserData;
import io.jitstatic.hosted.StoreInfo;
import io.jitstatic.storage.HashService;
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

    Response exceptionHandlerPOSTAPI(Throwable e) {
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
                RefNotFoundException rnfe = (RefNotFoundException) apiException;
                return new WebApplicationException(String.format("Branch %s is not found ", rnfe.getMessage()), Status.NOT_FOUND).getResponse();
            } else if (apiException instanceof UnsupportedOperationException) {
                return new WebApplicationException(Status.FORBIDDEN).getResponse();
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
            throw new WebApplicationException(Status.BAD_REQUEST);
        }
        boolean isValid = false;
        for (String headerValue : header) {
            isValid |= !headerValue.isEmpty();
        }
        if (!isValid) {
            throw new WebApplicationException(Status.BAD_REQUEST);
        }
    }

    static void checkValidRef(final String ref) {
        if (ref != null) {
            checkRef(ref);
            if (ref.startsWith(Constants.R_TAGS)) {
                throw new WebApplicationException(Status.FORBIDDEN);
            }
        }
    }

    Response execptionHandler(Throwable e) {
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
                return new WebApplicationException(Status.NOT_FOUND).getResponse();
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
            if (apiException instanceof UnsupportedOperationException || apiException instanceof RefNotFoundException) {
                return new WebApplicationException(Status.NOT_FOUND).getResponse();
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

    <T> T unwrap(final Supplier<T> supplier, final Supplier<T> alternative) {
        try {
            return supplier.get();
        } catch (WrappingAPIException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof UnsupportedOperationException) {
                throw new WebApplicationException(Status.FORBIDDEN);
            } if (cause instanceof RefNotFoundException) {
                throw new WebApplicationException(Status.NOT_FOUND);
            }
            log.error("Unknown api error", e);
            return alternative.get();
        } catch (Exception e) {
            log.error(UNHANDLED_ERROR, e);
            return alternative.get();
        }
    }

    static void checkRef(final String ref) {
        if (ref == null) {
            return;
        }
        if (!isRef(ref)) {
            throw new WebApplicationException(Status.NOT_FOUND);
        }
    }

    static boolean isRef(final String ref) {
        return ref != null && (ref.startsWith(Constants.R_HEADS) ^ ref.startsWith(Constants.R_TAGS));
    }

    static Response checkETag(final HttpHeaders headers, final EntityTag tag) {
        final List<String> requestHeaders = headers.getRequestHeader(HttpHeaders.IF_MATCH);
        if (requestHeaders == null) {
            return null;
        }
        if (requestHeaders.size() > 1) {
            throw new WebApplicationException(Status.BAD_REQUEST);
        }
        for (final String header : requestHeaders) {
            if (header.equals("\"" + tag.getValue() + "\"")) {
                return Response.notModified().tag(tag).build();
            }
        }
        return null;
    }

    static WebApplicationException createAuthenticationChallenge(String realm) {
        return new WebApplicationException(
                Response.status(Status.UNAUTHORIZED).header(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"" + realm + "\", charset=\"UTF-8\"").build());
    }

    StoreInfo checkIfKeyExist(final String key, final String ref, final Storage storage) {
        final Optional<StoreInfo> storeInfo = unwrap(() -> storage.getKey(key, ref), Optional::empty);
        return storeInfo.orElseThrow(() -> new WebApplicationException(key, Status.NOT_FOUND));
    }

    static String setToDefaultRefIfNull(final String ref, final String defaultRef) {
        return ref == null ? defaultRef : ref;
    }

    static boolean isKeyUserAllowed(final Storage storage, final HashService hashService, final User user, final String ref, Set<Role> keyRoles) {
        keyRoles = keyRoles == null ? Set.of() : keyRoles;
        try {
            final UserData userData = storage.getUser(user.getName(), ref, JitStaticConstants.JITSTATIC_KEYUSER_REALM);
            if (userData == null) {
                return false;
            }
            final Set<Role> userRoles = userData.getRoles();
            return (!keyRoles.stream().noneMatch(userRoles::contains) && hashService.hasSamePassword(userData, user.getPassword()));
        } catch (RefNotFoundException e) {
            return false;
        }
    }
}
