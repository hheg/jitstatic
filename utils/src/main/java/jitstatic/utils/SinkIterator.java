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

import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;

public class SinkIterator<T> implements Iterator<T> {

	private final List<T> victim;
	private int cursor = -1;
	private int lastRet = -1;
	private int size;

	public SinkIterator(final List<T> victim) {
		this.victim = victim;
		size = victim.size();
	}

	@Override
	public boolean hasNext() {
		return size > 0;
	}

	@Override
	public T next() {
		int i = cursor;
		if (i == size - 1) {
			i = -1;
		}
		i++;
		return victim.get(lastRet = cursor = i);
	}

	@Override
	public void remove() {
		if (lastRet < 0)
			throw new IllegalStateException();
		try {
			victim.remove(lastRet);
			size--;
			cursor = lastRet - 1;
			lastRet = -1;
		} catch (IndexOutOfBoundsException ex) {
			throw new ConcurrentModificationException();
		}
	}
}
