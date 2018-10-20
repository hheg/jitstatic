package io.jitstatic.storage;

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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.jitstatic.utils.Pair;

public class DotFinderVisitorTest {

    @Test
    public void testNoDotFile() {
        Tree t = Tree.of(List.of(Pair.of("dot", false)));
        assertFalse(t.accept(new DotFinderVisitor()));
    }

    @Test
    public void testFirstDotFile() {
        Tree t = Tree.of(List.of(Pair.of(".dot", false)));
        assertTrue(t.accept(new DotFinderVisitor()));
    }

    @Test
    public void testDotFolder() {
        Tree t = Tree.of(List.of(Pair.of("dot/.dot/", false)));
        assertTrue(t.accept(new DotFinderVisitor()));
    }

    @Test
    public void testDotFile() {
        Tree t = Tree.of(List.of(Pair.of("dot/.dot", false)));
        assertTrue(t.accept(new DotFinderVisitor()));
    }

    @Test
    public void testDotFolderFile() {
        Tree t = Tree.of(List.of(Pair.of("dot/dot/.dot", false)));
        assertTrue(t.accept(new DotFinderVisitor()));
    }
}
