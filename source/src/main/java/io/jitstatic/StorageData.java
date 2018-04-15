package io.jitstatic;

import java.util.List;

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

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;

import org.hibernate.validator.constraints.NotEmpty;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.jitstatic.auth.User;

@SuppressFBWarnings(justification = "Equals used here is not dodgy code", value = { "EQ_UNUSUAL" })
public class StorageData {
    @NotNull
    @Valid
    private final Set<User> users;
    @NotNull
    @NotEmpty
    private final String contentType;
    private final boolean isProtected;
    private final boolean hidden;
    @Valid
    private final List<HeaderPair> headers;

    @JsonCreator
    public StorageData(final @JsonProperty("users") Set<User> users, final @JsonProperty("contentType") String contentType,
            final @JsonProperty("protected") boolean isProtected, final @JsonProperty("hidden") boolean hidden,
            final @JsonProperty("headers") List<HeaderPair> headers) {
        this.users = Objects.requireNonNull(users, "metadata is missing users field");
        this.contentType = contentType == null ? "application/json" : contentType;
        this.isProtected = isProtected;
        this.hidden = hidden;
        this.headers = headers;
    }

    public Set<User> getUsers() {
        return users;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + users.hashCode();
        result = prime * result + getContentType().hashCode();
        return result;
    }

    @Override
    public boolean equals(final Object other) {
        return Optional.ofNullable(other).filter(that -> that instanceof StorageData).map(that -> (StorageData) that)
                .filter(that -> Objects.equals(this.users, that.users)).filter(that -> Objects.equals(this.getContentType(), that.getContentType()))
                .isPresent();
    }

    public String getContentType() {
        return contentType;
    }

    public boolean isProtected() {
        return isProtected;
    }

    public boolean isHidden() {
        return hidden;
    }

    public List<HeaderPair> getHeaders() {
        return headers;
    }
}
