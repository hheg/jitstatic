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

import java.util.List;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;

import org.hibernate.validator.constraints.NotBlank;
import org.hibernate.validator.constraints.NotEmpty;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class BulkSearch {

    @NotBlank
    private final String ref;
    @NotEmpty
    @NotNull
    @Valid
    private final List<SearchPath> paths;

    @JsonCreator
    public BulkSearch(@JsonProperty("ref") final String ref, @JsonProperty("paths") final List<SearchPath> paths) {
        this.ref = ref;
        this.paths = paths;
    }

    public String getRef() {
        return ref;
    }

    public List<SearchPath> getPaths() {
        return paths;
    }

}
