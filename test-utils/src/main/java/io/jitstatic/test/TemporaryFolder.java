package io.jitstatic.test;

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

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

public class TemporaryFolder {

    private final ThreadLocal<TemporaryFolderFactory> tlfactory;

    public TemporaryFolder() {
        this.tlfactory = new ThreadLocal<>() {
            protected TemporaryFolderFactory initialValue() {
                try {
                    return new TemporaryFolderFactory();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            };
        };
    }

    public File createTemporaryDirectory() throws IOException {
        return tlfactory.get().getTemporaryDirectory();
    }

    void destroy() {
        try {
            tlfactory.get().destroy();
        } catch (IOException ignore) {
        }
    }

    public Path createTemporaryFile() throws IOException {
        return tlfactory.get().getTemporaryFile().toPath();
    }

    void cleanup() {
        try {
            tlfactory.get().cleanup();
        } catch (IOException ignore) {
        }
    }
}
