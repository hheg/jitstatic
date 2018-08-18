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

import java.util.Arrays;
import java.util.Objects;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

import org.hibernate.validator.constraints.NotBlank;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.jitstatic.hosted.StoreInfo;
import io.jitstatic.utils.Pair;

@SuppressFBWarnings(value = { "EI_EXPOSE_REP", "EI_EXPOSE_REP2" }, justification = "Want to avoid copying the array twice")
public class KeyData {

    @NotBlank
    private final String tag;

    @NotBlank
    private final String type;

    @Size(min = 1)
    @NotNull
    private final byte[] data;

    @NotBlank
    private final String key;

    @JsonCreator
    public KeyData(@JsonProperty("key") final String key, @JsonProperty("type") final String type, @JsonProperty("tag") final String tag,
            @JsonProperty("data") final byte[] data) {
        this.type = Objects.requireNonNull(type);
        this.data = Objects.requireNonNull(data);
        this.tag = Objects.requireNonNull(tag);
        this.key = Objects.requireNonNull(key);
    }

    public KeyData(final Pair<String, StoreInfo> p) {
        this(p.getLeft(), p.getRight().getStorageData().getContentType(), p.getRight().getVersion(), p.getRight().getData());
    }

    public String getKey() {
        return key;
    }

    public String getTag() {
        return tag;
    }

    public String getType() {
        return type;
    }

    public byte[] getData() {
        return data;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + Arrays.hashCode(data);
        result = prime * result + key.hashCode();
        result = prime * result + tag.hashCode();
        result = prime * result + type.hashCode();
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
        KeyData other = (KeyData) obj;
        if (!Arrays.equals(data, other.data))
            return false;
        if (!key.equals(other.key))
            return false;
        if (!tag.equals(other.tag))
            return false;
        if (!type.equals(other.type))
            return false;
        return true;
    }

    @Override
    public String toString() {
        return "KeyData [key=" + key + ", tag=" + tag + ", type=" + type + "]";
    }

}
