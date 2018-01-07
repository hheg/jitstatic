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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.AbortedByHookException;
import org.eclipse.jgit.api.errors.ConcurrentRefUpdateException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.NoFilepatternException;
import org.eclipse.jgit.api.errors.NoHeadException;
import org.eclipse.jgit.api.errors.NoMessageException;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.api.errors.UnmergedPathsException;
import org.eclipse.jgit.api.errors.WrongRepositoryStateException;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;

import jitstatic.hosted.InputStreamHolder;
import jitstatic.source.SourceInfo;
import jitstatic.utils.Pair;

public class SourceExtractorTest {

	private static final String REFS_HEADS_MASTER = "refs/heads/master";
	@Rule
	public TemporaryFolder folder = new TemporaryFolder();
	@Rule
	public ExpectedException ex = ExpectedException.none();
	private Git git;

	private File workingFolder;

	@Before
	public void setup() throws IllegalStateException, GitAPIException, IOException {
		workingFolder = folder.newFolder();
		git = Git.init().setBare(true).setDirectory(workingFolder).call();
	}

	@After
	public void tearDown() {
		if (git != null) {
			git.close();
		}
	}

	@Test
	public void testCheckRepositoryUniqueBlobs()
			throws UnsupportedEncodingException, IOException, NoFilepatternException, GitAPIException {
		File tempGitFolder = folder.newFolder();
		try (Git local = Git.cloneRepository().setURI(workingFolder.toURI().toString()).setDirectory(tempGitFolder)
				.call()) {
			final String key = "file";
			for (int i = 0; i < 4; i++) {
				final String round = (i == 0 ? "" : String.valueOf(i));
				final String branchName = "master" + round;
				if (i > 0) {
					local.checkout().setCreateBranch(true).setName(branchName).call();
				}
				final Path file = tempGitFolder.toPath().resolve(key + round);
				Files.write(file, getData(i).getBytes("UTF-8"), StandardOpenOption.CREATE_NEW,
						StandardOpenOption.TRUNCATE_EXISTING);
				local.add().addFilepattern(key + round).call();
				local.commit().setMessage("Commit " + round).call();
				local.push().call();
			}

			SourceExtractor se = new SourceExtractor(git.getRepository());
			Map<Pair<AnyObjectId, Set<Ref>>, List<Pair<FileObjectIdStore, InputStreamHolder>>> allExtracted = se
					.extractAll();
			assertTrue(allExtracted.keySet().size() == 4);
			print(allExtracted);
			Optional<Pair<FileObjectIdStore, InputStreamHolder>> hasException = allExtracted.values().stream()
					.flatMap(l -> l.stream()).filter(i -> !i.getRight().isPresent()).findFirst();
			assertFalse(hasException.isPresent());

		}
	}

	@Test
	public void testCheckRepositorySameBlobs()
			throws UnsupportedEncodingException, IOException, NoFilepatternException, GitAPIException {
		File tempGitFolder = folder.newFolder();
		try (Git local = Git.cloneRepository().setURI(workingFolder.toURI().toString()).setDirectory(tempGitFolder)
				.call()) {
			final String key = "file";
			for (int i = 0; i < 4; i++) {
				final String round = (i == 0 ? "" : String.valueOf(i));
				final String branchName = "master" + round;
				if (i > 0) {
					local.checkout().setCreateBranch(true).setName(branchName).call();
				}
				final Path file = tempGitFolder.toPath().resolve(key + round);
				Files.write(file, getData().getBytes("UTF-8"), StandardOpenOption.CREATE_NEW,
						StandardOpenOption.TRUNCATE_EXISTING);
				local.add().addFilepattern(key + round).call();
				local.commit().setMessage("Commit " + round).call();
				local.push().call();
			}

			SourceExtractor se = new SourceExtractor(git.getRepository());
			Map<Pair<AnyObjectId, Set<Ref>>, List<Pair<FileObjectIdStore, InputStreamHolder>>> allExtracted = se
					.extractAll();
			assertTrue(allExtracted.keySet().size() == 4);
			print(allExtracted);
			Optional<Pair<FileObjectIdStore, InputStreamHolder>> hasException = allExtracted.values().stream()
					.flatMap(l -> l.stream()).filter(i -> !i.getRight().isPresent()).findFirst();
			assertFalse(hasException.isPresent());

			Optional<Pair<ObjectId, Set<String>>> reduce = allExtracted.values().stream().flatMap(l -> l.stream())
					.map(m -> new Pair<ObjectId, Set<String>>(m.getLeft().getObjectId(),
							new HashSet<>(Arrays.asList(m.getLeft().getFileName()))))
					.reduce((a, b) -> {
						assertEquals(a.getLeft(), b.getLeft());
						a.getRight().addAll(b.getRight());
						return a;
					});
			assertTrue(reduce.isPresent());
			assertTrue(reduce.get().getRight().size() == 4);
		}
	}

