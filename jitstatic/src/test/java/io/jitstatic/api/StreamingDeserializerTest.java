package io.jitstatic.api;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
import java.util.function.Supplier;

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.DeferredFileOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;

import io.jitstatic.source.ObjectStreamProvider;
import io.jitstatic.test.TemporaryFolder;
import io.jitstatic.test.TemporaryFolderExtension;

@ExtendWith(TemporaryFolderExtension.class)
public class StreamingDeserializerTest {

    private ObjectMapper mapper = new ObjectMapper();
    private StreamingDeserializer ds;
    private TemporaryFolder folder;

    @BeforeEach
    public void setup() {
        ds = new StreamingDeserializer();
    }

    @Test
    public void testSerializeObjectStreamProvider() throws IOException {
        String text = "the brown fox jumped over the fence";
        InputStream stream = new FeedingInputStream(1, get(text));
        JsonParser parser = mapper.getFactory().createParser(stream);
        DeserializationContext ctxt = mapper.getDeserializationContext();
        parser.nextToken();
        parser.nextToken();
        parser.nextToken();
        ObjectStreamProvider deserialized = ds.deserialize(parser, ctxt);
        assertNotNull(deserialized);
        try (InputStream is = deserialized.getInputStream();) {
            assertEquals(text + text, IOUtils.toString(is, StandardCharsets.UTF_8));
        }
        assertEquals((text + text).length(), deserialized.getSize());
    }

    @Test
    public void testSerializeLargeObjectStreamProvider() throws IOException {
        String text = "the brown fox jumped over the fence";
        InputStream stream = new FeedingInputStream(286_000, get(text));
        JsonParser parser = mapper.getFactory().createParser(stream);
        DeserializationContext ctxt = mapper.getDeserializationContext();
        parser.nextToken();
        parser.nextToken();
        parser.nextToken();
        ObjectStreamProvider deserialized = ds.deserialize(parser, ctxt);
        assertNotNull(deserialized);
        try (InputStream is = deserialized.getInputStream();) {
            assertTrue(IOUtils.contentEquals(new GeneratingInputStream(286_000, get(text)), is));
        }
        assertEquals(286_001 * 35, deserialized.getSize());
    }

    @Test
    public void testMountSerializerOnNotWritableFolder() throws IOException {
        File tmpDir = folder.createTemporaryDirectory();
        Path nowrite = tmpDir.toPath().resolve("nowrite");
        Set<PosixFilePermission> perms = PosixFilePermissions.fromString("r--------");
        FileAttribute<Set<PosixFilePermission>> attr = PosixFilePermissions.asFileAttribute(perms);
        Path createdFile = Files.createFile(nowrite, attr);
        assertThrows(IllegalArgumentException.class, () -> new StreamingDeserializer(createdFile.toFile()));
    }

    @Test
    public void testMountSerializerOnNotExistingAndNotWritableFolder() throws IOException {
        File tmpDir = folder.createTemporaryDirectory();
        Path nowrite = tmpDir.toPath().resolve("nowrite");
        Path dest = nowrite.resolve("dest");
        Set<PosixFilePermission> perms = PosixFilePermissions.fromString("r--------");
        FileAttribute<Set<PosixFilePermission>> attr = PosixFilePermissions.asFileAttribute(perms);
        assertNotNull(Files.createFile(nowrite, attr));
        assertThrows(IllegalArgumentException.class, () -> new StreamingDeserializer(dest.toFile()));
    }

    @Test
    public void testSerializeTooLargeObjectStreamProvider() throws IOException {
        File tmpDir = folder.createTemporaryDirectory();
        Path write = tmpDir.toPath().resolve("write");
        assertNotNull(Files.createFile(write));
        String text = "the brown fox jumped over the fence";
        InputStream stream = new FeedingInputStream(286_000, get(text));
        JsonParser parser = mapper.getFactory().createParser(stream);
        DeserializationContext ctxt = mapper.getDeserializationContext();
        parser.nextToken();
        parser.nextToken();
        parser.nextToken();
        ds = new StreamingDeserializer() {
            DeferredFileOutputStream getOutPutStream() {
                DeferredFileOutputStream deferredStream = super.getOutPutStream();
                DeferredFileOutputStream spy = Mockito.spy(deferredStream);
                Mockito.when(spy.getByteCount()).thenReturn((long) Integer.MAX_VALUE);
                return spy;
            };
        };
        assertThrows(MismatchedInputException.class, () -> ds.deserialize(parser, ctxt));
    }

    private Supplier<byte[]> get(String s) {
        return () -> s.getBytes(StandardCharsets.UTF_8);
    }

    private static class GeneratingInputStream extends InputStream {

        private final long eof;
        private long loops = 0;
        private int idx = 0;
        private byte[] data;
        private Supplier<byte[]> filler;

        public GeneratingInputStream(long limit, Supplier<byte[]> filler) {
            this.eof = limit;
            this.filler = filler;
            this.data = filler.get();
        }

        @Override
        public int read() throws IOException {
            if (idx == data.length) {
                if (loops == eof) {
                    return -1;
                }
                loops++;
                idx = 0;
                this.data = filler.get();
            }
            return data[idx++];
        }
    }

    private static class Base64EncodingInputStream extends InputStream {
        private InputStream is;
        private byte[] buf = new byte[3];
        private int idx = -1;
        private byte[] encoded;

        public Base64EncodingInputStream(InputStream is) {
            this.is = is;
        }

        @Override
        public int read() throws IOException {
            if (encoded == null || idx == encoded.length) {
                int read = is.read(buf);
                if (read == -1) {
                    return -1;
                }
                encoded = Base64.getEncoder().encode(read < 3 ? Arrays.copyOf(buf, read) : buf);
                idx = 0;
            }
            return encoded[idx++];
        }
    }

    private static class FeedingInputStream extends InputStream {

        private byte[] start = "{\"f\":\"".getBytes(StandardCharsets.UTF_8);
        private byte[] end = "\"}".getBytes(StandardCharsets.UTF_8);
        private int startidx = 0;
        private int endidx = 0;
        private InputStream gis;

        public FeedingInputStream(long limit, Supplier<byte[]> filler) {
            gis = new Base64EncodingInputStream(new GeneratingInputStream(limit, filler));
        }

        @Override
        public int read() throws IOException {
            if (startidx < start.length) {
                return start[startidx++];
            }
            int read = gis.read();
            if (read > -1) {
                return read;
            }
            if (endidx >= end.length) {
                return -1;
            }
            return end[endidx++];
        }

    }

}
