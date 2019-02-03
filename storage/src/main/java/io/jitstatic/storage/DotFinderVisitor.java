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

import java.util.Set;

import io.jitstatic.storage.Tree.Branch;
import io.jitstatic.storage.Tree.Leaf;

public class DotFinderVisitor implements TreeVisitor<Boolean> {

    @Override
    public Boolean visit(Tree tree) {
        if (tree.getName().startsWith(".")) {
            return true;
        }
        if (hasDotFile(tree.getLeafs())) {
            return true;
        }
        return hasDotFile(tree.getBranches());
    }

    @Override
    public Boolean visit(Branch tree) {
        return visit((Tree) tree);
    }

    @Override
    public Boolean visit(Leaf tree) {
        return tree.getName().startsWith(".");
    }

    private boolean hasDotFile(Set<Tree> tree) {
        for (Tree leaf : tree) {
            if (leaf.accept(this)) {
                return true;
            }
        }
        return false;
    }
}
