package jitstatic;

/*-
 * #%L
 * jitstatic
 * %%
 * Copyright (C) 2017 H.Hegardt
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

import java.io.IOException;
import java.io.InputStream;

import com.fasterxml.jackson.core.JsonParser.Feature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class SourceJSONParser {

	private static final ObjectMapper mapper = new ObjectMapper().enable(Feature.ALLOW_COMMENTS)
			.enable(Feature.STRICT_DUPLICATE_DETECTION);
	
	public void parse(InputStream bc) throws IOException {
		final JsonNode metaData = mapper.readValue(bc, JsonNode.class);
		
		final JsonNode usersNode = metaData.get("users");
		if(usersNode == null) {
			throw new StorageParseException("metadata is missing users node");
		}
		if(!usersNode.isArray()) {
			throw new StorageParseException("users node is not an array");
		}
		checkUsers(usersNode);
		
	}

	private void checkUsers(final JsonNode usersNode) throws StorageParseException {
		for (JsonNode userNode : usersNode) {
			checkUser(userNode);
		}
	}

	private void checkUser(JsonNode userNode) throws StorageParseException {
		final JsonNode userName = userNode.get("user");		
		if(userName == null) {
			throw new StorageParseException("metadata is missing user name");
		}
	}

	private static class StorageParseException extends IOException {

		private static final long serialVersionUID = 1774575933983877566L;

		public StorageParseException(final String message) {
			super(message);
		}

		public StorageParseException(final String message, final IOException e) {
			super(message, e);
		}

	}
}
