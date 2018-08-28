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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.jitstatic.utils.Pair;

public class PairTest {

    @Test
    public void testPair() {
        Object l = new Object();
        Object r = new Object();
        Pair<Object, Object> p = Pair.of(l, r);
        assertEquals(l, p.getLeft());
        assertEquals(r, p.getRight());
        Pair<Object, Object> ofNothing = Pair.ofNothing();
        assertNull(ofNothing.getLeft());
        assertNull(ofNothing.getRight());
        assertFalse(ofNothing.isPresent());
        assertTrue(p.isPresent());
        assertFalse(Pair.of(l, null).isPresent());
        assertFalse(Pair.of(null, r).isPresent());
        assertNotNull(Pair.of("1", "2").toString());
        assertEquals("1", Pair.of("1", "2").getKey());
        assertEquals("2", Pair.of("1", "2").getValue());
        assertEquals(Pair.of("1", "2").hashCode(), Pair.of("1", "2").hashCode());
        assertEquals(Pair.of("1", null).hashCode(), Pair.of("1", null).hashCode());
        assertEquals(Pair.of(null, "2").hashCode(), Pair.of(null, "2").hashCode());
    }

    @Test
    public void testEquals() {
        assertEquals(Pair.of(), Pair.of());
        assertEquals(Pair.of("1", null), Pair.of("1", null));
        assertEquals(Pair.of(null, "1"), Pair.of(null, "1"));
        assertEquals(Pair.of(null, null), Pair.of(null, null));
        assertEquals(Pair.of("1", "2"), Pair.of("1", "2"));
    }

    @Test
    public void testNotEquals() {
        assertNotEquals(Pair.of("1", null), Pair.of());
        assertNotEquals(Pair.of("1", null), Pair.of(null, "1"));
        assertNotEquals(Pair.of("1", "2"), Pair.of("2", "1"));
        assertNotEquals(Pair.of("1", null), null);
        assertNotEquals(null, Pair.of());
    }
}
