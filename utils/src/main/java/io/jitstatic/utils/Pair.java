package io.jitstatic.utils;

import java.util.Map;

/*-
 * #%L
 * jitstatic
 * %%
 * Copyright (C) 2017 H.Hegardt
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

public class Pair<L, R> implements Map.Entry<L, R> {
    private static final Pair<?, ?> PAIR_OF_NOTHING = new Pair<>();
    private final L left;
    private final R right;

    public Pair() {
        this.left = null;
        this.right = null;
    }

    public Pair(final L t, final R u) {
        this.left = t;
        this.right = u;
    }

    public L getLeft() {
        return left;
    }

    public R getRight() {
        return right;
    }

    public boolean isPresent() {
        return left != null && right != null;
    }

    public static <T, U> Pair<T, U> of(T t, U u) {
        return new Pair<>(t, u);
    }

    public static <T, U> Pair<T, U> ofNothing() {
        @SuppressWarnings("unchecked")
        Pair<T, U> p = (Pair<T, U>) PAIR_OF_NOTHING;
        return p;
    }

    @Override
    public String toString() {
        return "Pair [left=" + left + ", right=" + right + "]";
    }

    @Override
    public L getKey() {
        return left;
    }

    @Override
    public R getValue() {
        return right;
    }

    @Override
    public R setValue(R value) {
        throw new UnsupportedOperationException(String.format("Trying to set value %s in immutable %s", value, toString()));
    }
}
