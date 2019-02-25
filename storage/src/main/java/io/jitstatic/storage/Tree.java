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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;

import io.jitstatic.utils.Pair;

public class Tree implements TreeVisitable<String, Pair<String, Boolean>> {
    public static final DotFinder DOT_FINDER = new DotFinder();
    private final Node<String, Pair<String, Boolean>> root;
    private static final String FWD_SLASH = "/";
    

    private Tree(final List<Pair<String, Boolean>> data) {
        final Node<String, Pair<String, Boolean>> node = new AnchorNode<>(FWD_SLASH);
        for (Pair<String, Boolean> p : data) {
            String key = p.getLeft().replaceAll("/+", "/");
            if (key.startsWith("/") && key.length() > 1) {
                key = key.replaceFirst("^/+", "");
            }
            node.accept(new Inserter(Pair.of(key, p.getRight())));
        }
        this.root = node;
    }

    public static Tree of(final List<Pair<String, Boolean>> data) {
        return new Tree(data);
    }

    abstract static class Node<T extends Comparable<T>, X> implements TreeVisitable<T, X>, Comparable<Node<T, X>> {
        protected final Collection<Node<T, X>> children;
        protected final T value;
        protected Node<T, X> parent;

        Node(final T value) {
            this(value, null, new TreeSet<>());
        }

        Node(final T value, final Node<T, X> parent, final Collection<Node<T, X>> children) {
            this.value = value;
            this.children = Objects.requireNonNull(children);
            this.parent = parent;
            if (parent != null) {
                parent.addChild(this);
            }
        }

        protected void addChild(final Node<T, X> child) {
            if (child == this) {
                throw new IllegalArgumentException("node can't be child and it's own parent");
            }
            children.add(child);
        }

        protected void removeChild(final Node<T, X> child) {
            children.remove(child);
        }

        Node<T, X> createOrGetLeaf(final T value) {
            return findNodeWith(value).orElseGet(() -> new LeafNode<>(value, this));
        }

        Node<T, X> createOrGetBranch(final T value) {
            return findNodeWith(value).orElseGet(() -> new BranchNode<>(value, this));
        }

        protected abstract Optional<Node<T, X>> findNodeWith(final T value);

        private static <T extends Comparable<T>, X> Optional<Node<T, X>> extractFromTreeSet(final Collection<Node<T, X>> children, final T value) {
            final SortedSet<Node<T, X>> tailSet = ((TreeSet<Node<T, X>>) children).tailSet(new SearchNode<>(value));
            if (tailSet.isEmpty()) {
                return Optional.empty();
            }
            final Node<T, X> first = tailSet.first();
            return first.value.equals(value) ? Optional.of(first) : Optional.empty();
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((value == null) ? 0 : value.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != SearchNode.class && obj.getClass() != SearchNode.class) {
                if (getClass() != obj.getClass())
                    return false;
            }
            @SuppressWarnings("rawtypes")
            Node other = (Node) obj;
            if (value == null) {
                if (other.value != null)
                    return false;
            } else if (!value.equals(other.value))
                return false;
            return true;
        }

        @Override
        public int compareTo(Node<T, X> o) {
            if (this.equals(o)) {
                return 0;
            }
            return this.value.compareTo(o.value);
        }
    }

    static final class SearchNode<T extends Comparable<T>, X> extends Node<T, X> {

        SearchNode(T value) {
            super(value, null, List.of());
        }

        @Override
        public <U> U accept(TreeVisitor<T, X, U> visitor) {
            throw new UnsupportedOperationException(getClass() + ".accept(Treevisitor) is not supported");
        }

        @Override
        protected Optional<Node<T, X>> findNodeWith(T value) {
            throw new UnsupportedOperationException(getClass() + ".findNodeWith(T) is not supported");
        }

        @Override
        public Node<T, X> createOrGetBranch(final T value) {
            throw new UnsupportedOperationException(getClass() + ".createOrGetBranch(T) is not supported");
        }

