package io.jitstatic.api;

import javax.validation.constraints.NotBlank;

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

public class SearchPath {

    @NotBlank
    private final String path;
    private final boolean recursively;
    @JsonCreator()
    public SearchPath(@JsonProperty("path") final String path, @JsonProperty("recursively") final boolean recursively) {
        this.path = path;
        this.recursively = recursively;
    }
    public String getPath() {
        return path;
    }
    public boolean isRecursively() {
        return recursively;
    }
}
