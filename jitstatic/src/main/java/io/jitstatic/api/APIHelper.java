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
import java.util.function.Supplier;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.EntityTag;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.lib.Constants;
import org.slf4j.Logger;

import io.jitstatic.UpdateFailedException;
import io.jitstatic.hosted.KeyAlreadyExist;
import io.jitstatic.utils.VersionIsNotSame;
import io.jitstatic.utils.WrappingAPIException;

class APIHelper {

    private final Logger log;

    public APIHelper(final Logger log) {
        this.log = log;
    }

    <T> T unwrapWithPOSTApi(final Supplier<T> add) {
        try {
            return add.get();
        } catch (final Exception e) {
            if (e instanceof WrappingAPIException) {
                final Throwable apiException = e.getCause();
                if (apiException instanceof KeyAlreadyExist) {
                    throw new WebApplicationException(apiException.getMessage(), Status.CONFLICT);
                } else if (apiException instanceof RefNotFoundException) {
                    // Error message here means that the branch is not found.
                    throw new WebApplicationException("Branch is not found", Status.NOT_FOUND);
                } else if (apiException instanceof IOException) {
                    log.error("IO Error while executing command", e);
                    throw new WebApplicationException("Data is malformed", 422);
                }
            }
            log.error("Error while unwrapping future", e);
            throw new WebApplicationException(Status.INTERNAL_SERVER_ERROR);
        }
    }

    void checkHeaders(final HttpHeaders headers) {
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

    void checkValidRef(final String ref) {
        if (ref != null) {
            checkRef(ref);
            if (ref.startsWith(Constants.R_TAGS)) {
                throw new WebApplicationException(Status.FORBIDDEN);
            }
        }
    }

    <T> T unwrapWithPUTApi(final Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (final Exception e) {
            if (e instanceof WrappingAPIException) {
                final Throwable apiException = e.getCause();
                if (apiException instanceof UnsupportedOperationException) {
                    throw new WebApplicationException(Status.NOT_FOUND);
                }
                if (apiException instanceof RefNotFoundException) {
                    return null;
                }
                if (apiException instanceof VersionIsNotSame) {
                    throw new WebApplicationException(apiException.getLocalizedMessage(), Status.PRECONDITION_FAILED);
                }
                if (apiException instanceof KeyAlreadyExist) {
                    throw new WebApplicationException(apiException.getLocalizedMessage(), Status.CONFLICT);
                }
            } else if (e instanceof UpdateFailedException) {
                throw new WebApplicationException("Key is being updated", Status.PRECONDITION_FAILED);
            }
            log.error("Error while unwrapping future", e);
            throw new WebApplicationException(Status.INTERNAL_SERVER_ERROR);
        }
    }

    <T> Optional<T> unwrap(final Supplier<Optional<T>> supplier) {
        try {
            return supplier.get();
        } catch (final Exception e) {
            log.error("Error while unwrapping future", e);
            return Optional.empty();
        }
    }

    void checkRef(final String ref) {
        if (ref != null) {
            if (!(ref.startsWith(Constants.R_HEADS) ^ ref.startsWith(Constants.R_TAGS))) {
                throw new WebApplicationException(Status.NOT_FOUND);
            }
        }
    }

    Response checkETag(final HttpHeaders headers, final EntityTag tag) {
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
}
