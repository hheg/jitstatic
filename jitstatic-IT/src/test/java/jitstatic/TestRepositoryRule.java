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

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.function.Supplier;

import org.eclipse.jgit.api.Git;
import org.junit.rules.ExternalResource;

public class TestRepositoryRule extends ExternalResource {

	private final Supplier<String> baseSupplier;
	private final String[] filesToCommit;
	private Path bareBase;

	public TestRepositoryRule(final Supplier<String> base, final String... filesToCommit) {
		this.baseSupplier = base;
		this.filesToCommit = filesToCommit;
	}

	@Override
	protected void before() throws Throwable {
		super.before();
		Path base = Paths.get(baseSupplier.get());
		bareBase = base.resolve("bare");
		Path workBase = base.resolve("work");

		try (Git bareGit = Git.init().setBare(true).setDirectory(bareBase.toFile()).call();
				Git git = Git.cloneRepository().setURI(bareBase.toUri().toString()).setDirectory(workBase.toFile())
						.call();) {
			for (String file : filesToCommit) {
				final Path filePath = workBase.resolve(file);
				Files.createDirectories(filePath.getParent());
				try (InputStream is = getClass().getResourceAsStream("/" + file)) {
					Files.copy(is, filePath, StandardCopyOption.REPLACE_EXISTING);
				}
				git.add().addFilepattern(file).call();
			}
			git.commit().setMessage("Initial commit").call();
			git.push().call();
		}
	}

	public final Supplier<String> getBase = () -> {
		if (bareBase == null) {
			throw new IllegalStateException("bareBase is not initialized yet");
		}
		return bareBase.toAbsolutePath().toString();
	};
}