        @Override
        public Node<T, X> createOrGetLeaf(final T value) {
            throw new UnsupportedOperationException(getClass() + ".createOrGetLeaf(T) is not supported");
        }

        @Override
        protected void removeChild(final Node<T, X> child) {
            throw new UnsupportedOperationException(getClass() + ".removeChild(Node) is not supported");
        }

        @Override
        protected void addChild(final Node<T, X> child) {
            throw new UnsupportedOperationException(getClass() + ".addChild(Node) is not supported");
        }
    }

    static class BranchNode<T extends Comparable<T>, X> extends Node<T, X> {

        BranchNode(final T value, final Node<T, X> parent) {
            super(value, Objects.requireNonNull(parent), new TreeSet<>());
        }

        @Override
        public <U> U accept(final TreeVisitor<T, X, U> visitor) {
            return visitor.visit(this);
        }

        @Override
        protected Optional<Node<T, X>> findNodeWith(T value) {
            return Node.extractFromTreeSet(children, value);
        }
    }

    static class AnchorNode<T extends Comparable<T>, X> extends Node<T, X> {

        AnchorNode(T value) {
            super(null, null, new ArrayList<>(1));
            new RootNode<>(value, this);
        }

        @Override
        protected void addChild(final Node<T, X> child) {
            if (children.size() == 1) {
                throw new UnsupportedOperationException("Cannot add more children to an AnchorNode");
            }
            super.addChild(child);
        }

        @Override
        public <U> U accept(final TreeVisitor<T, X, U> visitor) {
            // Skip visit this
            return children.iterator().next().accept(visitor);
        }

        @Override
        protected Optional<Node<T, X>> findNodeWith(T value) {
            return Optional.of(((ArrayList<Node<T, X>>) children).get(0));
        }
    }

    static class LevelNode<T extends Comparable<T>, X> extends Node<T, X> {
        LevelNode(final T value, final Node<T, X> parent, final Collection<Node<T, X>> inheritedChildren) {
            super(value, parent, inheritedChildren);
            final Iterator<Node<T, X>> iterator = inheritedChildren.iterator();
            while (iterator.hasNext()) {
                final Node<T, X> child = iterator.next();
                if (child instanceof LeafNode && !(child instanceof StarNode)) {
                    iterator.remove();
                } else {
                    child.parent = this;
                }
            }
        }

        LevelNode(final T value, final Node<T, X> parent) {
            this(value, parent, new TreeSet<>());
        }

        @Override
        public <U> U accept(final TreeVisitor<T, X, U> visitor) {
            return visitor.visit(this);
        }

        @Override
        public Node<T, X> createOrGetLeaf(final T value) {
            return null;
        }

        @Override
        protected Optional<Node<T, X>> findNodeWith(T value) {
            return Node.extractFromTreeSet(children, value);
        }
    }

    static class LeafNode<T extends Comparable<T>, X> extends Node<T, X> {
        LeafNode(final T value, final Node<T, X> parent) {
            this(value, parent, List.of());
        }

        LeafNode(final T value, final Node<T, X> parent, final Collection<Node<T, X>> children) {
            super(value, Objects.requireNonNull(parent), children);
        }

        @Override
        public <U> U accept(final TreeVisitor<T, X, U> visitor) {
            return visitor.visit(this);
        }

        @Override
        public Node<T, X> createOrGetLeaf(final T value) {
            throw new UnsupportedOperationException(getClass() + ".createOrGetLeaf(Node) is not supported");
        }

        @Override
        protected Optional<Node<T, X>> findNodeWith(T value) {
            return Optional.empty();
        }
    }

    static class StarNode<T extends Comparable<T>, X> extends LeafNode<T, X> {

        StarNode(final T value, final Node<T, X> parent) {
            super(value, parent);
        }

