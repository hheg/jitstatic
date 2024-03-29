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

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.jitstatic.MetaData;

public class ModifyMetaKeyData {

    @JsonProperty
    @NotBlank
    private String message;

    @JsonProperty
    @NotNull
    @Valid
    private MetaData metaData;

    @JsonProperty
    @NotBlank
    private String userMail;

    @JsonProperty
    @NotBlank
    private String userInfo;

    public String getMessage() {
        return message;
    }

    public void setMessage(final String message) {
        this.message = message;
    }

    public MetaData getMetaData() {
        return metaData;
    }

    public void setMetaData(final MetaData data) {
        this.metaData = data;
    }

    public String getUserMail() {
        return userMail;
    }

    public void setUserMail(final String userMail) {
        this.userMail = userMail;
    }

    public String getUserInfo() {
        return userInfo;
    }

    public void setUserInfo(final String userInfo) {
        this.userInfo = userInfo;
    }
}
