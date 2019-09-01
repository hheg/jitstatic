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

import static java.nio.file.FileVisitResult.CONTINUE;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

public class TemporaryFolderFactory {
    private final Path rootFolder;
    private static final String BASENAME = "junit";

    public TemporaryFolderFactory() throws IOException {
        rootFolder = Files.createTempDirectory(BASENAME);
    }

    public File getTemporaryDirectory() throws IOException {
        return Files.createTempDirectory(rootFolder, BASENAME).toFile();
    }

    public File getTemporaryFile() throws IOException {
        return Files.createTempFile(rootFolder, null, null).toFile();
    }

    public void destroy() throws IOException {
        delete(true);
    }

    public void cleanup() throws IOException {
        delete(false);
    }

    private void delete(boolean destroy) throws IOException {
        if (rootFolder.toFile().exists()) {
            Files.walkFileTree(rootFolder, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    return delete(file);
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                    if (!directory.equals(rootFolder)) {
                        return delete(directory);
                    }
                    return CONTINUE;
                }

                private FileVisitResult delete(Path file) throws IOException {
                    Files.delete(file);
                    return CONTINUE;
                }
            });
            if (destroy && Files.exists(rootFolder)) {
                Files.delete(rootFolder);
            }
        }
    }
}