        @Override
        public <U> U accept(final TreeVisitor<T, X, U> visitor) {
            return visitor.visit(this);
        }
    }

    static class RootNode<T extends Comparable<T>, X> extends Node<T, X> {

        RootNode(final T value, final Node<T, X> parent) {
            super(value, Objects.requireNonNull(parent), new TreeSet<>());
        }

        @Override
        public <U> U accept(final TreeVisitor<T, X, U> visitor) {
            return visitor.visit(this);
        }

        @Override
        protected Optional<Node<T, X>> findNodeWith(T value) {
            return Node.extractFromTreeSet(children, value);
        }

    }

    private static class Inserter implements TreeVisitor<String, Pair<String, Boolean>, Void> {

        private final Pair<String, Boolean> p;

        public Inserter(final Pair<String, Boolean> p) {
            this.p = Objects.requireNonNull(p);
        }

        public Void visitNode(final Node<String, Pair<String, Boolean>> parent) {
            final String element = p.getLeft();
            final int firstSlash = element.indexOf(FWD_SLASH);
            if (firstSlash > -1) {
                final String head = element.substring(0, firstSlash);
                if (element.length() != firstSlash + 1) {
                    return parent.createOrGetBranch(head)
                            .accept(new Inserter(Pair.of(extractTail(element, firstSlash), p.getRight())));
                } else {
                    if (p.getRight()) {
                        parent.findNodeWith(head).ifPresent(parent::removeChild);
                        new StarNode<>(head, parent);
                    } else {
                        parent.findNodeWith(head)
                                .ifPresentOrElse(n -> n.accept(new LevelNodeInserter(head)), () -> new LevelNode<>(head, parent));
                    }
                }
            } else {
                parent.createOrGetLeaf(element);
            }
            return null;
        }

        private static String extractTail(final String s, final int firstSlash) {
            return s.substring(firstSlash + 1, s.length());
        }

        @Override
        public Void visit(final RootNode<String, Pair<String, Boolean>> node) {
            final String element = p.getLeft();
            if (FWD_SLASH.equals(element)) {
                node.parent.children.remove(node);
                if (p.getRight()) {
                    new StarNode<>(element, node.parent);
                } else {
                    new LevelNode<>(element, node.parent, node.children);
                }
                return null;
            } else {
                return visitNode(node);
            }
        }

        @Override
        public Void visit(final BranchNode<String, Pair<String, Boolean>> node) {
            return visitNode(node);
        }

        @Override
        public Void visit(final LeafNode<String, Pair<String, Boolean>> node) {
            return null;
        }

        @Override
        public Void visit(final AnchorNode<String, Pair<String, Boolean>> node) {
            return node.children.iterator().next().accept(this);
        }

        @Override
        public Void visit(final StarNode<String, Pair<String, Boolean>> node) {
            return null;
        }

        @Override
        public Void visit(final LevelNode<String, Pair<String, Boolean>> node) {
            return visitNode(node);
        }
    }

    @Override
    public <T> T accept(final TreeVisitor<String, Pair<String, Boolean>, T> visitor) {
        return this.root.accept(visitor);
    }

    public static class Extractor implements TreeVisitor<String, Pair<String, Boolean>, List<Pair<String, Boolean>>> {

        @Override
        public List<Pair<String, Boolean>> visit(final RootNode<String, Pair<String, Boolean>> node) {
            final Iterator<Node<String, Pair<String, Boolean>>> iterator = node.children.iterator();
            final List<Pair<String, Boolean>> retVal = new ArrayList<>();
            while (iterator.hasNext()) {
                retVal.addAll(iterator.next().accept(this));
            }
            return retVal;
        }

        @Override
        public List<Pair<String, Boolean>> visit(final BranchNode<String, Pair<String, Boolean>> node) {
            return visitChildren(new ArrayList<>(), node.value + FWD_SLASH, node.children);
        }

