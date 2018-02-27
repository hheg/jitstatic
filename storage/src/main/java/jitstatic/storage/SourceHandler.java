package jitstatic.storage;

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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonParser.Feature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

import jitstatic.JitStaticConstants;
import jitstatic.StorageData;

class SourceHandler {
	private static final ObjectMapper MAPPER = new ObjectMapper().enable(Feature.ALLOW_COMMENTS);
	private static final ObjectWriter WRITER = MAPPER.writerWithDefaultPrettyPrinter();
	
	public byte[] readStorageData(final InputStream is, final String type) throws JsonParseException, JsonMappingException, IOException {
		Objects.requireNonNull(type);
		return readByteArray(is);
	}

	public StorageData readStorage(final InputStream storageStream) throws IOException {
		Objects.requireNonNull(storageStream);
		try (final JsonParser parser = MAPPER.getFactory().createParser(storageStream);) {
			return parser.readValueAs(StorageData.class);
		}
	}

	public byte[] readByteArray(final InputStream is) throws IOException {
		Objects.requireNonNull(is);
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		int nRead;
		byte[] data = new byte[4096];
		while ((nRead = is.read(data, 0, data.length)) != -1) {
			buffer.write(data, 0, nRead);
		}
		buffer.flush();
		return buffer.toByteArray();
	}
	// TODO Don't make this pass
	public byte[] formatData(final byte[] data, final String contentType) throws IOException {
		Objects.requireNonNull(data);
		Objects.requireNonNull(contentType);
		if (JitStaticConstants.APPLICATION_JSON.equals(contentType)) {
			return WRITER.writeValueAsString(MAPPER.readTree(data)).getBytes("UTF-8");
		}
		return data;
	}
}
