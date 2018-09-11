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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.jitstatic.utils.Pair;

public class TreeTest {
    // @formatter:off
    private List<Pair<String, Boolean>> data = List.of(
            Pair.of("/", false), 
            Pair.of("dir0/dir1/dir3/file1", false),
            Pair.of("dir0/dir1/dir3/file8", false), 
            Pair.of("dir0/dir1/dir3/file9", false), 
            Pair.of("dir0/dir1/file2", false),
            Pair.of("dir0/dir1/", false), 
            Pair.of("dir0/dir2/file3", false), 
            Pair.of("dir0/dir1/dir3/dir4/dir5/file6", false),
            Pair.of("dir0/dir1/file4", true), 
            Pair.of("file5", false), 
            Pair.of("dir6/file7", false));
    // @formatter:on

    @Test
    public void testDeboneFolderWithFile() {
        Tree tree = Tree.of(List.of(Pair.of("dir6/file7", false)));
        assertTrue(tree.getBranches().size() == 1);
        Tree branch = tree.getBranches().iterator().next();
        assertTrue(branch.getName().equals("dir6"));
        assertTrue(branch.getBranches().isEmpty());
        assertTrue(branch.getLeafs().size() == 1);
        Tree leaf = branch.getLeafs().iterator().next();
        assertTrue(leaf.getName().equals("file7"));
    }

    @Test
    public void testDeboneFolderWithLevel() {
        Tree tree = Tree.of(List.of(Pair.of("dir6/file7", false), Pair.of("dir6/", false), Pair.of("dir6/dir7/file10", false)));
        ByteArrayOutputStream byteArrayOutputStream1 = new ByteArrayOutputStream();
        tree.accept(new PrintingVisitor(byteArrayOutputStream1));
        Tree expected = Tree.of(List.of(Pair.of("dir6/dir7/file10", false), Pair.of("dir6/", false)));
        assertEquals(expected, tree);
        ByteArrayOutputStream byteArrayOutputStream2 = new ByteArrayOutputStream();
        tree.accept(new PrintingVisitor(byteArrayOutputStream2));
        assertEquals(new String(byteArrayOutputStream1.toByteArray()), new String(byteArrayOutputStream2.toByteArray()));
    }

    @Test
    public void testDeboneFolderWithAll() {
        Tree tree = Tree.of(List.of(Pair.of("dir6/file7", false), Pair.of("dir6/", true), Pair.of("dir6/dir7/file10", false)));
        tree.accept(new PrintingVisitor(System.out));
        assertTrue(tree.getBranches().size() == 1);
        Tree branch = tree.getBranches().iterator().next();
        assertTrue(branch.getName().equals("dir6"));
        assertTrue(branch.getBranches().isEmpty());
        assertTrue(branch.getLeafs().isEmpty());
    }

    @Test
    public void testDeboneFolderWithLevelAndFiles() {
        // @formatter:off
        Tree tree = Tree.of(List.of(
                Pair.of("dir0/dir1/dir3/file1", false), 
                Pair.of("dir0/dir1/dir3/file8", false),
                Pair.of("dir0/dir1/dir3/file9", false), 
                Pair.of("dir0/dir1/file2", false), 
                Pair.of("dir0/dir1/", false)));
        tree.accept(new PrintingVisitor(System.out));
        Tree expected = Tree.of(List.of(
                Pair.of("dir0/dir1/dir3/file1", false), 
                Pair.of("dir0/dir1/dir3/file8", false),
                Pair.of("dir0/dir1/dir3/file9", false), 
                Pair.of("dir0/dir1/", false)));
        // @formatter:on
        assertEquals(expected, tree);
    }

    @Test
    public void testPrintStream() {
        Tree tree = Tree.of(data);
        tree.accept(new PrintingVisitor(System.out));
        System.out.println(tree);
    }

    @Test
    public void testEquals() {
        Tree tree1 = Tree.of(data);
        Tree tree2 = Tree.of(data);
        assertEquals(tree1, tree2);
        assertEquals(tree1.hashCode(), tree2.hashCode());
    }

    @Test
    public void testNotEquals() {
        Tree tree1 = Tree.of(data);
        Tree tree2 = Tree.of(List.of(Pair.of("dir0/dir1/", false)));
        assertNotEquals(tree2, tree1);
    }

    @Test
    public void testIdempotency() {
        Tree tree = Tree.of(data);
        List<Pair<String, Boolean>> shuffled = new ArrayList<>(data);
        Collections.shuffle(shuffled);
        Tree shuffledTree = Tree.of(shuffled);
        assertEquals(tree, shuffledTree);
    }

    @Test
    public void testAll() {
        Tree tree = Tree.of(List.of(Pair.of("/", true), Pair.of("file1", false), Pair.of("dir1/file2", false)));
        assertTrue(tree.getBranches().isEmpty());
        assertTrue(tree.getLeafs().isEmpty());
        assertEquals("/", tree.getName());
    }

    @Test
    public void testTreeAllAndLevel() {
        Tree all = Tree.of(List.of(Pair.of("/", true)));
        Tree level = Tree.of(List.of(Pair.of("/", false)));
        assertFalse(all.equals(level));
    }

}
