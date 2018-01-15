package jitstatic.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PairTest {
	
	@Test
	public void testPair() {
		Object l = new Object();
		Object r = new Object();
		Pair<Object, Object> p = Pair.of(l, r);
		assertEquals(l,p.getLeft());
		assertEquals(r,p.getRight());
		Pair<Object,Object> ofNothing = Pair.ofNothing();
		assertNull(ofNothing.getLeft());
		assertNull(ofNothing.getRight());
		assertFalse(ofNothing.isPresent());
		assertTrue(p.isPresent());
		assertFalse(Pair.of(l, null).isPresent());
		assertFalse(Pair.of(null,r).isPresent());
	}
}
