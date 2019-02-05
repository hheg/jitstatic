package io.jitstatic.api;

import org.hibernate.validator.constraints.NotBlank;

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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import io.jitstatic.hosted.StoreInfo;
import io.jitstatic.source.ObjectStreamProvider;
import io.jitstatic.utils.Pair;

public class SearchResult {
    @NotBlank
    private final String key;
    @NotBlank
    private final String ref;
    @NotBlank
    private final String tag;
    @NotBlank
    private final String contentType;

    @JsonSerialize(using = StreamingSerializer.class)
    private final ObjectStreamProvider content;

    public SearchResult(final Pair<String, StoreInfo> data, final String ref) {
        this(data.getLeft(), data.getRight().getVersion(), data.getRight().getMetaData().getContentType(), ref, data.getRight().getStreamProvider());
    }

    @JsonCreator
    public SearchResult(@JsonProperty("key") final String key, @JsonProperty("tag") final String tag, @JsonProperty("contentType") final String contentType,
            @JsonProperty("ref") final String ref,
            @JsonProperty("content") @JsonDeserialize(using = StreamingDeserializer.class) ObjectStreamProvider provider) {
        this.tag = tag;
        this.contentType = contentType;
        this.key = key;
        this.ref = ref;
        this.content = provider;
    }

    public String getTag() {
        return tag;
    }

    public String getContentType() {
        return contentType;
    }

    public ObjectStreamProvider getContent() {
        return content;
    }

    public String getKey() {
        return key;
    }

    public String getRef() {
        return ref;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((key == null) ? 0 : key.hashCode());
        result = prime * result + ((ref == null) ? 0 : ref.hashCode());
        result = prime * result + ((tag == null) ? 0 : tag.hashCode());
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
        SearchResult other = (SearchResult) obj;
        if (key == null) {
            if (other.key != null)
                return false;
        } else if (!key.equals(other.key))
            return false;
        if (ref == null) {
            if (other.ref != null)
                return false;
        } else if (!ref.equals(other.ref))
            return false;
        if (tag == null) {
            if (other.tag != null)
                return false;
        } else if (!tag.equals(other.tag))
            return false;
        return true;
    }
}