        @Override
        public List<Pair<String, Boolean>> visit(final LeafNode<String, Pair<String, Boolean>> node) {
            return List.of(Pair.of(node.value, false));
        }

        @Override
        public List<Pair<String, Boolean>> visit(final AnchorNode<String, Pair<String, Boolean>> node) {
            return node.children.iterator().next().accept(this);
        }

        @Override
        public List<Pair<String, Boolean>> visit(final StarNode<String, Pair<String, Boolean>> node) {
            return List.of(Pair.of(checkValueForSlash(node.value, FWD_SLASH), true));
        }

        @Override
        public List<Pair<String, Boolean>> visit(final LevelNode<String, Pair<String, Boolean>> node) {
            final List<Pair<String, Boolean>> retVal = new ArrayList<>();
            retVal.add(Pair.of(checkValueForSlash(node.value, FWD_SLASH), false));
            return visitChildren(retVal, checkValueForSlash(node.value, ""), node.children);
        }

        private List<Pair<String, Boolean>> visitChildren(final List<Pair<String, Boolean>> retVal, final String value,
                final Collection<Node<String, Pair<String, Boolean>>> children) {
            for (var n : children) {
                final List<Pair<String, Boolean>> accepted = n.accept(this);
                for (var a : accepted) {
                    retVal.add(Pair.of(value + a.getLeft(), a.getRight()));
                }
            }
            return retVal;
        }

        private String checkValueForSlash(final String value, final String replaceMent) {
            return FWD_SLASH.equals(value) ? replaceMent : value + FWD_SLASH;
        }
    }

    public static class DotFinder implements TreeVisitor<String, Pair<String, Boolean>, Boolean> {

        @Override
        public Boolean visit(RootNode<String, Pair<String, Boolean>> node) {
            return visitChildren(node.children);
        }

        @Override
        public Boolean visit(BranchNode<String, Pair<String, Boolean>> node) {
            if (hasDot(node)) {
                return true;
            }
            return visitChildren(node.children);
        }

        private boolean hasDot(Node<String, Pair<String, Boolean>> node) {
            return node.value.startsWith(".");
        }

        @Override
        public Boolean visit(LeafNode<String, Pair<String, Boolean>> node) {
            return hasDot(node);
        }

        @Override
        public Boolean visit(AnchorNode<String, Pair<String, Boolean>> node) {
            return node.children.iterator().next().accept(this);
        }

        @Override
        public Boolean visit(StarNode<String, Pair<String, Boolean>> node) {
            return hasDot(node);
        }

        @Override
        public Boolean visit(LevelNode<String, Pair<String, Boolean>> node) {
            return hasDot(node);
        }

        private boolean visitChildren(final Collection<Node<String, Pair<String, Boolean>>> children) {
            for (Node<String, Pair<String, Boolean>> node : children) {
                if (node.accept(this)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static class LevelNodeInserter implements TreeVisitor<String, Pair<String, Boolean>, Void> {

        final String head;

        public LevelNodeInserter(final String head) {
            this.head = head;
        }

        @Override
        public Void visit(BranchNode<String, Pair<String, Boolean>> node) {
            return change(node);
        }

        @Override
        public Void visit(LeafNode<String, Pair<String, Boolean>> node) {
            return change(node);
        }

        private Void change(Node<String, Pair<String, Boolean>> node) {
            node.parent.removeChild(node);
            new LevelNode<>(head, node.parent, node.children);
            return null;
        }

        @Override
        public Void visit(RootNode<String, Pair<String, Boolean>> node) {
            return change(node);
        }

        @Override
        public Void visit(AnchorNode<String, Pair<String, Boolean>> node) {
            return null;
        }

        @Override
        public Void visit(StarNode<String, Pair<String, Boolean>> node) {
            return null;
        }

        @Override
        public Void visit(LevelNode<String, Pair<String, Boolean>> node) {
            return null;
        }
    }

}
