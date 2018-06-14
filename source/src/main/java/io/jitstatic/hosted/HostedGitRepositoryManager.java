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
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

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
import org.eclipse.jgit.transport.resolver.UploadPackFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.jitstatic.CorruptedSourceException;
import io.jitstatic.FileObjectIdStore;
import io.jitstatic.JitStaticConstants;
import io.jitstatic.SourceChecker;
import io.jitstatic.SourceExtractor;
import io.jitstatic.SourceUpdater;
import io.jitstatic.StorageData;
import io.jitstatic.source.Source;
import io.jitstatic.source.SourceEventListener;
import io.jitstatic.source.SourceInfo;
import io.jitstatic.utils.Pair;
import io.jitstatic.utils.ShouldNeverHappenException;
import io.jitstatic.utils.VersionIsNotSame;
import io.jitstatic.utils.WrappingAPIException;

public class HostedGitRepositoryManager implements Source {
    private static final Logger LOG = LoggerFactory.getLogger(HostedGitRepositoryManager.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Repository bareRepository;
    private final String endPointName;
    private final SourceExtractor extractor;
    private final String defaultRef;
    private final JitStaticReceivePackFactory receivePackFactory;
    private final ErrorReporter errorReporter;
    private final RepositoryBus repositoryBus;
    private final SourceUpdater updater;
    private final JitStaticUploadPackFactory uploadPackFactory;

    HostedGitRepositoryManager(final Path workingDirectory, final String endPointName, final String defaultRef, final Executor repoExecutor,
            final ErrorReporter errorReporter) throws CorruptedSourceException, IOException {
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
        this.receivePackFactory = new JitStaticReceivePackFactory(errorReporter,
                defaultRef, repositoryBus);
        this.uploadPackFactory = new JitStaticUploadPackFactory(errorReporter);
        this.defaultRef = defaultRef;
        this.errorReporter = errorReporter;
    }

    private HostedGitRepositoryManager(final Path workingDirectory, final String endPointName, final String defaultRef,
            final ErrorReporter reporter) throws CorruptedSourceException, IOException {
        this(workingDirectory, endPointName, defaultRef, null, reporter);
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
    public SourceInfo getSourceInfo(String key, String ref) throws RefNotFoundException {
        Objects.requireNonNull(key);
        ref = checkRef(ref);
        if (key.isEmpty()) {
            throw new IllegalArgumentException("Key is empty");
        }
        if (key.equals("/")) {
            key = "";
        } else if (key.startsWith("/")) {
            throw new IllegalArgumentException("Key starts with a '/' " + key);
        }

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
    public String modify(final String key, String ref, final byte[] data, final String version, final String message, final String userInfo,
            final String userMail) {
        Objects.requireNonNull(data);
        Objects.requireNonNull(version);
        Objects.requireNonNull(message);
        Objects.requireNonNull(userInfo);
        Objects.requireNonNull(userMail);
        Objects.requireNonNull(key);
        final String finalRef = checkRef(ref);
        checkIfTag(finalRef);
        try {
            final Ref actualRef = findRef(finalRef);
            if (!checkVersion(version, key, finalRef)) {
                throw new WrappingAPIException(new VersionIsNotSame());
            }
            return updater.updateKey(key, actualRef, data, message, userInfo, userMail);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Ref findRef(final String finalRef) throws IOException {
        final Ref actualRef = bareRepository.findRef(finalRef);
        if (actualRef == null) {
            throw new WrappingAPIException(new RefNotFoundException(finalRef));
        }
        return actualRef;
    }

    private String checkRef(String ref) {
        if (ref == null) {
            ref = defaultRef;
        }
        return ref;
    }

    private boolean checkVersion(final String version, final String key, final String ref) {
        try {
            final SourceInfo sourceInfo = getSourceInfo(key, ref);
            if (sourceInfo == null) {
                return false;
            }
            return version.equals(sourceInfo.getSourceVersion());
        } catch (final RefNotFoundException e) {
            throw new WrappingAPIException(e);
        }
    }

    private boolean checkMetaDataVersion(final String version, final String key, final String ref) {
        try {
            final SourceInfo sourceInfo = getSourceInfo(key, ref);
            if (sourceInfo == null) {
                return false;
            }
            return version.equals(sourceInfo.getMetaDataVersion());
        } catch (final RefNotFoundException e) {
            throw new WrappingAPIException(e);
        }
    }

    @Override
    public Pair<String, String> addKey(final String key, String ref, final byte[] data, final StorageData metaData, final String message,
            final String userInfo, final String userMail) {
        Objects.requireNonNull(data);
        Objects.requireNonNull(message);
        Objects.requireNonNull(userInfo);
        Objects.requireNonNull(userMail);
        Objects.requireNonNull(key);
        Objects.requireNonNull(metaData);
        final String finalRef = checkRef(ref);
        checkIfTag(finalRef);
        final CompletableFuture<byte[]> metaDataConverter = convertMetaData(metaData);

        try {
            final Ref actualRef = findRef(finalRef);
            checkIfKeyAlreadyExist(key, finalRef);
            return updater.addKey(Pair.of(Pair.of(key, data), Pair.of(key + JitStaticConstants.METADATA, unwrap(metaDataConverter))),
                    actualRef, message, userInfo, userMail);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void checkIfKeyAlreadyExist(final String key, final String finalRef) {
        try {
            final SourceInfo sourceInfo = getSourceInfo(key, finalRef);
            if (sourceInfo != null && !sourceInfo.isMetaDataSource()) {
                throw new WrappingAPIException(new KeyAlreadyExist(key, finalRef));
            }
        } catch (final RefNotFoundException e) {
            throw new WrappingAPIException(e);
        }
    }

    @Override
    public String modify(final StorageData metaData, final String metaDataVersion, final String message, final String userInfo,
            final String userMail, final String key, String ref) {
        Objects.requireNonNull(message);
        Objects.requireNonNull(userInfo);
        Objects.requireNonNull(userMail);
        Objects.requireNonNull(key);
        final String finalRef = checkRef(ref);
        checkIfTag(finalRef);
        final CompletableFuture<byte[]> metaDataConverter = convertMetaData(Objects.requireNonNull(metaData));
        try {
            final Ref actualRef = findRef(finalRef);
            if (!checkMetaDataVersion(metaDataVersion, key, finalRef)) {
                throw new WrappingAPIException(new VersionIsNotSame());
            }
            return updater.updateMetaData(key + JitStaticConstants.METADATA, actualRef, unwrap(metaDataConverter), message, userInfo,
                    userMail);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void checkIfTag(final String finalRef) {
        if (finalRef.startsWith(Constants.R_TAGS)) {
            throw new UnsupportedOperationException("Tags cannot be modified");
        }
    }
    
    private static <T> T unwrap(final CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (final CompletionException ce) {
            final Throwable cause = ce.getCause();
            if (cause instanceof WrappingAPIException) {
                throw (WrappingAPIException) cause;
            }
            throw ce;
        }
    }

    private CompletableFuture<byte[]> convertMetaData(final StorageData metaData) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return MAPPER.writeValueAsBytes(metaData);
            } catch (final JsonProcessingException e1) {
                throw new ShouldNeverHappenException("", e1);
            }
        });
    }

    public UploadPackFactory<HttpServletRequest> getUploadPackFactory() {
        return uploadPackFactory;
    }

    @Override
    public void delete(final String key, String ref, final String user, final String message, final String userMail) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(user);
        Objects.requireNonNull(message);
        Objects.requireNonNull(userMail);
        final String finalRef = checkRef(ref);
        checkIfTag(finalRef);

        try {
            final Ref actualRef = findRef(finalRef);
            final SourceInfo sourceInfo = getSourceInfo(key, finalRef);
            if (sourceInfo == null) {
                return;
            }
            updater.deleteKey(key, actualRef, user, message, userMail, sourceInfo.hasKeyMetaData());
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        } catch (final RefNotFoundException e) {
            throw new ShouldNeverHappenException("delete key:" + key + " ref:" + finalRef, e);
        }
    }

    @Override
    public String getRefId(final String ref) throws IOException {
        final Ref actualRef = extractor.getRef(ref);
        if (actualRef == null) {
            return null;
        }
        return actualRef.getObjectId().name();
    }
}
