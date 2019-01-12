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

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import org.apache.commons.io.input.ProxyInputStream;
import org.apache.commons.io.output.DeferredFileOutputStream;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import io.jitstatic.source.ObjectStreamProvider;

public class StreamingDeserializer extends JsonDeserializer<ObjectStreamProvider> {

    private static final int DEFAULT_MAX_BYTE_SIZE = 10_000_000;
    private static final int MAX_FILE_SIZE = Integer.MAX_VALUE - 1;
    private static final File TMP_DIR = Paths.get(System.getProperty("java.io.tmpdir"), "jitstatic").toFile();
    static {
        checkOrCreateFolder(TMP_DIR);
    }
    private final int threshold;
    private final File workingDirectory;

    public StreamingDeserializer() {
        this(DEFAULT_MAX_BYTE_SIZE, TMP_DIR);
    }

    public StreamingDeserializer(final int threshold) {
        this(threshold, TMP_DIR);
    }

    public StreamingDeserializer(final File workingDirectory) {
        this(DEFAULT_MAX_BYTE_SIZE, workingDirectory);
    }

    public StreamingDeserializer(final int threshold, final File workingDirectory) {
        this.threshold = threshold < 0 ? DEFAULT_MAX_BYTE_SIZE : threshold;
        checkOrCreateFolder(workingDirectory);
        this.workingDirectory = workingDirectory;
    }

    private static void checkOrCreateFolder(final File workingDirectory) {
        if (!Objects.requireNonNull(workingDirectory).exists() && !workingDirectory.mkdirs()) {
            throw new IllegalArgumentException(String.format("Folder %s doesn't exist and can't be created", workingDirectory.getAbsolutePath()));
        }
        if (!workingDirectory.canWrite()) {
            throw new IllegalArgumentException(String.format("Folder %s exist but can't be written to", workingDirectory.getAbsolutePath()));
        }
    }

    DeferredFileOutputStream getOutPutStream() {
        return new DeferredFileOutputStream(threshold, "defer", "dat", workingDirectory) {
            @Override
            protected void checkThreshold(int count) throws IOException {
                if (getByteCount() >= MAX_FILE_SIZE) {
                    throw new FileTooLargeException();
                }
                super.checkThreshold(count);
            }
        };
    }

    @Override
    public ObjectStreamProvider deserialize(final JsonParser p, final DeserializationContext ctxt) throws IOException, JsonProcessingException {
        final DeferredFileOutputStream dfos = getOutPutStream();
        try {
            p.readBinaryValue(dfos);
        } catch (FileTooLargeException ftle) {
            ctxt.reportInputMismatch(ObjectStreamProvider.class, "Input is too large > " + MAX_FILE_SIZE);
        } finally {
            dfos.close();
        }
        return new ObjectStreamProvider() {
            @Override
            public long getSize() throws IOException {
                return dfos.getByteCount();
            }

            @Override
            public InputStream getInputStream() throws IOException {
                if (dfos.isInMemory()) {
                    return new ByteArrayInputStream(dfos.getData());
                }
                return new ProxyInputStream(Files.newInputStream(dfos.getFile().toPath(), StandardOpenOption.READ)) {
                    @Override
                    public void close() throws IOException {
                        try {
                            super.close();
                        } finally {
                            CompletableFuture.runAsync(() -> {
                                try {
                                    Path path = dfos.getFile().toPath();
                                    if (!Files.deleteIfExists(path)) {
                                        Files.delete(path);
                                    }
                                } catch (IOException e) {
                                    // Ignore
                                }
                            });
                        }
                    }
                };
            }
        };
    }

    static class FileTooLargeException extends IOException {
        private static final long serialVersionUID = 1L;
    }

}
