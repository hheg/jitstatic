package jitstatic.storage;

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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

import com.fasterxml.jackson.core.JsonProcessingException;

import jitstatic.auth.User;

public class StorageDataTest {

	@Test
	public void testEquals() throws JsonProcessingException, IOException {
		Set<User> users1 = new HashSet<>();
		users1.add(new User("user1", "p"));

		Set<User> users2 = new HashSet<>();
		users2.add(new User("user1", "p"));
		StorageData sd1 = new StorageData(users1);
		StorageData sd2 = new StorageData(users2);
		assertEquals(sd1, sd2);
	}

	@Test
	public void testNotEquals() throws JsonProcessingException, IOException {
		Set<User> users1 = new HashSet<>();
		users1.add(new User("user1", "p"));
		Set<User> users2 = new HashSet<>();
		users2.add(new User("user2", "p"));
		StorageData sd1 = new StorageData(users1);
		StorageData sd2 = new StorageData(users2);
		assertNotEquals(sd1, sd2);
	}

	@Test
	public void testEqualsHashCode() throws JsonProcessingException, IOException {
		Set<User> users1 = new HashSet<>();
		users1.add(new User("user1", "p"));
		Set<User> users2 = new HashSet<>();
		users2.add(new User("user1", "p"));
		StorageData sd1 = new StorageData(users1);
		StorageData sd2 = new StorageData(users2);
		assertEquals(sd1.hashCode(), sd2.hashCode());
	}

	@Test
	public void testNotEqualsHashCode() throws JsonProcessingException, IOException {
		Set<User> users1 = new HashSet<>();
		users1.add(new User("user1", "p"));
		Set<User> users2 = new HashSet<>();
		users2.add(new User("user2", "p"));
		StorageData sd1 = new StorageData(users1);
		StorageData sd2 = new StorageData(users2);
		assertNotEquals(sd1.hashCode(), sd2.hashCode());
	}

	@Test
	public void testEqualsInstance() throws JsonProcessingException, IOException {
		Set<User> users1 = new HashSet<>();
		users1.add(new User("user1", "p"));
		StorageData sd1 = new StorageData(users1);
		assertTrue(sd1.equals(sd1));
	}
	
	@Test
	public void testNotEqualsToNull() throws JsonProcessingException, IOException {
		Set<User> users1 = new HashSet<>();
		users1.add(new User("user1", "p"));
		StorageData sd1 = new StorageData(users1);
		assertFalse(sd1.equals(null));
	}

	@Test
	public void testNotEqualsToOther() throws JsonProcessingException, IOException {
		Set<User> users1 = new HashSet<>();
		users1.add(new User("user1", "p"));
		StorageData sd1 = new StorageData(users1);
		assertFalse(sd1.equals(new Object()));
	}
	
	@Test
	public void testNotEqualsUsers() throws JsonProcessingException, IOException {
		Set<User> users1 = new HashSet<>();
		users1.add(new User("user1", "p"));
		StorageData sd1 = new StorageData(users1);
		Set<User> users2 = new HashSet<>();
		users1.add(new User("user2", "p"));
		StorageData sd2 = new StorageData(users2);
		assertFalse(sd1.equals(sd2));
	}
}
