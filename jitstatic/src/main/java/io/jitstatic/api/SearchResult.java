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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.jitstatic.hosted.StoreInfo;
import io.jitstatic.utils.Pair;

@SuppressFBWarnings(value = { "EI_EXPOSE_REP", "EI_EXPOSE_REP2" }, justification = "Want to avoid copying the array twice")
public class SearchResult {

    private final String key;
    private final String ref;
    private final String tag;
    private final String contentType;
    private final byte[] content;

    public SearchResult(final Pair<String, StoreInfo> data, final String ref) {
        this(data.getLeft(), data.getRight().getVersion(), data.getRight().getStorageData().getContentType(), data.getRight().getData(), ref);
    }

    @JsonCreator
    public SearchResult(@JsonProperty("key") final String key, @JsonProperty("tag") final String tag, @JsonProperty("contentType") final String contentType,
            @JsonProperty("content") final byte[] content, @JsonProperty("ref") final String ref) {
        this.tag = tag;
        this.contentType = contentType;
        this.content = content;
        this.key = key;
        this.ref = ref;
    }

    public String getTag() {
        return tag;
    }

    public String getContentType() {
        return contentType;
    }

    public byte[] getContent() {
        return content;
    }

    public String getKey() {
        return key;
    }

    public String getRef() {
        return ref;
    }

}
