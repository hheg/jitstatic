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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import jitstatic.SourceJSONParser;

public class SourceJSONParserTest {

	@Rule
	public final ExpectedException ex = ExpectedException.none();

	private final SourceJSONParser p = new SourceJSONParser();

	@Test
	public void testReadValidParser() throws IOException {
		try (InputStream bc = SourceJSONParserTest.class.getResourceAsStream("/test3.json")) {
			p.parse(bc);
		}
	}

	@Test
	public void testReadFaultyJSON() throws IOException {
		ex.expect(IOException.class);
		ex.expectMessage("File is not valid JSON at line: 48, column: 950");
		try (InputStream bc = SourceJSONParserTest.class.getResourceAsStream("/test4.json")) {
			p.parse(bc);
		}
	}

	@Test
	public void testReadJSONWithMissingUserField() throws IOException {
		ex.expect(IOException.class);
		ex.expectMessage("File does not have valid store file format at line:");
		try (InputStream bc = SourceJSONParserTest.class.getResourceAsStream("/test5.json")) {
			p.parse(bc);
		}
	}

	@Test
	public void testReadJSONWithMissingDataField() throws IOException {
		ex.expect(IOException.class);
		ex.expectMessage("File does not have valid store file format at line:");
		try (InputStream bc = SourceJSONParserTest.class.getResourceAsStream("/test6.json")) {
			p.parse(bc);
		}
	}

	@Test
	public void testReadObjectWithNoUsers() throws UnsupportedEncodingException, IOException {
		ex.expect(IOException.class);
		ex.expectMessage("File does not have valid store file format at line: 1, column: 18");
		try (InputStream bc = new ByteArrayInputStream(
				"{\"data\":\"value1\"}".getBytes(StandardCharsets.UTF_8.name()))) {
			p.parse(bc);
		}
	}

	@Test
	public void testReadObjectWithNoData() throws UnsupportedEncodingException, IOException {
		ex.expect(IOException.class);
		ex.expectMessage("File does not have valid store file format at line: 1, column: 46");
		try (InputStream bc = new ByteArrayInputStream(
				"{\"users\":[{\"user\":\"user\",\"password\":\"1234\"}]}"
						.getBytes(StandardCharsets.UTF_8.name()))) {
			p.parse(bc);
		}
	}

	@Test
	public void testReadObjectWithUserWithNoPassword() throws UnsupportedEncodingException, IOException {
		ex.expect(IOException.class);
		ex.expectMessage("File does not have valid store file format at line: 1, column: 26");
		try (InputStream bc = new ByteArrayInputStream(
				"{\"users\":[{\"user\":\"user\"}]}".getBytes(StandardCharsets.UTF_8.name()))) {
			p.parse(bc);
		}
	}

	@Test
	public void testReadObjectWithUserWithNoUser() throws UnsupportedEncodingException, IOException {
		ex.expect(IOException.class);
		ex.expectMessage("File does not have valid store file format at line: 1, column: 30");
		try (InputStream bc = new ByteArrayInputStream(
				"{\"users\":[{\"password\":\"1234\"}]}".getBytes(StandardCharsets.UTF_8.name()))) {
			p.parse(bc);
		}
	}

	@Test
	public void testReadObjectFieldNoStart() throws UnsupportedEncodingException, IOException {
		ex.expect(IOException.class);
		ex.expectMessage("File does not have valid store file format at line: 1, column: 3");
		try (InputStream bc = new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8.name()))) {
			p.parse(bc);
		}
	}

}