	@Test
	public void testCheckBranchWithRemovedObjects()
			throws UnsupportedEncodingException, IOException, NoFilepatternException, GitAPIException {
		final String key = "file";
		final File tempGitFolder = folder.newFolder();
		try (final Git local = Git.cloneRepository().setURI(workingFolder.toURI().toString())
				.setDirectory(tempGitFolder).call()) {
			final Path file = tempGitFolder.toPath().resolve(key);
			Files.write(file, getData().getBytes("UTF-8"), StandardOpenOption.CREATE_NEW,
					StandardOpenOption.TRUNCATE_EXISTING);
			local.add().addFilepattern(key).call();
			local.commit().setMessage("Init commit").call();
			local.push().call();
			final SourceExtractor se = new SourceExtractor(git.getRepository());
			Pair<Pair<AnyObjectId, Set<Ref>>, List<Pair<FileObjectIdStore, InputStreamHolder>>> sourceBranchExtractor = se
					.sourceBranchExtractor(REFS_HEADS_MASTER);
			assertEquals(1, sourceBranchExtractor.getRight().size());
			Pair<FileObjectIdStore, InputStreamHolder> p = sourceBranchExtractor.getRight().get(0);
			assertNotNull(p.getRight());
			local.rm().addFilepattern(key).call();
			local.add().addFilepattern(key).call();
			local.commit().setMessage("Removed file").call();
			local.push().call();

			sourceBranchExtractor = se.sourceBranchExtractor(REFS_HEADS_MASTER);
			assertEquals(0, sourceBranchExtractor.getRight().size());
		}
	}

	@Test
	public void testReadASingleFileFromMaster() throws Exception {
		final String key = "file";
		File temporaryGitFolder = folder.newFolder();
		try (Git local = Git.cloneRepository().setURI(workingFolder.toURI().toString()).setDirectory(temporaryGitFolder)
				.call()) {
			final Path file = temporaryGitFolder.toPath().resolve(key);
			Files.write(file, getData().getBytes("UTF-8"), StandardOpenOption.CREATE_NEW,
					StandardOpenOption.TRUNCATE_EXISTING);
			local.add().addFilepattern(key).call();
			local.commit().setMessage("Commit " + file.toString()).call();
			local.push().call();
		}
		SourceExtractor se = new SourceExtractor(git.getRepository());
		SourceInfo branch = se.openBranch(Constants.R_HEADS + Constants.MASTER, key);
		try (final InputStream is = branch.getInputStream();) {
			assertNotNull(is);
			String parsed = new BufferedReader(new InputStreamReader(is)).lines().collect(Collectors.joining());
			assertEquals(getData(), parsed);
		}

	}

	@Test
	public void testExtractNullTag() throws Exception {
		ex.expect(NullPointerException.class);
		SourceExtractor se = new SourceExtractor(git.getRepository());
		se.openTag(null, "file");
	}

	@Test
	public void testExtractNoTag() throws Exception {
		ex.expect(RefNotFoundException.class);
		SourceExtractor se = new SourceExtractor(git.getRepository());
		se.openTag("tag", "file");
	}

