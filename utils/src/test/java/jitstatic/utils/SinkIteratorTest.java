package jitstatic.utils;

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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.List;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import jitstatic.utils.SinkIterator;

public class SinkIteratorTest {

	private List<String> victim;

	@Rule
	public ExpectedException ex = ExpectedException.none();

	@Before
	public void setup() {
		victim = new ArrayList<>(Arrays.asList("1", "2", "3"));
	}

	@Test
	public void testIterate() {
		int size = victim.size();
		SinkIterator<String> iter = new SinkIterator<>(victim);
		int i = 0;
		while (iter.hasNext()) {
			assertEquals(iter.next(), victim.get(0));
			iter.remove();
			i++;
		}
		assertEquals(size, i);
		assertTrue(victim.isEmpty());
	}

	@Test
	public void testReIterate() {
		int size = victim.size();
		SinkIterator<String> iter = new SinkIterator<>(victim);
		assertTrue(iter.hasNext());
		assertEquals(victim.get(0), iter.next());
		assertTrue(iter.hasNext());
		assertEquals(victim.get(1), iter.next());
		assertTrue(iter.hasNext());
		assertEquals(victim.get(2), iter.next());
		int i = 0;
		while (iter.hasNext()) {
			assertEquals(iter.next(), victim.get(0));
			iter.remove();
			i++;
		}
		assertEquals(size, i);
		assertTrue(victim.isEmpty());
	}

	@Test
	public void testConcurrentModificationException() {
		ex.expect(ConcurrentModificationException.class);
		SinkIterator<String> iter = new SinkIterator<>(victim);
		iter.next();
		victim.clear();
		iter.remove();
	}
	
	@Test
	public void testRemoveOnIteratorButNotAdvanced() {
		ex.expect(IllegalStateException.class);
		SinkIterator<String> iter = new SinkIterator<>(victim);
		iter.remove();
	}
}
