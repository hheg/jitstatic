package jitstatic;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class LinkedExceptionTest {

	@Test
	public void testLinkedException() {
		LinkedException le = new LinkedException();
		le.add(new Exception("Message"));
		le.add(null);
		String message = le.getMessage();
		assertEquals("class java.lang.Exception: Message",message);
	}
}
