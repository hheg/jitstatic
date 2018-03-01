package jitstatic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

import jitstatic.auth.User;

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
