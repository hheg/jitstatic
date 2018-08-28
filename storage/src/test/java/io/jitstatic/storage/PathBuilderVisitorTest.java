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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.jitstatic.utils.Pair;

public class PathBuilderVisitorTest {

    @Test
    public void testBuildPathWithDirectoryAndFile() {
        // @formatter:off
        List<Pair<String, Boolean>> data = List.of(
                Pair.of("dir0/dir1/dir3/file1", false), 
                Pair.of("dir0/dir1/dir3/file8", false), 
                Pair.of("dir0/dir1/dir3/file9", false),
                Pair.of("dir0/dir1/file2", false), 
                Pair.of("dir0/dir1/", false)
                );
        // @formatter:on
        Tree tree = Tree.of(data);
        tree.accept(new PrintingVisitor(System.out));
        PathBuilderVisitor pbv = new PathBuilderVisitor();
        List<Pair<String, Boolean>> elements = tree.accept(pbv);
        elements.stream().forEach(System.out::println);
        assertTrue(data.containsAll(elements));
        List<Pair<String, Boolean>> elems = new ArrayList<>(elements);
        elems.add(Pair.of("dir0/dir1/file2", false));
        assertTrue(elems.containsAll(data));
    }

    @Test
    public void testBuildPathWithRootDirectoryAndFile() {
        // @formatter:off
        List<Pair<String, Boolean>> data = List.of(
                Pair.of("/", false),
                Pair.of("dir0/dir1/dir3/file1", false), 
                Pair.of("dir0/dir1/dir3/file8", false), 
                Pair.of("dir0/dir1/dir3/file9", false),
                Pair.of("dir0/dir1/file2", false), 
                Pair.of("dir0/dir1/", false)
                );
        // @formatter:on
        Tree tree = Tree.of(data);
        tree.accept(new PrintingVisitor(System.out));
        PathBuilderVisitor pbv = new PathBuilderVisitor();
        List<Pair<String, Boolean>> elements = tree.accept(pbv);
        elements.stream().forEach(System.out::println);
        assertTrue(data.containsAll(elements));
        List<Pair<String, Boolean>> elems = new ArrayList<>(elements);
        elems.add(Pair.of("dir0/dir1/file2", false));
        assertTrue(elems.containsAll(data));
    }

    @Test
    public void testBuildPathWithRootDirectoryAll() {
        // @formatter:off
        List<Pair<String, Boolean>> data = List.of(
                Pair.of("/", true),
                Pair.of("dir0/dir1/dir3/file1", false), 
                Pair.of("dir0/dir1/dir3/file8", false), 
                Pair.of("dir0/dir1/dir3/file9", false),
                Pair.of("dir0/dir1/file2", false), 
                Pair.of("dir0/dir1/", false)
                );
        // @formatter:on
        Tree tree = Tree.of(data);
        tree.accept(new PrintingVisitor(System.out));
        PathBuilderVisitor pbv = new PathBuilderVisitor();
        List<Pair<String, Boolean>> elements = tree.accept(pbv);
        elements.stream().forEach(System.out::println);
        assertEquals(List.of(Pair.of("/", true)), elements);
    }

    @Test
    public void testBuildPathWithLeafs() {
        // @formatter:off        
        List<Pair<String, Boolean>> data = List.of(
                Pair.of("dir0/dir1/dir3/file1", false),
                Pair.of("dir0/dir1/dir3/file8", false), 
                Pair.of("dir0/dir1/dir3/file9", false), 
                Pair.of("dir0/dir1/file2", false),
                Pair.of("dir0/dir1/", true), 
                Pair.of("dir0/dir1/dir3/dir4/dir5/file6", false),
                Pair.of("dir0/dir1/file4", true), 
                Pair.of("file5", false));
        // @formatter:on
        Tree tree = Tree.of(data);
        tree.accept(new PrintingVisitor(System.out));
        PathBuilderVisitor pbv = new PathBuilderVisitor();
        List<Pair<String, Boolean>> elements = tree.accept(pbv);
        elements.stream().forEach(System.out::println);
        Tree expected = Tree.of(List.of(Pair.of("dir0/dir1/", true), Pair.of("file5", false)));
        assertEquals(expected, tree);
    }

    @Test
    public void testRootDirLevel() {
        Tree rootTree = Tree.of(List.of(Pair.of("/", false)));
        PathBuilderVisitor pbv = new PathBuilderVisitor();
        List<Pair<String, Boolean>> elements = rootTree.accept(pbv);
        assertTrue(elements.size() == 1);
        elements.stream().forEach(System.out::println);
    }

    @Test
    public void testRootDirAll() {
        Tree rootTree = Tree.of(List.of(Pair.of("/", true)));
        PathBuilderVisitor pbv = new PathBuilderVisitor();
        List<Pair<String, Boolean>> elements = rootTree.accept(pbv);
        assertTrue(elements.size() == 1);
        elements.stream().forEach(System.out::println);
    }
}
