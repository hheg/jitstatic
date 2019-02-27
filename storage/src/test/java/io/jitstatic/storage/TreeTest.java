package io.jitstatic.storage;

/*-
 * #%L
 * jitstatic
 * %%
 * Copyright (C) 2017 - 2019 H.Hegardt
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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.jitstatic.storage.Tree.Node;
import io.jitstatic.utils.Pair;

public class TreeTest {
    private List<Pair<String, Boolean>> data = List.of(
            Pair.of("/", false),
            Pair.of("dir0/dir1/dir3/file1", false),
            Pair.of("dir0/dir1/dir3/file8", false),
            Pair.of("dir0/dir1/dir3/file9", false),
            Pair.of("dir0/dir1/file2", false),
            Pair.of("dir0/dir1/", false),
            Pair.of("dir0/dir2/file3", false),
            Pair.of("dir0/dir2/file3", false),
            Pair.of("dir0/dir1/dir3/dir4/dir5/file6", false),
            Pair.of("dir0/dir1/file4", false),
            Pair.of("dir0/dir2/", true),
            Pair.of("file5", false),
            Pair.of("dir0/dir2/file4", false),
            Pair.of("dir0/dir2/file5", false),
            Pair.of("dir6/file7", false));
    private List<Pair<String, Boolean>> expected = List.of(
            Pair.of("/", false),
            Pair.of("dir0/dir1/", false),
            Pair.of("dir0/dir1/dir3/dir4/dir5/file6", false),
            Pair.of("dir0/dir1/dir3/file1", false),
            Pair.of("dir0/dir1/dir3/file8", false),
            Pair.of("dir0/dir1/dir3/file9", false),
            Pair.of("dir0/dir2/", true),
            Pair.of("dir6/file7", false));

    @Test
    public void testGeneral() {
        assertEquals(expected, Tree.of(data).accept(new Tree.Extractor()));
    }

    @Test
    public void testOrderingAndStarNode() {
        List<Pair<String, Boolean>> data = List.of(
                Pair.of("dir0/dir2/file3", false),
                Pair.of("dir0/dir2/file3", false),
                Pair.of("dir0/dir2/", true),
                Pair.of("dir0/dir2/file4", false),
                Pair.of("dir0/dir1/dir3/dir4/dir5/file6", false));
        List<Pair<String, Boolean>> actual = Tree.of(data).accept(new Tree.Extractor());
        List<Pair<String, Boolean>> expected = List.of(
                Pair.of("dir0/dir1/dir3/dir4/dir5/file6", false),
                Pair.of("dir0/dir2/", true));
        assertEquals(expected, actual);
    }

    @Test
    public void testAllFilesInSubfolder() {
        List<Pair<String, Boolean>> data = List.of(
                Pair.of("dir0/dir2/file3", false),
                Pair.of("dir0/dir2/file3", false),
                Pair.of("dir0/dir2/", false),
                Pair.of("dir0/dir2/file4", false),
                Pair.of("dir0/dir2/dir3/dir4/dir5/file6", false));
        List<Pair<String, Boolean>> actual = Tree.of(data).accept(new Tree.Extractor());
        List<Pair<String, Boolean>> expected = List.of(
                Pair.of("dir0/dir2/", false),
                Pair.of("dir0/dir2/dir3/dir4/dir5/file6", false));
        assertEquals(expected, actual);
    }

    @Test
    public void testAllFilesInSubfolder2() {
        List<Pair<String, Boolean>> data = List.of(
                Pair.of("dir0/dir2/file3", false),
                Pair.of("dir0/dir2/file3", false),
                Pair.of("dir0/dir2/dir3/dir4/dir5/file6", false),
                Pair.of("dir0/dir2/", false),
                Pair.of("dir0/dir2/file4", false));
        List<Pair<String, Boolean>> actual = Tree.of(data).accept(new Tree.Extractor());
        List<Pair<String, Boolean>> expected = List.of(
                Pair.of("dir0/dir2/", false),
                Pair.of("dir0/dir2/dir3/dir4/dir5/file6", false));
        assertEquals(expected, actual);
    }

    @Test
    public void testLevelRoot() {
        List<Pair<String, Boolean>> data = List.of(
                Pair.of("/", false),
                Pair.of("file1", false),
                Pair.of("dir0/file1", false));
        List<Pair<String, Boolean>> actual = Tree.of(data).accept(new Tree.Extractor());
        List<Pair<String, Boolean>> expected = List.of(
                Pair.of("/", false),
                Pair.of("dir0/file1", false));
        assertEquals(expected, actual);
    }

    @Test
    public void testLevelRootReverse() {
        List<Pair<String, Boolean>> data = List.of(
                Pair.of("file1", false),
                Pair.of("dir0/file1", false),
                Pair.of("/", false));
        List<Pair<String, Boolean>> actual = Tree.of(data).accept(new Tree.Extractor());
        List<Pair<String, Boolean>> expected = List.of(
                Pair.of("/", false),
                Pair.of("dir0/file1", false));
        assertEquals(expected, actual);
    }

    @Test
    public void testLevelInsertion() {
        List<Pair<String, Boolean>> data = List.of(
                Pair.of("dir0/dir1/dir3/file1", false),
                Pair.of("dir0/dir1/dir3/file8", false),
                Pair.of("dir0/dir1/dir3/file9", false),
                Pair.of("dir0/dir1/file2", false),
                Pair.of("dir0/dir1/", false),
                Pair.of("dir0/dir1/file3", false),
                Pair.of("dir0/dir1/file3", false),
                Pair.of("dir0/dir1/dir3/dir4/dir5/file6", false),
                Pair.of("dir0/dir1/file4", false),
                Pair.of("dir0/dir1/", false),
                Pair.of("file5", false),
                Pair.of("dir0/dir1/file4", false),
                Pair.of("dir0/dir1/file5", false));
        List<Pair<String, Boolean>> actual = Tree.of(data).accept(new Tree.Extractor());
        List<Pair<String, Boolean>> expected = List.of(
                Pair.of("dir0/dir1/", false),
                Pair.of("dir0/dir1/dir3/dir4/dir5/file6", false),
                Pair.of("dir0/dir1/dir3/file1", false),
                Pair.of("dir0/dir1/dir3/file8", false),
                Pair.of("dir0/dir1/dir3/file9", false),
                Pair.of("file5", false));
        assertEquals(expected, actual);
    }

    @Test
    public void testStarRoot() {
        List<Pair<String, Boolean>> data = List.of(
                Pair.of("/", true),
                Pair.of("dir0/file1", false));
        List<Pair<String, Boolean>> actual = Tree.of(data).accept(new Tree.Extractor());
        List<Pair<String, Boolean>> expected = List.of(
                Pair.of("/", true));
        assertEquals(expected, actual);
    }

    @Test
    public void testStarRootReverse() {
        List<Pair<String, Boolean>> data = List.of(
                Pair.of("dir0/file1", false),
                Pair.of("/", true));
        List<Pair<String, Boolean>> actual = Tree.of(data).accept(new Tree.Extractor());
        List<Pair<String, Boolean>> expected = List.of(
                Pair.of("/", true));
        assertEquals(expected, actual);
    }

    @Test
    public void testNameClash() {
        List<Pair<String, Boolean>> data = List.of(
                Pair.of("dir0/dir1/", false),
                Pair.of("dir0/dir1", false));
        List<Pair<String, Boolean>> actual = Tree.of(data).accept(new Tree.Extractor());
        List<Pair<String, Boolean>> expected = List.of(
                Pair.of("dir0/dir1/", false));
        assertEquals(expected, actual);
    }

    @Test
    public void testNameClashReverse() {
        List<Pair<String, Boolean>> data = List.of(
                Pair.of("dir0/dir1", false),
                Pair.of("dir0/dir1/", false));
        List<Pair<String, Boolean>> actual = Tree.of(data).accept(new Tree.Extractor());
        List<Pair<String, Boolean>> expected = List.of(
                Pair.of("dir0/dir1/", false));
        assertEquals(expected, actual);
    }

    @Test
    public void testPrePendedSlash() {
        List<Pair<String, Boolean>> data = List.of(
                Pair.of("/dir0/dir1", false),
                Pair.of("dir0/dir1/", false));
        List<Pair<String, Boolean>> actual = Tree.of(data).accept(new Tree.Extractor());
        List<Pair<String, Boolean>> expected = List.of(
                Pair.of("dir0/dir1/", false));
        assertEquals(expected, actual);
    }

    @Test
    public void testPrePendedSlashAndMiddle() {
        List<Pair<String, Boolean>> data = List.of(
                Pair.of("///dir0/dir1", false),
                Pair.of("dir0//dir1/", false));
        List<Pair<String, Boolean>> actual = Tree.of(data).accept(new Tree.Extractor());
        List<Pair<String, Boolean>> expected = List.of(
                Pair.of("dir0/dir1/", false));
        assertEquals(expected, actual);
    }

    @Test
    public void testNodeEquals() {
        Node<String, Void> r = new Tree.AnchorNode<>("");
        Node<String, Void> n1 = new Tree.BranchNode<>("1", r.findNodeWith("/").get());
        Node<String, Void> n11 = new Tree.BranchNode<>("1", r.findNodeWith("/").get());
        assertEquals(n1, n11);
    }

    @Test
    public void testNodeSame() {
        Node<String, Void> r = new Tree.AnchorNode<>("");
        Node<String, Void> n1 = new Tree.BranchNode<>("1", r.findNodeWith("/").get());
        assertSame(n1, n1);
    }

    @Test
    public void testNodeNotEquals() {
        Node<String, Void> r = new Tree.AnchorNode<>("");
        Node<String, Void> n1 = new Tree.BranchNode<>("1", r.findNodeWith("/").get());
        Node<String, Void> n2 = new Tree.BranchNode<>("2", r.findNodeWith("/").get());
        assertNotEquals(n1, n2);
    }

    @Test
    public void testLeafNodeEquals() {
        Node<String, Void> r = new Tree.AnchorNode<>("");
        Node<String, Void> p1 = new Tree.BranchNode<>("1", r.findNodeWith("/").get());
        Node<String, Void> p2 = new Tree.BranchNode<>("2", r.findNodeWith("/").get());
        Node<String, Void> n1 = new Tree.LeafNode<>("1", p1);
        Node<String, Void> n2 = new Tree.LeafNode<>("2", p2);
        assertNotEquals(n1, n2);
    }

    @Test
    public void testDeboneFolderWithLevelAndFiles() {
        Tree data = Tree.of(List.of(
                Pair.of("dir0/dir1/dir3/file1", false),
                Pair.of("dir0/dir1/dir3/file8", false),
                Pair.of("dir0/dir1/dir3/file9", false),
                Pair.of("dir0/dir1/file2", false),
                Pair.of("dir0/dir1/", false)));
        List<Pair<String, Boolean>> actual = data.accept(new Tree.Extractor());
        List<Pair<String, Boolean>> expected = List.of(
                Pair.of("dir0/dir1/", false),
                Pair.of("dir0/dir1/dir3/file1", false),
                Pair.of("dir0/dir1/dir3/file8", false),
                Pair.of("dir0/dir1/dir3/file9", false));
        assertEquals(expected, actual);
    }

    @Test
    public void testNotEqualsTrees() {
        Tree data = Tree.of(List.of(
                Pair.of("dir0/dir1/dir3/file1", false),
                Pair.of("dir0/dir1/dir3/file8", false),
                Pair.of("dir0/dir1/dir3/file9", false),
                Pair.of("dir0/dir1/file2", false),
                Pair.of("dir0/dir1/", false)));
        List<Pair<String, Boolean>> actual = data.accept(new Tree.Extractor());
        List<Pair<String, Boolean>> expected = List.of(
                Pair.of("dir0/dir1/dir3/file1", false),
                Pair.of("dir0/dir1/dir3/file8", false),
                Pair.of("dir0/dir1/dir3/file9", false),
                Pair.of("dir0/dir1/dir3/file7", false),
                Pair.of("dir0/dir1/", false));
        assertNotEquals(expected, actual);
    }

    @Test
    public void testHasRootDot() {
        Tree tree = Tree.of(List.of(Pair.of(".users/git/blah", false)));
        assertTrue(tree.accept(Tree.DOT_FINDER));
    }

    @Test
    public void testDirHasDot() {
        Tree tree = Tree.of(List.of(Pair.of("users/.git/blah", false)));
        assertTrue(tree.accept(Tree.DOT_FINDER));
    }

    @Test
    public void testFileHasDot() {
        Tree tree = Tree.of(List.of(Pair.of("users/git/.blah", false)));
        assertTrue(tree.accept(Tree.DOT_FINDER));
    }

    @Test
    public void testHasNoDot() {
        Tree tree = Tree.of(List.of(Pair.of("users/git/blah", false)));
        assertFalse(tree.accept(Tree.DOT_FINDER));
    }

    @Test
    public void testLastDirHasDot() {
        Tree tree = Tree.of(List.of(Pair.of("users/git/.blah/", false)));
        assertTrue(tree.accept(Tree.DOT_FINDER));
    }

    @Test
    public void testStarNodeLastDirHasDot() {
        Tree tree = Tree.of(List.of(Pair.of("users/git/.blah/", true)));
        assertTrue(tree.accept(Tree.DOT_FINDER));
    }

    @Test
    public void testBuildPathWithDirectoryAndFile() {
        List<Pair<String, Boolean>> data = List.of(
                Pair.of("dir0/dir1/dir3/file1", false),
                Pair.of("dir0/dir1/dir3/file8", false),
                Pair.of("dir0/dir1/dir3/file9", false),
                Pair.of("dir0/dir1/file2", false),
                Pair.of("dir0/dir1/", false));
        List<Pair<String, Boolean>> actual = Tree.of(data).accept(new Tree.Extractor());
        List<Pair<String, Boolean>> expected = List.of(
                Pair.of("dir0/dir1/", false),
                Pair.of("dir0/dir1/dir3/file1", false),
                Pair.of("dir0/dir1/dir3/file8", false),
                Pair.of("dir0/dir1/dir3/file9", false));
        assertEquals(expected, actual);
    }

    @Test
    public void testBuildPathWithRootDirectoryAndFile() {
        List<Pair<String, Boolean>> data = List.of(
                Pair.of("/", false),
                Pair.of("dir0/dir1/dir3/file1", false),
                Pair.of("dir0/dir1/dir3/file8", false),
                Pair.of("dir0/dir1/dir3/file9", false),
                Pair.of("dir0/dir1/file2", false),
                Pair.of("dir0/dir1/", false));
        List<Pair<String, Boolean>> actual = Tree.of(data).accept(new Tree.Extractor());
        List<Pair<String, Boolean>> expected = List.of(
                Pair.of("/", false),
                Pair.of("dir0/dir1/", false),
                Pair.of("dir0/dir1/dir3/file1", false),
                Pair.of("dir0/dir1/dir3/file8", false),
                Pair.of("dir0/dir1/dir3/file9", false));
        assertEquals(expected, actual);
    }

    @Test
    public void testBuildPathWithRootDirectoryAll() {
        List<Pair<String, Boolean>> data = List.of(
                Pair.of("/", true),
                Pair.of("dir0/dir1/dir3/file1", false),
                Pair.of("dir0/dir1/dir3/file8", false),
                Pair.of("dir0/dir1/dir3/file9", false),
                Pair.of("dir0/dir1/file2", false),
                Pair.of("dir0/dir1/", false));
        List<Pair<String, Boolean>> actual = Tree.of(data).accept(new Tree.Extractor());
        List<Pair<String, Boolean>> expected = List.of(
                Pair.of("/", true));
        assertEquals(expected, actual);
    }

    @Test
    public void testRootStarAndLabelMixed() {
        List<Pair<String, Boolean>> data = List.of(
                Pair.of("/", true),
                Pair.of("/", false),
                Pair.of("dir0/dir1/dir3/file1", false),
                Pair.of("dir0/dir1/dir3/file8", false),
                Pair.of("dir0/dir1/dir3/file9", false),
                Pair.of("dir0/dir1/file2", false),
                Pair.of("dir0/dir1/", false),
                Pair.of("/", false));
        List<Pair<String, Boolean>> actual = Tree.of(data).accept(new Tree.Extractor());
        List<Pair<String, Boolean>> expected = List.of(
                Pair.of("/", true));
        assertEquals(expected, actual);
    }

    @Test
    public void testRootStarAndLabelMixed2() {
        List<Pair<String, Boolean>> data = List.of(
                Pair.of("dir0/dir1/dir3/file1", false),
                Pair.of("dir0/dir1/dir3/file8", false),
                Pair.of("dir0/dir1/dir2/file1", false),
                Pair.of("dir0/dir1/dir3/file9", false),
                Pair.of("dir0/dir1/dir2/", true),
                Pair.of("dir0/dir1/", false),
                Pair.of("dir0/dir1/file2", false),
                Pair.of("dir0/dir1/dir2/file2", false));
        List<Pair<String, Boolean>> actual = Tree.of(data).accept(new Tree.Extractor());
        List<Pair<String, Boolean>> expected = List.of(
                Pair.of("dir0/dir1/", false),
                Pair.of("dir0/dir1/dir2/", true),
                Pair.of("dir0/dir1/dir3/file1", false),
                Pair.of("dir0/dir1/dir3/file8", false),
                Pair.of("dir0/dir1/dir3/file9", false));
        assertEquals(expected, actual);
    }

    @Test
    public void testSeveralLevelOfLevels() {
        List<Pair<String, Boolean>> data = List.of(
                Pair.of("dir0/dir1/dir3/file1", false),
                Pair.of("dir0/dir1/", false),
                Pair.of("dir0/dir1/dir3/", false),
                Pair.of("dir0/dir1/dir3/file8", false),
                Pair.of("dir0/dir1/dir3/file9", false),
                Pair.of("dir0/dir1/file2", false),
                Pair.of("dir0/dir1/", false),
                Pair.of("/", false));
        List<Pair<String, Boolean>> actual = Tree.of(data).accept(new Tree.Extractor());
        List<Pair<String, Boolean>> expected = List.of(
                Pair.of("/", false),
                Pair.of("dir0/dir1/", false),
                Pair.of("dir0/dir1/dir3/", false));
        assertEquals(expected, actual);
    }

    @Test
    public void testBuildPathWithLeafs() {
        List<Pair<String, Boolean>> data = List.of(
                Pair.of("dir0/dir1/dir3/file1", false),
                Pair.of("dir0/dir1/dir3/file8", false),
                Pair.of("dir0/dir1/dir3/file9", false),
                Pair.of("dir0/dir1/file2", false),
                Pair.of("dir0/dir1/", true),
                Pair.of("dir0/dir1/dir3/dir4/dir5/file6", false),
                Pair.of("dir0/dir1/file4", true),
                Pair.of("file5", false));
        List<Pair<String, Boolean>> actual = Tree.of(data).accept(new Tree.Extractor());
        var expected = List.of(
                Pair.of("dir0/dir1/", true),
                Pair.of("file5", false));
        assertEquals(expected, actual);
    }

    @Test
    public void testSearchNode() {
        Node<String, Void> r = new Tree.AnchorNode<>("");
        Node<String, Void> n1 = new Tree.BranchNode<>("1", r.findNodeWith("/").get());
        Node<String, Void> n2 = new Tree.SearchNode<>("1");
        assertEquals(n1, n2);
        assertEquals(n2, n1);
        assertEquals(n1.hashCode(), n2.hashCode());
    }
}
