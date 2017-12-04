package jitstatic.hosted;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.eclipse.jgit.lib.ObjectId;
import org.junit.Test;

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