	@Test
	public void testExtractTag() throws Exception {
		final String key = "file";
		File temporaryGitFolder = folder.newFolder();
		try (Git local = Git.cloneRepository().setURI(workingFolder.toURI().toString()).setDirectory(temporaryGitFolder)
				.call()) {
			final Path file = temporaryGitFolder.toPath().resolve(key);
			Files.write(file, getData().getBytes("UTF-8"), StandardOpenOption.CREATE_NEW,
					StandardOpenOption.TRUNCATE_EXISTING);
			local.add().addFilepattern(key).call();
			local.commit().setMessage("Commit " + file.toString()).call();
			local.push().call();
			local.tag().setName("tag").call();
			local.push().setPushTags().call();
		}
		SourceExtractor se = new SourceExtractor(git.getRepository());
		SourceInfo tag = se.openTag(Constants.R_TAGS + "tag", key);
		try (final InputStream is = tag.getInputStream();) {
			assertNotNull(is);
			String parsed = new BufferedReader(new InputStreamReader(is)).lines().collect(Collectors.joining());
			assertEquals(getData(), parsed);
		}
	}

	@Test
	public void testRefNotPresent() throws Exception {
		ex.expect(RefNotFoundException.class);
		final String key = "file";
		File temporaryGitFolder = folder.newFolder();
		try (Git local = Git.cloneRepository().setURI(workingFolder.toURI().toString()).setDirectory(temporaryGitFolder)
				.call()) {
			final Path file = temporaryGitFolder.toPath().resolve(key);
			Files.write(file, getData().getBytes("UTF-8"), StandardOpenOption.CREATE_NEW,
					StandardOpenOption.TRUNCATE_EXISTING);
			local.add().addFilepattern(key).call();
			local.commit().setMessage("Commit " + file.toString()).call();
			local.push().call();
		}
		SourceExtractor se = new SourceExtractor(git.getRepository());
		SourceInfo branch = se.openBranch(Constants.R_HEADS + "notexisting", key);
		try (final InputStream is = branch.getInputStream();) {
			assertNotNull(is);
			String parsed = new BufferedReader(new InputStreamReader(is)).lines().collect(Collectors.joining());
			assertEquals(getData(), parsed);
		}
	}

	@Test
	public void testOpeningRepositoryFails() throws Exception {
		IOException exception = new IOException("Error opening");
		final String key = "file";
		File temporaryGitFolder = folder.newFolder();
		try (Git local = Git.cloneRepository().setURI(workingFolder.toURI().toString()).setDirectory(temporaryGitFolder)
				.call()) {
			final Path file = temporaryGitFolder.toPath().resolve(key);
			Files.write(file, getData().getBytes("UTF-8"), StandardOpenOption.CREATE_NEW,
					StandardOpenOption.TRUNCATE_EXISTING);
			local.add().addFilepattern(key).call();
			local.commit().setMessage("Commit " + file.toString()).call();
			local.push().call();
			local.tag().setName("tag").call();
			local.push().setPushTags().call();

			Repository spy = Mockito.spy(local.getRepository());
			Mockito.doThrow(exception).when(spy).open(Mockito.any());
			SourceExtractor se = new SourceExtractor(spy);
			Pair<Pair<AnyObjectId, Set<Ref>>, List<Pair<FileObjectIdStore, InputStreamHolder>>> branch = se
					.sourceBranchExtractor(REFS_HEADS_MASTER);
			assertEquals(REFS_HEADS_MASTER, branch.getLeft().getRight().stream().findFirst().get().getName());
			assertSame(exception, branch.getRight().stream().findFirst().get().getRight().exception());
		}
	}

	@Test
	public void testSourceTestBranchExtractorNullBranch() throws RefNotFoundException, IOException {
		ex.expect(NullPointerException.class);
		Repository repository = Mockito.mock(Repository.class);
		SourceExtractor se = new SourceExtractor(repository);
		se.sourceTestBranchExtractor(null);
	}

	@Test
	public void testSourceTestBranchExtractorBranchNotFound() throws RefNotFoundException, IOException {
		ex.expect(RefNotFoundException.class);
		Repository repository = Mockito.mock(Repository.class);
		SourceExtractor se = new SourceExtractor(repository);
		se.sourceTestBranchExtractor("notfound");
	}

