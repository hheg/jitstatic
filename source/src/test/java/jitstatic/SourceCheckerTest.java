package jitstatic;

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
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.errors.AmbiguousObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.RevisionSyntaxException;
import org.eclipse.jgit.lib.Constants;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;

import jitstatic.hosted.RemoteTestUtils;

public class SourceCheckerTest {

	private static final String REF_HEAD_MASTER = Constants.R_HEADS + "master";
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
	public void testCheckSourceFileOnBareRepo() throws RevisionSyntaxException, AmbiguousObjectException,
			IncorrectObjectTypeException, IOException, RefNotFoundException {
		try (SourceChecker sc = new SourceChecker(bareGit.getRepository());) {
			sc.checkBranchForErrors(REF_HEAD_MASTER);
		}
	}

	@Test
	public void testCheckSourceFileOnNormalRepo() throws RevisionSyntaxException, AmbiguousObjectException,
			IncorrectObjectTypeException, IOException, RefNotFoundException {
		try (SourceChecker sc = new SourceChecker(bareGit.getRepository());) {
			sc.checkBranchForErrors(REF_HEAD_MASTER);
		}
	}

	@Test
	public void testCorrectFormattedBranchNotFound() throws RevisionSyntaxException, AmbiguousObjectException,
			IncorrectObjectTypeException, IOException, RefNotFoundException {
		final String branch = Constants.R_HEADS + "other";
		ex.expect(RefNotFoundException.class);
		ex.expectMessage(branch);
		try (SourceChecker sc = new SourceChecker(bareGit.getRepository());) {
			sc.checkBranchForErrors(branch);
		}
	}

	@Test
	public void testNotCorrectFormattedBranchNotFound() throws RevisionSyntaxException, AmbiguousObjectException,
			IncorrectObjectTypeException, IOException, RefNotFoundException {
		final String branch = "other";
		ex.expect(RefNotFoundException.class);
		ex.expectMessage(branch);
		try (SourceChecker sc = new SourceChecker(bareGit.getRepository());) {
			sc.checkBranchForErrors(branch);
		}
	}
}
