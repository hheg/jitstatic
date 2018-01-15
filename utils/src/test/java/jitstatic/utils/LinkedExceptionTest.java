package jitstatic.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

public class LinkedExceptionTest {

	@Test
	public void testLinkedException() {
		LinkedException le = new LinkedException(Arrays.asList(new RuntimeException("re"), new Exception("e1")));
		le.add(new Exception("e2"));
		le.addAll(Arrays.asList(new Exception("e3")));
		assertEquals("class java.lang.RuntimeException: re\n" + "class java.lang.Exception: e1\n" + "class java.lang.Exception: e2\n"
				+ "class java.lang.Exception: e3", le.getMessage());
		assertFalse(le.isEmpty());
	}
	
	@Test
	public void testNotAddNullExceptions() {
		LinkedException le = new LinkedException();
		le.add(null);
		assertTrue(le.isEmpty());
	}
}
