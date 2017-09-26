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

import java.io.File;
import java.io.IOException;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.errors.AmbiguousObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.RevisionSyntaxException;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;

public class StorageCheckerTest {

	private static final String store = "data";
	@Rule
	public ExpectedException ex = ExpectedException.none();
	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private Git bareGit;
	private Git workingGit;

	@Before
	public void setup() throws Exception {
		final File base = folder.newFolder();
		final File wBase = folder.newFolder();
		bareGit = Git.init().setBare(true).setDirectory(base).call();
		workingGit = Git.cloneRepository().setURI(bareGit.getRepository().getDirectory().toURI().toString())
				.setDirectory(wBase).call();

		RemoteTestUtils.copy("/test3.json", wBase.toPath().resolve(store));
		workingGit.add().addFilepattern(store).call();
		workingGit.commit().setMessage("Initial commit").call();
		workingGit.push().call();
	}

	@After
	public void tearDown() {
		bareGit.close();
		workingGit.close();
	}

	@Test
	public void testCheckStorageFileOnBareRepo()
			throws RevisionSyntaxException, AmbiguousObjectException, IncorrectObjectTypeException, IOException {
		try (StorageChecker sc = new StorageChecker(bareGit.getRepository());) {
			sc.check(store, "master");
		}
	}

	@Test
	public void testCheckStorageFileOnNormalRepo()
			throws RevisionSyntaxException, AmbiguousObjectException, IncorrectObjectTypeException, IOException {
		try (StorageChecker sc = new StorageChecker(bareGit.getRepository());) {
			sc.check(store, "master");
		}
	}

	@Test
	public void testFileIsNotFound()
			throws RevisionSyntaxException, AmbiguousObjectException, IncorrectObjectTypeException, IOException {
		ex.expect(IllegalStateException.class);
		ex.expectMessage("Did not find expected file '");
		try (StorageChecker sc = new StorageChecker(bareGit.getRepository());) {
			sc.check("someotherfile", "master");
		}
	}
	
	@Test
	@Ignore("This must be required?")
	//TODO FIX this...
	public void testBranchNotFound() throws RevisionSyntaxException, AmbiguousObjectException, IncorrectObjectTypeException, IOException {
		try (StorageChecker sc = new StorageChecker(bareGit.getRepository());) {
			sc.check(store, "other");
		}
	}

}
