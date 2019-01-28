package io.jitstatic.auth;

import java.io.Serializable;

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

import java.util.Set;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.jitstatic.Role;
import io.jitstatic.auth.constraints.DuplicatedAuthenticationMethods;
import io.jitstatic.auth.constraints.HasPassword;
import io.jitstatic.auth.constraints.Warning;

@HasPassword
@DuplicatedAuthenticationMethods(payload = Warning.class)
public class UserData implements BasicAuthentication, Serializable {

    private static final long serialVersionUID = -8443071109393902012L;

    private final String basicPassword;

    @NotNull
    @Valid
    private final Set<Role> roles;

    private final String salt;

    private final String hash;

    @JsonCreator
    public UserData(@JsonProperty("roles") Set<Role> roles, @JsonProperty("basicPassword") String password, @JsonProperty("salt") String salt,
            @JsonProperty("hash") String hash) {
        this.basicPassword = password;
        this.roles = roles;
        this.salt = salt;
        this.hash = hash;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((basicPassword == null) ? 0 : basicPassword.hashCode());
        result = prime * result + ((hash == null) ? 0 : hash.hashCode());
        result = prime * result + ((roles == null) ? 0 : roles.hashCode());
        result = prime * result + ((salt == null) ? 0 : salt.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        UserData other = (UserData) obj;
        if (basicPassword == null) {
            if (other.basicPassword != null)
                return false;
        } else if (!basicPassword.equals(other.basicPassword))
            return false;
        if (hash == null) {
            if (other.hash != null)
                return false;
        } else if (!hash.equals(other.hash))
            return false;
        if (roles == null) {
            if (other.roles != null)
                return false;
        } else if (!roles.equals(other.roles))
            return false;
        if (salt == null) {
            if (other.salt != null)
                return false;
        } else if (!salt.equals(other.salt))
            return false;
        return true;
    }

    public Set<Role> getRoles() {
        return roles;
    }

    @Override
    public String getBasicPassword() {
        return basicPassword;
    }

    @Override
    public String getSalt() {
        return salt;
    }

    @Override
    public String getHash() {
        return hash;
    }

}
