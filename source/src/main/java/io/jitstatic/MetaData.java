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
import io.jitstatic.constraints.IfNotEmpty;

@SuppressFBWarnings(justification = "Equals used here is not dodgy code", value = { "EQ_UNUSUAL" })
public class MetaData {

    @Valid
    @Deprecated
    @IfNotEmpty
    private final Set<User> users;
    @NotNull
    @NotEmpty
    private final String contentType;
    private final boolean isProtected;
    private final boolean hidden;
    @Valid
    private final List<HeaderPair> headers;
    @Valid
    @NotNull
    private final Set<Role> read;
    @Valid
    @NotNull
    private final Set<Role> write;

    @JsonCreator
    @Deprecated
    public MetaData(final @JsonProperty("users") Set<User> users, final @JsonProperty("contentType") String contentType,
            final @JsonProperty("protected") boolean isProtected, final @JsonProperty("hidden") boolean hidden,
            final @JsonProperty("headers") List<HeaderPair> headers, final @JsonProperty("read") Set<Role> read, final @JsonProperty("write") Set<Role> write) {
        this.users = users;
        this.contentType = contentType == null ? "application/json" : contentType;
        this.isProtected = isProtected;
        this.hidden = hidden;
        this.headers = headers;
        this.read = read;
        this.write = write;
    }

    public MetaData(final String contentType, final boolean isProtected, final boolean hidden, final List<HeaderPair> headers, final Set<Role> read,
            Set<Role> write) {
        this(Set.of(), contentType, isProtected, hidden, headers, Objects.requireNonNull(read), Objects.requireNonNull(write));
    }

    public MetaData(final Set<Role> read, final Set<Role> write) {
        this(null, false, false, List.of(), read, write);
    }

    @Override
    public int hashCode() {
        return Objects.hash(users, getContentType());
    }

    @Override
    public boolean equals(final Object other) {
        return Optional.ofNullable(other)
                .filter(that -> that instanceof MetaData)
                .map(that -> (MetaData) that)
                .filter(that -> Objects.equals(this.users, that.users))
                .filter(that -> Objects.equals(this.getContentType(), that.getContentType()))
                .isPresent();
    }
    
    @Deprecated
    public Set<User> getUsers() { return users; }

    public String getContentType() { return contentType; }

    public boolean isProtected() { return isProtected; }

    public boolean isHidden() { return hidden; }

    public List<HeaderPair> getHeaders() { return headers; }

    public Set<Role> getRead() { return read; }

    public Set<Role> getWrite() { return write; }

}
