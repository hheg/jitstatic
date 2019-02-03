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

import java.util.Set;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

import org.hibernate.validator.constraints.NotBlank;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.jitstatic.Role;
import io.jitstatic.api.constraints.Adding;

public class UserData {
    @Size(min = 1, max = 100)
    @NotBlank(groups = Adding.class)
    private final String basicPassword;

    @NotNull
    @Valid
    private final Set<Role> roles;

    @JsonCreator
    public UserData(@JsonProperty("roles") Set<Role> roles, @JsonProperty("basicPassword") String password) {
        this.basicPassword = password;
        this.roles = roles;
    }

    public UserData(io.jitstatic.auth.UserData userData) {
        this(userData.getRoles(), userData.getHash() != null ? null : userData.getBasicPassword());
    }

    public Set<Role> getRoles() {
        return roles;
    }

    public String getBasicPassword() {
        return basicPassword;
    }

}
