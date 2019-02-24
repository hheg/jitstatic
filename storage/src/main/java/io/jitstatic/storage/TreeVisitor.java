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

import io.jitstatic.storage.Tree.AnchorNode;
import io.jitstatic.storage.Tree.BranchNode;
import io.jitstatic.storage.Tree.LeafNode;
import io.jitstatic.storage.Tree.LevelNode;
import io.jitstatic.storage.Tree.RootNode;
import io.jitstatic.storage.Tree.StarNode;

public interface TreeVisitor<U extends Comparable<U>, X, T> {
    public T visit(RootNode<U, X> node);

    public T visit(BranchNode<U, X> node);

    public T visit(LeafNode<U, X> node);

    public T visit(AnchorNode<U, X> node);

    public T visit(StarNode<U, X> node);

    public T visit(LevelNode<U, X> node);
}
