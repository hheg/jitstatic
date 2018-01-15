package jitstatic.utils;

/*-
 * #%L
 * jitstatic
 * %%
 * Copyright (C) 2017 - 2018 H.Hegardt
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
