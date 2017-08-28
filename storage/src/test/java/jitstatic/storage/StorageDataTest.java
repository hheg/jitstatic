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

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jitstatic.auth.User;

public class StorageDataTest {

	private static final ObjectMapper mapper = new ObjectMapper();
		
	
	@Test
	public void testEquals() throws JsonProcessingException, IOException {
		Set<User> users1 = new HashSet<>();
		users1.add(new User("user1","p"));
		
		Set<User> users2 = new HashSet<>();
		users2.add(new User("user1","p"));				
		StorageData sd1 = new StorageData(users1, readJson("{\"data\": \"value1\"}"));
		StorageData sd2 = new StorageData(users2, readJson("{\"data\": \"value1\"}"));
		assertEquals(sd1,sd2);
	}
	
	@Test
	public void testNotEquals() throws JsonProcessingException, IOException {
		Set<User> users1 = new HashSet<>();
		users1.add(new User("user1","p"));
		Set<User> users2 = new HashSet<>();
		users2.add(new User("user1","p"));				
		StorageData sd1 = new StorageData(users1, readJson("{\"data\": \"value1\"}"));
		StorageData sd2 = new StorageData(users2, readJson("{\"data\": \"value2\"}"));
		assertNotEquals(sd1,sd2);
	}
	
	@Test
	public void testEqualsHashCode() throws JsonProcessingException, IOException {
		Set<User> users1 = new HashSet<>();
		users1.add(new User("user1","p"));		
		Set<User> users2 = new HashSet<>();
		users2.add(new User("user1","p"));				
		StorageData sd1 = new StorageData(users1, readJson("{\"data\": \"value1\"}"));
		StorageData sd2 = new StorageData(users2, readJson("{\"data\": \"value1\"}"));
		assertEquals(sd1.hashCode(),sd2.hashCode());
	}
	
	@Test
	public void testNotEqualsHashCode() throws JsonProcessingException, IOException {
		Set<User> users1 = new HashSet<>();
		users1.add(new User("user1","p"));
		Set<User> users2 = new HashSet<>();
		users2.add(new User("user1","p"));				
		StorageData sd1 = new StorageData(users1, readJson("{\"data\": \"value1\"}"));
		StorageData sd2 = new StorageData(users2, readJson("{\"data\": \"value2\"}"));
		assertNotEquals(sd1.hashCode(),sd2.hashCode());
	}


	private JsonNode readJson(String content) throws JsonProcessingException, IOException {
		return mapper.readTree(content);
	}
}
