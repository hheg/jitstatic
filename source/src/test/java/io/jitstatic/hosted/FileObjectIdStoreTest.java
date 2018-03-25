package io.jitstatic.hosted;

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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.eclipse.jgit.lib.ObjectId;
import org.junit.Test;

import io.jitstatic.FileObjectIdStore;

public class FileObjectIdStoreTest {

	@Test
	public void testEquals() {
		FileObjectIdStore first = new FileObjectIdStore("file",
				ObjectId.fromString("800c62339fc21a6f7b149124ce98a17236e2dfe4"));
		FileObjectIdStore second = new FileObjectIdStore("file",
				ObjectId.fromString("800c62339fc21a6f7b149124ce98a17236e2dfe4"));
		assertEquals(first, second);
		assertEquals(first.hashCode(), second.hashCode());
	}

	@Test
	public void testNotEqualsFile() {
		FileObjectIdStore first = new FileObjectIdStore("file",
				ObjectId.fromString("800c62339fc21a6f7b149124ce98a17236e2dfe4"));
		FileObjectIdStore second = new FileObjectIdStore("file2",
				ObjectId.fromString("800c62339fc21a6f7b149124ce98a17236e2dfe4"));
		assertNotEquals(first, second);
	}

	@Test
	public void testSame() {
		FileObjectIdStore first = new FileObjectIdStore("file",
				ObjectId.fromString("800c62339fc21a6f7b149124ce98a17236e2dfe4"));
		assertEquals(first, first);
	}
}
