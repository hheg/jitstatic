package jitstatic.utils;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class WrappingAPIExceptionTest {

	@Test
	public void testWrappingAPIException() {
		VersionIsNotSameException v = new VersionIsNotSameException();
		WrappingAPIException w = new WrappingAPIException(v);
		assertEquals(v, w.getCause());
	}
}
