package io.jitstatic.utils;

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
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

import io.jitstatic.utils.LinkedException;

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
