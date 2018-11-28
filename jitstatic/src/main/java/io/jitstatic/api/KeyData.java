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

import org.hibernate.validator.constraints.NotBlank;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import io.jitstatic.hosted.StoreInfo;
import io.jitstatic.source.ObjectStreamProvider;
import io.jitstatic.utils.Pair;


@JsonInclude(Include.NON_NULL)
public class KeyData {

    @NotBlank
    private final String tag;

    @NotBlank
    private final String type;
    
    @JsonSerialize(using = StreamingSerializer.class)
    private final ObjectStreamProvider data;

    @NotBlank
    private final String key;

    @JsonCreator
    public KeyData(@JsonProperty("key") final String key, @JsonProperty("type") final String type, @JsonProperty("tag") final String tag,
            @JsonDeserialize(using = StreamingDeserializer.class) @JsonProperty("data") ObjectStreamProvider provider) {
        this.type = type;
        this.tag = tag;
        this.key = key;
        this.data = provider;
    }

    public KeyData(final Pair<String, StoreInfo> p) {
        this(p.getLeft(), p.getRight().getMetaData().getContentType(), p.getRight().getVersion(), p.getRight().getStreamProvider());
    }

    public KeyData(final String key, final StoreInfo si) {
        this(key, si.getMetaData().getContentType(), si.getVersion(), null);
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

    public ObjectStreamProvider getData() {
        return data;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
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
        if (!key.equals(other.key))
            return false;
        if (!tag.equals(other.tag))
            return false;
        return (type.equals(other.type));
    }

    @Override
    public String toString() {
        return "KeyData [key=" + key + ", tag=" + tag + ", type=" + type + "]";
    }

}
