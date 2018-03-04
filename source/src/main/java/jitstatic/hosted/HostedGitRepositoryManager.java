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
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.servlet.http.HttpServletRequest;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.resolver.ReceivePackFactory;
import org.eclipse.jgit.transport.resolver.RepositoryResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import jitstatic.CorruptedSourceException;
import jitstatic.FileObjectIdStore;
import jitstatic.SourceChecker;
import jitstatic.SourceExtractor;
import jitstatic.SourceUpdater;
import jitstatic.StorageData;
import jitstatic.source.Source;
import jitstatic.source.SourceEventListener;
import jitstatic.source.SourceInfo;
import jitstatic.utils.ErrorConsumingThreadFactory;
import jitstatic.utils.Pair;
import jitstatic.utils.VersionIsNotSameException;
import jitstatic.utils.WrappingAPIException;

class HostedGitRepositoryManager implements Source {
    private static final Logger LOG = LoggerFactory.getLogger(HostedGitRepositoryManager.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Repository bareRepository;
    private final String endPointName;
    private final SourceExtractor extractor;
    private final String defaultRef;
    private final JitStaticReceivePackFactory receivePackFactory;
    private final ErrorReporter errorReporter;
    private final RepositoryBus repositoryBus;
    private final ExecutorService repoExecutor;
    private final SourceUpdater updater;

    HostedGitRepositoryManager(final Path workingDirectory, final String endPointName, final String defaultRef,
            final ExecutorService repoExecutor, final ErrorReporter errorReporter) throws CorruptedSourceException, IOException {
        if (!Files.isDirectory(Objects.requireNonNull(workingDirectory))) {
            if (Files.isRegularFile(workingDirectory)) {
                throw new IllegalArgumentException(String.format("Path %s is a file", workingDirectory));
            }
            Files.createDirectories(workingDirectory);
        }
        if (!Files.isWritable(workingDirectory)) {
            throw new IllegalArgumentException(String.format("Path %s is not writeable", workingDirectory));
        }

        this.endPointName = Objects.requireNonNull(endPointName).trim();

        if (this.endPointName.isEmpty()) {
            throw new IllegalArgumentException(String.format("Parameter endPointName cannot be empty"));
        }

        try {
            this.bareRepository = setUpBareRepository(workingDirectory);
        } catch (final IllegalStateException | GitAPIException | IOException e) {
            throw new RuntimeException(e);
        }

        final List<Pair<Set<Ref>, List<Pair<FileObjectIdStore, Exception>>>> errors = checkStoreForErrors(this.bareRepository);

        if (!errors.isEmpty()) {
            throw new CorruptedSourceException(errors);
        }
        checkIfDefaultBranchExist(defaultRef);
        this.extractor = new SourceExtractor(this.bareRepository);
        this.updater = new SourceUpdater(this.bareRepository);
        this.repositoryBus = new RepositoryBus(errorReporter);
        this.receivePackFactory = new JitStaticReceivePackFactory(Objects.requireNonNull(repoExecutor, "Repo executor cannot be null"),
                errorReporter, defaultRef, repositoryBus);
        this.defaultRef = defaultRef;
        this.errorReporter = errorReporter;
        this.repoExecutor = repoExecutor;
    }

    private HostedGitRepositoryManager(final Path workingDirectory, final String endPointName, final String defaultRef,
            final ErrorReporter reporter) throws CorruptedSourceException, IOException {
        this(workingDirectory, endPointName, defaultRef,
                Executors.newSingleThreadExecutor(new ErrorConsumingThreadFactory("repo", reporter::setFault)), reporter);
    }

    public HostedGitRepositoryManager(final Path workingDirectory, final String endPointName, final String defaultRef)
            throws CorruptedSourceException, IOException {
        this(workingDirectory, endPointName, defaultRef, new ErrorReporter());
    }

    private void checkIfDefaultBranchExist(final String defaultRef) throws IOException {
        Objects.requireNonNull(defaultRef, "defaultBranch cannot be null");
        if (defaultRef.isEmpty()) {
            throw new IllegalArgumentException("defaultBranch cannot be empty");
        }
        try (SourceChecker sc = new SourceChecker(bareRepository)) {
            sc.checkIfDefaultBranchExists(defaultRef);
        }
    }

    private static List<Pair<Set<Ref>, List<Pair<FileObjectIdStore, Exception>>>> checkStoreForErrors(final Repository bareRepository) {
        try (final SourceChecker sc = new SourceChecker(bareRepository)) {
            return sc.check();
        }
    }

    private static Repository setUpBareRepository(final Path repositoryBase) throws IOException, IllegalStateException, GitAPIException {
        LOG.info("Mounting repository on " + repositoryBase);
        Repository repo = getRepository(repositoryBase);
        if (repo == null) {
            Files.createDirectories(repositoryBase);
            repo = Git.init().setDirectory(repositoryBase.toFile()).setBare(true).call().getRepository();
        }
        repo.incrementOpen();
        return repo;
    }

    private static Repository getRepository(final Path baseDirectory) {
        try {
            return Git.open(baseDirectory.toFile()).getRepository();
        } catch (final IOException ignore) {
        }
        return null;
    }

    @Override
    public void close() {
        repoExecutor.shutdown();
        try {
            repoExecutor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException ignore) {
        }
        try {
            this.bareRepository.close();
        } catch (final Exception ignore) {
        }
    }

    public URI repositoryURI() {
        return this.bareRepository.getDirectory().toURI();
    }

    public RepositoryResolver<HttpServletRequest> getRepositoryResolver() {
        return (request, name) -> {
            if (!endPointName.equals(name)) {
                throw new RepositoryNotFoundException(name);
            }
            bareRepository.incrementOpen();
            return bareRepository;
        };
    }

    public ReceivePackFactory<HttpServletRequest> getReceivePackFactory() {
        return receivePackFactory;
    }

    @Override
    public String getDefaultRef() {
        return defaultRef;
    }

    @Override
    public void addListener(final SourceEventListener listener) {
        this.repositoryBus.addListener(listener);
    }

    @Override
    public void start() {
        // noop
    }

    @Override
    public void checkHealth() {
        final Exception fault = this.errorReporter.getFault();
        if (fault != null) {
            throw new RuntimeException(fault);
        }
    }

    @Override
    public SourceInfo getSourceInfo(final String key, String ref) throws RefNotFoundException {
        Objects.requireNonNull(key);
        ref = checkRef(ref);
        try {
            if (ref.startsWith(Constants.R_HEADS)) {
                return extractor.openBranch(ref, key);
            } else if (ref.startsWith(Constants.R_TAGS)) {
                return extractor.openTag(ref, key);
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        throw new RefNotFoundException(ref);
    }

    @Override
    public CompletableFuture<String> modify(final byte[] data, final String version, final String message, final String userInfo,
            String userMail, final String key, String ref) {
        Objects.requireNonNull(data);
        Objects.requireNonNull(version);
        Objects.requireNonNull(message);
        Objects.requireNonNull(userInfo);
        Objects.requireNonNull(userMail);
        Objects.requireNonNull(key);
        ref = checkRef(ref);
        if (ref.startsWith(Constants.R_TAGS)) {
            throw new UnsupportedOperationException("Tags cannot be modified");
        }
        final String finalRef = ref;
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!checkVersion(version, key, finalRef)) {
                    throw new WrappingAPIException(new VersionIsNotSameException());
                }
                final Ref actualRef = bareRepository.findRef(finalRef);
                if (actualRef == null) {
                    throw new WrappingAPIException(new RefNotFoundException(finalRef));
                }
                return this.updater.updateKey(key, actualRef, data, message, userInfo, userMail);
            } catch (final IOException | RefNotFoundException e) {
                throw new WrappingAPIException(e);
            }
        }, repoExecutor);
    }

    private String checkRef(String ref) {
        if (ref == null) {
            ref = defaultRef;
        }
        return ref;
    }

    private boolean checkVersion(final String version, final String key, final String ref) throws RefNotFoundException {
        final SourceInfo sourceInfo = getSourceInfo(key, ref);
        if (sourceInfo == null) {
            return false;
        }
        return version.equals(sourceInfo.getSourceVersion());
    }

    @Override
    public CompletableFuture<String> addKey(final String key, String ref, final byte[] data, final StorageData metaData, final String message, final String userInfo, final String userMail) {
        Objects.requireNonNull(data);
        Objects.requireNonNull(message);
        Objects.requireNonNull(userInfo);
        Objects.requireNonNull(userMail);
        Objects.requireNonNull(key);
        Objects.requireNonNull(metaData);
        final String finalRef = checkRef(ref);

        return CompletableFuture.supplyAsync(() -> {
            try {
                final Ref actualRef = bareRepository.findRef(finalRef);
                if (actualRef == null) {
                    throw new WrappingAPIException(new RefNotFoundException(finalRef));
                }
                return this.updater.addKey(Pair.of(Pair.of(key, data), Pair.of(key + ".metadata", MAPPER.writeValueAsBytes(metaData))), actualRef,
                        message, userInfo, userMail);
            } catch (final IOException e) {
                throw new WrappingAPIException(e);
            }
        }, repoExecutor);
    }
}
