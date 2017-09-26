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
import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;

public class StorageExtractor {

	public static InputStream sourceExtractor(final Repository repository, final String branch, final String storageFile) throws AmbiguousObjectException, IncorrectObjectTypeException, IOException,
			MissingObjectException, CorruptObjectException {
		try (final RevWalk revWalk = new RevWalk(repository)) {
			final ObjectId HEAD = repository.resolve(Objects.requireNonNull(branch));
			if (HEAD == null) {
				throw new BranchNotFoundException(branch);
			}
			final RevCommit commit = revWalk.parseCommit(HEAD);
			final RevTree tree = commit.getTree();
	
			try (final TreeWalk treeWalk = new TreeWalk(repository)) {
				treeWalk.addTree(tree);
				treeWalk.setRecursive(true);
				treeWalk.setFilter(PathFilter.create(Objects.requireNonNull(storageFile)));
	
				if (!treeWalk.next()) {
					throw new IllegalStateException("Did not find expected file '" + storageFile + "'");
				}
	
				final ObjectId objectId = treeWalk.getObjectId(0);
				final ObjectLoader loader = repository.open(objectId);
				return loader.openStream();
			}
		}
	}

}
