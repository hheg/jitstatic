package jitstatic.utils;

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

public class Pair<T, U> {
	private final T left;
	private final U right;

	public Pair() {
		this.left = null;
		this.right = null;
	}

	public Pair(final T t, final U u) {
		this.left = t;
		this.right = u;
	}

	public T getLeft() {
		return left;
	}

	public U getRight() {
		return right;
	}

	public boolean isPresent() {
		return left != null && right != null;
	}
	
	public static <T, U> Pair<T, U> of(T t, U u) {
		return new Pair<>(t, u);
	}
	
	public static <T,U> Pair<T,U> ofNothing(){
		return new Pair<>();
	}
}
