package jitstatic.api;

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
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

import org.hibernate.validator.constraints.NotEmpty;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jitstatic.StorageData;

@SuppressFBWarnings(value = { "EI_EXPOSE_REP", "EI_EXPOSE_REP2" }, justification = "Want to avoid copying the array twice")
public class AddKeyData {

    private static final String REFS_HEADS_MASTER = "refs/heads/master";

    @NotNull
    @NotEmpty
    private final String key;

    @NotNull
    @NotEmpty
    @Pattern(regexp = "^refs/heads/.+$")
    private final String branch;

    @NotNull
    @Size(min=1)
    private final byte[] data;

    @NotNull
    @Valid
    private final StorageData metaData;

    @NotNull
    @NotEmpty
    private final String message;

    @NotNull
    @NotEmpty
    private final String userMail;

    @NotNull
    @NotEmpty
    private final String userInfo;

    @JsonCreator
    public AddKeyData(@JsonProperty("key") final String key, @JsonProperty("branch") final String branch,
            @JsonProperty("data") final byte[] data, @JsonProperty("metaData") final StorageData metaData,
            @JsonProperty("message") final String message, @JsonProperty("userInfo") final String userInfo,
            @JsonProperty("userMail") final String userMail) {
        this.key = key;
        this.branch = (branch == null ? REFS_HEADS_MASTER : branch);
        this.data = data;
        this.metaData = metaData;
        this.message = message;
        this.userMail = userMail;
        this.userInfo = userInfo;
    }

    public String getKey() {
        return key;
    }

    public String getBranch() {
        return branch;
    }

    public byte[] getData() {
        return data;
    }

    public StorageData getMetaData() {
        return metaData;
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
