package jitstatic.auth;

import static org.junit.Assert.*;

import org.junit.Test;

public class UserTest {

	@Test
	public void testSameUser() {
		User u1 = new User(new String("user"));
		User u2 = new User(new String("user"));
		assertEquals(u1,u2);
		assertEquals(u1.hashCode(),u2.hashCode());
	}
	
	@Test
	public void testSameUserObject() {
		User u1 = new User(new String("user"));		
		assertEquals(u1,u1);		
	}
	
	@Test
	public void testNotSameUser() {
		User u1 = new User(new String("auser"));
		User u2 = new User(new String("buser"));
		assertNotEquals(u1,u2);
	}

	
	@Test
	public void testAgainstNull() {
		User u1 = new User(new String("auser"));		
		assertNotEquals(u1,null);
	}
	
	@Test
	public void testAgainstUserNull() {
		User u1 = new User(null);
		User u2 = new User(null);
		assertTrue(u1.equals(u2));
	}
	
	@Test
	public void testNullAgainstUser() {
		User u1 = new User(null);
		User u2 = new User("other");
		assertFalse(u1.equals(u2));
	}
	
	@Test
	public void testAgainstAnotherObject() {
		User u1 = new User(new String("auser"));		
		assertNotEquals(u1,new Object());
	}
}
