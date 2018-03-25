package io.jitstatic.hosted;

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

import java.io.IOException;
import java.io.InputStream;
import java.util.NoSuchElementException;

import org.eclipse.jgit.lib.ObjectLoader;

public class InputStreamHolder {
	private final ObjectLoader loader;
	private final Exception e;

	public InputStreamHolder(final ObjectLoader ol) {
		this.loader = ol;
		this.e = null;
	}

	public InputStreamHolder(final Exception e) {
		this.loader = null;
		this.e = e;
	}

	public boolean isPresent() {
		return loader != null;
	}

	public InputStream inputStream() throws IOException {
		if (isPresent()) {
			return loader.openStream();
		}
		throw new NoSuchElementException();
	}

	public Exception exception() {
		if (!isPresent()) {
			return e;
		}
		throw new NoSuchElementException();
	}
}