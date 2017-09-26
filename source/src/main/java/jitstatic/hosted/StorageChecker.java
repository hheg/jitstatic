package jitstatic.hosted;

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
import java.util.Objects;

import org.eclipse.jgit.errors.AmbiguousObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.RevisionSyntaxException;
import org.eclipse.jgit.lib.Repository;

public class StorageChecker implements AutoCloseable {

	private final Repository repository;
	private static final StorageJSONParser parser = new StorageJSONParser();

	public StorageChecker(final Repository repository) {
		this.repository = Objects.requireNonNull(repository);
		repository.incrementOpen();
	}

	public void check(final String store, final String branch)
			throws RevisionSyntaxException, AmbiguousObjectException, IncorrectObjectTypeException, IOException {
		try(InputStream source = StorageExtractor.sourceExtractor(repository, branch, store);){
			parser.parse(source);
		}
	}

	@Override
	public void close() {
		this.repository.close();
	}

}
