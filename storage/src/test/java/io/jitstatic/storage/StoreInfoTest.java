package io.jitstatic.storage;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;

import io.jitstatic.StorageData;
import io.jitstatic.hosted.StoreInfo;

public class StoreInfoTest {

	@Test
	public void testStorageInfo() throws JsonParseException, JsonMappingException, IOException {
		StoreInfo si1 = new StoreInfo("{\"one\":\"two\"}".getBytes("UTF-8"), new StorageData(new HashSet<>(), null, false, false, List.of()), "1", "1");
		StoreInfo si2 = new StoreInfo("{\"one\":\"two\"}".getBytes("UTF-8"), new StorageData(new HashSet<>(), null, false, false, List.of()), "1", "1");
		StoreInfo si3 = new StoreInfo("{\"one\":\"two\"}".getBytes("UTF-8"), new StorageData(new HashSet<>(), null, false, false, List.of()), "2", "2");

		assertEquals(si1, si1);
		assertEquals(si1.hashCode(), si2.hashCode());
		assertEquals(si1, si2);
		assertNotEquals(si1, si3);

		assertNotEquals(si1, null);
		assertNotEquals(si1, new Object());
	}
}
