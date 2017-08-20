package jitstatic.hosted;

/*-
 * #%L
 * jitstatic
 * %%
 * Copyright (C) 2017 HHegardt
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
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import javax.servlet.ServletRequestListener;
import javax.servlet.http.HttpServletRequest;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.ServiceMayNotContinueException;
import org.eclipse.jgit.transport.resolver.RepositoryResolver;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;
import org.eclipse.jgit.transport.resolver.ServiceNotEnabledException;

import jitstatic.source.Source;
import jitstatic.source.SourceEventListener;

class HostedGitRepositoryManager implements Source {

	static final String BARE = "bare";
	private final Repository bareRepository;
	private final String endPointName;
	private final GitRecievePackListener listener;

	public HostedGitRepositoryManager(final Path workingDirectory, final String endPointName) {
		Objects.requireNonNull(workingDirectory);
		Objects.requireNonNull(endPointName);
		if (endPointName.isEmpty()) {
			throw new IllegalArgumentException(String.format("Parameter endPointName cannot be empty"));
		}
		this.endPointName = endPointName;
		if (!Files.isDirectory(workingDirectory))
			throw new IllegalArgumentException(String.format("Path %s is not a directory", workingDirectory));
		if (!Files.isWritable(workingDirectory))
			throw new IllegalArgumentException(String.format("Path %s is not writeable", workingDirectory));

		try {
			this.bareRepository = setUpBareRepository(workingDirectory);
		} catch (final IllegalStateException | GitAPIException | IOException e) {
			throw new RuntimeException(e);
		}
		this.listener = new GitRecievePackListener();
	}

	private Repository setUpBareRepository(final Path repo) throws IOException, IllegalStateException, GitAPIException {
		final Path bareRepo = repo.resolve(BARE);
		if (!Files.exists(bareRepo)) {
			Files.createDirectories(bareRepo); // TODO Fix attributes
			return Git.init().setDirectory(bareRepo.toFile()).setBare(true).call().getRepository();
		} else {
			return new FileRepositoryBuilder().setGitDir(bareRepo.toFile()).readEnvironment()
					.findGitDir(bareRepo.toFile()).setMustExist(true).build();
		}
	}

	@Override
	public void close() {
		Utils.closeSilently(this.bareRepository);
	}

	public URI repositoryURI() {
		return this.bareRepository.getDirectory().toURI();
	}

	public ServletRequestListener getRequestListener() {
		return this.listener;
	}

	public RepositoryResolver<HttpServletRequest> getRepositoryResolver() {
		return new RepositoryResolver<HttpServletRequest>() {
			@Override
			public Repository open(final HttpServletRequest req, final String name) throws RepositoryNotFoundException,
					ServiceNotAuthorizedException, ServiceNotEnabledException, ServiceMayNotContinueException {
				if (!endPointName.equals(name)) {
					throw new RepositoryNotFoundException(name);
				}
				bareRepository.incrementOpen();
				return bareRepository;
			}
		};
	}

	@Override
	public void addListener(SourceEventListener listener) {
		this.listener.addListener(listener);
	}

	@Override
	public Contact getContact() {
		return new Contact() {

			@Override
			public URI repositoryURI() {
				return bareRepository.getDirectory().toURI();
			}

			@Override
			public String getUserName() {
				return null;
			}

			@Override
			public String getPassword() {
				return null;
			}
		};
	}

	@Override
	public void start() {
		// noop		
	}
}
