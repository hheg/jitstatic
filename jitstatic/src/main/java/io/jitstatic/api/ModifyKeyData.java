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

import javax.validation.constraints.NotNull;

import org.hibernate.validator.constraints.NotBlank;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import io.jitstatic.source.ObjectStreamProvider;

public class ModifyKeyData {

    @NotNull
    private final ObjectStreamProvider data;

    @NotBlank
    private final String message;

    @NotBlank
    private final String userMail;

    @NotBlank
    private final String userInfo;

    @JsonCreator
    public ModifyKeyData(
            @JsonSerialize(using = StreamingSerializer.class) @JsonDeserialize(using = StreamingDeserializer.class) @JsonProperty("data") final ObjectStreamProvider data,
            @JsonProperty("message") final String message, @JsonProperty("userInfo") final String userInfo,
            @JsonProperty("userMail") final String userMail) {
        this.data = data;
        this.message = message;
        this.userMail = userMail;
        this.userInfo = userInfo;
    }

    public ObjectStreamProvider getData() {
        return data;
    }

    public String getMessage() {
        return message;
    }

    public String getUserMail() {
        return userMail;
    }

    public String getUserInfo() {
        return userInfo;
    }
}
