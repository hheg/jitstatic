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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.jitstatic.storage.Tree.Branch;
import io.jitstatic.storage.Tree.Leaf;
import io.jitstatic.utils.Pair;

public class PathBuilderVisitor implements TreeVisitor<List<Pair<String, Boolean>>> {

    @Override
    public List<Pair<String, Boolean>> visit(final Tree tree) {
        final List<Pair<String, Boolean>> trees = new ArrayList<>();
        for (Tree t : tree.getBranches()) {
            trees.addAll(t.accept(this));
        }
        for (Tree t : tree.getLeafs()) {
            trees.addAll(t.accept(this));
        }
        if (tree.isExclusive()) {
            trees.add(Pair.of(tree.getName(), tree.isRecursive()));
        }
        return trees;
    }

    @Override
    public List<Pair<String, Boolean>> visit(final Branch tree) {
        if (tree.getBranches().isEmpty() && tree.getLeafs().isEmpty()) {
            return List.of(Pair.of(tree.getName() + "/", tree.isRecursive()));
        } else {
            final List<Pair<String, Boolean>> trees = new ArrayList<>();
            for (Tree t : tree.getBranches()) {
                trees.addAll(t.accept(this));
            }
            for (Tree t : tree.getLeafs()) {
                trees.addAll(t.accept(this));
            }
            return trees.stream().map(appendParent(tree)).collect(Collectors.toList());
        }
    }

    private Function<? super Pair<String, Boolean>, ? extends Pair<String, Boolean>> appendParent(final Branch tree) {
        return p -> Pair.of(tree.getName() + "/" + p.getLeft(), p.getRight());
    }

    @Override
    public List<Pair<String, Boolean>> visit(final Leaf tree) {
        return List.of(Pair.of(tree.getName(), false));
    }

}