	@Test
	public void testSourceTestBranchExtractorWrongRefName()
			throws NoHeadException, NoMessageException, UnmergedPathsException, ConcurrentRefUpdateException,
			WrongRepositoryStateException, AbortedByHookException, GitAPIException, IOException {
		final String branch = JitStaticConstants.REF_JISTSTATIC + "something";
		final String key = "file";
		File temporaryGitFolder = folder.newFolder();
		try (Git local = Git.cloneRepository().setURI(workingFolder.toURI().toString()).setDirectory(temporaryGitFolder)
				.call()) {
			final Path file = temporaryGitFolder.toPath().resolve(key);
			Files.write(file, getData().getBytes("UTF-8"), StandardOpenOption.CREATE_NEW,
					StandardOpenOption.TRUNCATE_EXISTING);
			local.add().addFilepattern(key).call();
			local.commit().setMessage("Commit " + file.toString()).call();
			local.push().call();
			SourceExtractor se = new SourceExtractor(local.getRepository());
			assertFalse(se.sourceTestBranchExtractor(branch).isPresent());
		}
	}

	@Test
	public void testSourceBranchExtractorNullBranch() throws RefNotFoundException, IOException {
		ex.expect(NullPointerException.class);
		Repository repository = Mockito.mock(Repository.class);
		SourceExtractor se = new SourceExtractor(repository);
		se.sourceBranchExtractor(null);
	}

	@Test
	public void testSourceBranchExtractorBranchNotFound() throws RefNotFoundException, IOException {
		ex.expect(RefNotFoundException.class);
		Repository repository = Mockito.mock(Repository.class);
		SourceExtractor se = new SourceExtractor(repository);
		se.sourceBranchExtractor("notfound");
	}

	@Test
	public void testExtractNullBranch() throws Exception {
		ex.expect(NullPointerException.class);
		SourceExtractor se = new SourceExtractor(git.getRepository());
		se.openBranch(null, null);
	}

	@Test
	public void testExtractNoBranch() throws Exception {
		ex.expect(RefNotFoundException.class);
		SourceExtractor se = new SourceExtractor(git.getRepository());
		se.openBranch("branch", null);
	}

	private final Function<Exception, String> pe = (e) -> {
		return e.getMessage() + Stream.of(e.getStackTrace()).map(st -> st.toString())
				.collect(Collectors.joining(System.lineSeparator()));
	};

	private final Function<InputStreamHolder, String> c = (i) -> {
		try (InputStream is = i.inputStream()) {
			return new BufferedReader(new InputStreamReader(is)).lines().collect(Collectors.joining());
		} catch (IOException e1) {
			return pe.apply(e1);
		}
	};

	private void print(
			final Map<Pair<AnyObjectId, Set<Ref>>, List<Pair<FileObjectIdStore, InputStreamHolder>>> allExtracted) {
		allExtracted.entrySet().stream().forEach(e -> {
			System.out.println("######");
			Pair<AnyObjectId, Set<Ref>> k = e.getKey();
			System.out.println("AnyObjectId=" + k.getLeft());
			System.out.println("Refs=" + k.getRight());
			System.out.println("List size=" + e.getValue().size());
			e.getValue().stream().forEach(l -> {
				System.out.println("ObjectId=" + l.getLeft().getObjectId() + "  name=" + l.getLeft().getFileName());
				System.out.println("Data="
						+ (l.getRight().isPresent() ? c.apply(l.getRight()) : pe.apply(l.getRight().exception())));
			});
		});
	}

	private String getData() {
		return getData(1);
	}

	private String getData(int i) {
		return "{\"data\":{\"key" + i
				+ "\":{\"data\":\"value1\",\"users\":[{\"password\":\"1234\",\"user\":\"user1\"}]},\"key3\":{\"data\":\"value3\",\"users\":[{\"password\":\"1234\",\"user\":\"user1\"}]}},\"users\":[{\"password\":\"1234\",\"user\":\"user1\"}]}";
	}

}
