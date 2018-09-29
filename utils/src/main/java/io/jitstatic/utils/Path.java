package io.jitstatic.utils;

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
import java.util.stream.Collectors;

public class Path {

    private final String[] pathElements;
    private final boolean isDirectory;

    private Path(final String path) {
        this.pathElements = Objects.requireNonNull(path).split("/");
        this.isDirectory = path.length() == 0 || (path.charAt(path.length() - 1) == '/');
    }

    public static Path of(final String path) {
        return new Path(path);
    }

    public boolean isDirectory() {
        return isDirectory;
    }

    public String getLastElement() {
        return isDirectory ? "" : pathElements[pathElements.length - 1];
    }

    public String getParentElements() {
        return Arrays.stream(pathElements).limit(pathElements.length - 1L).map(s -> s + "/").collect(Collectors.joining(""));
    }

}
