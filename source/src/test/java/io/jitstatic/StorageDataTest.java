package io.jitstatic;

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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

import io.jitstatic.StorageData;
import io.jitstatic.auth.User;

public class StorageDataTest {

	@Test
	public void testStorageData() {
		Set<User> users = new HashSet<>();
		users.add(new User("name", "pass"));
		StorageData sd1 = new StorageData(users, null);
		StorageData sd2 = new StorageData(users, null);
		StorageData sd3 = new StorageData(users, "text/plain");

		assertEquals(sd1, sd2);
		assertNotEquals(sd1, sd3);
		assertEquals(sd1.hashCode(), sd2.hashCode());
		assertNotEquals(sd1.hashCode(), sd3.hashCode());
	}
}
