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

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import io.jitstatic.utils.Pair;

public class Tree implements TreeVisitable {
    private static final Pair<String, Boolean> ALL = Pair.of("/", Boolean.TRUE);
    private static final Pair<String, Boolean> LEVEL = Pair.of("/", Boolean.FALSE);

    private final String name;
    private final Tree trunk;
    private final Set<Tree> branches;
    private final Set<Tree> leafs;
    private final boolean recursive;
    private final boolean exclusive;

    private Tree(final Collection<Pair<String, Boolean>> parts) {
        this(null, parts, "/", parts.contains(ALL), parts.contains(LEVEL));
    }

    private Tree(final Tree parent, final Collection<Pair<String, Boolean>> parts, final String name, final boolean recursive,
            final boolean exlusive) {
        this.trunk = parent;
        this.name = Objects.requireNonNull(name);
        this.recursive = recursive;
        this.exclusive = recursive || exlusive;
        if (Objects.requireNonNull(parts).isEmpty()) {
            branches = leafs = Set.of();
            return;
        }

        final Map<String, Set<Pair<String, Boolean>>> mappedParts = new HashMap<>();
        this.leafs = debone(parts, mappedParts);
        branches = Collections.unmodifiableSet(extractBranches(mappedParts));
    }

    protected Tree(final Tree parent, final Collection<Pair<String, Boolean>> parts, final String name, final boolean recursive) {
        this(parent, parts, name, recursive, false);
    }

    private Set<Tree> debone(final Collection<Pair<String, Boolean>> parts, final Map<String, Set<Pair<String, Boolean>>> map) {
        if (parts.contains(ALL)) {
            return Set.of();
        }
        final Set<Tree> leafs = new HashSet<>();
        boolean clearLeafs = false;
        for (Pair<String, Boolean> part : parts) {
            if (part.equals(LEVEL)) {
                clearLeafs |= true;
            }
            final String element = part.getLeft();
            final int firstSlash = element.indexOf('/');
            if (firstSlash != -1) {
                final String head = element.substring(0, firstSlash);
                if (!head.isEmpty()) {
                    map.compute(head, (k, set) -> {
                        final String tail = extractTail(element, firstSlash);
                        if (set == null) {
                            final Set<Pair<String, Boolean>> local = new HashSet<>();
                            local.add(Pair.of(tail, part.getRight()));
                            return local;
                        }
                        set.add(Pair.of(tail, part.getRight()));
                        return set;
                    });
                }
            } else {
                leafs.add(new Leaf(this, element));
            }
        }
        if (clearLeafs) {
            leafs.clear();
        }
        return Collections.unmodifiableSet(leafs);
    }

    private HashSet<Tree> extractBranches(final Map<String, Set<Pair<String, Boolean>>> map) {
        return map.entrySet().stream().map(e -> {
            final Set<Pair<String, Boolean>> value = e.getValue();
            if (value.remove(ALL)) {
                value.clear();
                value.add(ALL);
            }
            return e;
        }).collect(HashSet::new, (set, entry) -> {
            final Set<Pair<String, Boolean>> value = entry.getValue();
            if (value.contains(ALL)) {
                set.add(new Branch(this, Collections.emptyList(), entry.getKey(), true));
            } else {
                if (value.contains(LEVEL)) {
                    set.add(new Branch(this, Collections.emptyList(), entry.getKey(), false));
                }
                set.add(new Branch(this, value, entry.getKey(), false));
            }
        }, (a, b) -> {
            a.addAll(b);
        });
    }

    private String extractTail(String s, int firstSlash) {
        String tail = s.substring(firstSlash + 1, s.length());
        return (tail.isEmpty() ? "/" : tail);
    }

    public static Tree of(final List<Pair<String, Boolean>> data) {
        return new Tree(data);
    }

    @Override
    public String toString() {
        return "Tree [name=" + getName() + ", branches=" + getBranches() + ", leafs=" + getLeafs() + "]";
    }

    static class Branch extends Tree implements TreeVisitable {

        protected Branch(final Tree parent, final Collection<Pair<String, Boolean>> parts, final String name, boolean recursive) {
            super(parent, parts, name, recursive);
        }

        @Override
        public String toString() {
            final String parent = (getTrunk() instanceof Branch ? "pBranch=" : "trunk=");
            return "Branch [" + parent + getTrunk().getName() + ", name=" + getName() + ", branches=" + getBranches() + ", leafs="
                    + getLeafs() + "]";
        }

        @Override
        public <T> T accept(final TreeVisitor<T> visitor) {
            return visitor.visit(this);
        }
    }

    static class Leaf extends Tree implements TreeVisitable {

        protected Leaf(final Tree parent, final String name) {
            super(parent, Collections.emptyList(), name, false);
        }

        @Override
        public String toString() {
            final String parent = (getTrunk() instanceof Branch ? "pBranch=" : "trunk=");
            return "Leaf [" + parent + getTrunk().getName() + ", name=" + getName() + "]";
        }

        @Override
        public <T> T accept(final TreeVisitor<T> visitor) {
            return visitor.visit(this);
        }
    }

    @Override
    public <T> T accept(final TreeVisitor<T> visitor) {
        return visitor.visit(this);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + (name.hashCode());
        result = prime * result + ((trunk == null) ? 0 : trunk.name.hashCode());
        result = prime * result + (recursive ? 1231 : 1237);
        result = prime * result + (branches.size());
        result = prime * result + (leafs.size());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        Tree other = (Tree) obj;
        if (name == null) {
            if (other.name != null)
                return false;
        } else if (!name.equals(other.name))
            return false;
        if (trunk == null) {
            if (other.trunk != null)
                return false;
        } else if (!trunk.equals(other.trunk))
            return false;
        if (recursive != other.recursive)
            return false;
        if (branches.size() != other.branches.size())
            return false;
        if (leafs.size() != other.leafs.size())
            return false;
        return true;
    }

    protected String getName() {
        return name;
    }

    protected Tree getTrunk() {
        return trunk;
    }

    protected Set<Tree> getBranches() {
        return branches;
    }

    protected Set<Tree> getLeafs() {
        return leafs;
    }

    protected boolean isRecursive() {
        return recursive;
    }

    protected boolean isExclusive() {
        return exclusive;
    }
}
