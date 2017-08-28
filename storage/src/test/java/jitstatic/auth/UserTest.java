package jitstatic.auth;

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

import org.junit.Test;

public class UserTest {

	@Test
	public void testSameUser() {
		User u1 = new User(new String("user"), new String("1"));
		User u2 = new User(new String("user"),new String("1"));
		assertEquals(u1,u2);
		assertEquals(u1.hashCode(),u2.hashCode());
	}
	
	@Test
	public void testSameUserObject() {
		User u1 = new User(new String("user"),new String("1"));		
		assertEquals(u1,u1);		
	}
	
	@Test
	public void testNotSameUser() {
		User u1 = new User(new String("auser"),new String("1"));
		User u2 = new User(new String("buser"),new String("1"));
		assertNotEquals(u1,u2);
	}

	
	@Test
	public void testAgainstNull() {
		User u1 = new User(new String("auser"),null);		
		assertNotEquals(u1,null);
	}
	
	@Test
	public void testAgainstUserNull() {
		User u1 = new User(null,null);
		User u2 = new User(null,null);
		assertTrue(u1.equals(u2));
	}
	
	@Test
	public void testNullAgainstUser() {
		User u1 = new User(null,null);
		User u2 = new User("other",null);
		assertFalse(u1.equals(u2));
	}
	
	@Test
	public void testAgainstAnotherObject() {
		User u1 = new User(new String("auser"),null);		
		assertNotEquals(u1,new Object());
	}
}
