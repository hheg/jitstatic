package io.jitstatic.utils;

/*-
 * #%L
 * jitstatic
 * %%
 * Copyright (C) 2017 - 2019 H.Hegardt
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

import java.io.File;
import java.util.Objects;

public class FilesUtils {

    public static void checkOrCreateFolder(final File workingDirectory) {
        if (!Objects.requireNonNull(workingDirectory).exists() && !workingDirectory.mkdirs()) {
            throw new IllegalArgumentException(String.format("Folder %s doesn't exist and can't be created", workingDirectory.getAbsolutePath()));
        }
        if (!workingDirectory.canWrite()) {
            throw new IllegalArgumentException(String.format("Folder %s exist but can't be written to", workingDirectory.getAbsolutePath()));
        }
    }

}
