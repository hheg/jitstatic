package io.jitstatic.hosted;

/*-
 * #%L
 * jitstatic
 * %%
 * Copyright (C) 2017 - 2018 H.Hegardt
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

import static io.jitstatic.JitStaticConstants.METADATA;
import static org.eclipse.jgit.lib.Constants.R_HEADS;
import static org.eclipse.jgit.lib.Constants.R_TAGS;

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
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletRequest;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.resolver.ReceivePackFactory;
import org.eclipse.jgit.transport.resolver.RepositoryResolver;
import org.eclipse.jgit.transport.resolver.UploadPackFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.jitstatic.CommitMetaData;
import io.jitstatic.MetaData;
import io.jitstatic.RepositoryUpdater;
import io.jitstatic.SourceUpdater;
import io.jitstatic.auth.UserData;
import io.jitstatic.check.CorruptedSourceException;
import io.jitstatic.check.FileObjectIdStore;
import io.jitstatic.check.SourceChecker;
import io.jitstatic.check.SourceExtractor;
import io.jitstatic.source.Source;
import io.jitstatic.source.SourceEventListener;
import io.jitstatic.source.SourceInfo;
import io.jitstatic.utils.Functions.ThrowingSupplier;
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
    private final UserExtractor userExtractor;
    private final UserUpdater userUpdater;

    HostedGitRepositoryManager(final Path workingDirectory, final String endPointName, final String defaultRef, final ErrorReporter errorReporter)
            throws CorruptedSourceException, IOException {
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
            throw new IllegalArgumentException("Parameter endPointName cannot be empty");
        }

        try {
            this.bareRepository = setUpBareRepository(workingDirectory);
        } catch (final IllegalStateException | GitAPIException | IOException e) {
            throw new RuntimeException(e);
        }

        this.userExtractor = new UserExtractor(bareRepository);
        final List<Pair<Set<Ref>, List<Pair<FileObjectIdStore, Exception>>>> errors = checkStoreForErrors(this.bareRepository);
        errors.addAll(checkForUserErrors(this.userExtractor));

        if (!errors.isEmpty()) {
            throw new CorruptedSourceException(errors);
        }
        checkIfDefaultBranchExist(defaultRef);
        final RepositoryUpdater repositoryUpdater = new RepositoryUpdater(this.bareRepository);
        this.extractor = new SourceExtractor(this.bareRepository);
        this.updater = new SourceUpdater(repositoryUpdater);
        this.repositoryBus = new RepositoryBus(errorReporter);
        this.receivePackFactory = new JitStaticReceivePackFactory(errorReporter, defaultRef, repositoryBus, userExtractor);
        this.uploadPackFactory = new JitStaticUploadPackFactory(errorReporter);
        this.defaultRef = defaultRef;
        this.errorReporter = errorReporter;
        this.userUpdater = new UserUpdater(repositoryUpdater);
    }

    public HostedGitRepositoryManager(final Path workingDirectory, final String endPointName, final String defaultRef)
            throws CorruptedSourceException, IOException {
        this(workingDirectory, endPointName, defaultRef, new ErrorReporter());
    }

    private static List<Pair<Set<Ref>, List<Pair<FileObjectIdStore, Exception>>>> checkForUserErrors(UserExtractor userExtractor) {
        return userExtractor.validateAll().stream()
                .map(p -> Pair.of(p.getLeft(), p.getRight().stream()
                        .map(Pair::getRight)
                        .flatMap(List::stream)
                        .collect(Collectors.toList())))
                .collect(Collectors.toList());
    }

    private void checkIfDefaultBranchExist(final String defaultRef) throws IOException {
        if (Objects.requireNonNull(defaultRef, "defaultBranch cannot be null").isEmpty()) {
            throw new IllegalArgumentException("defaultBranch cannot be empty");
        }
        new SourceChecker(bareRepository).checkIfDefaultBranchExists(defaultRef);
    }

    private static List<Pair<Set<Ref>, List<Pair<FileObjectIdStore, Exception>>>> checkStoreForErrors(final Repository bareRepository) {
        return new SourceChecker(bareRepository).check();
    }

    private static Repository setUpBareRepository(final Path repositoryBase) throws IOException, GitAPIException {
        LOG.info("Mounting repository on {}", repositoryBase);
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
            return null;
        }
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
        ref = checkRef(ref);
        key = checkKeyFormat(Objects.requireNonNull(key));

        try {
            if (ref.startsWith(R_HEADS)) {
                return extractor.openBranch(ref, key);
            } else if (ref.startsWith(R_TAGS)) {
                return extractor.openTag(ref, key);
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        throw new RefNotFoundException(ref);
    }

    private String checkKeyFormat(String key) {
        if (key.isEmpty()) {
            throw new IllegalArgumentException("Key is empty");
        }
        if (key.equals("/")) {
            key = "";
        } else if (key.startsWith("/")) {
            throw new IllegalArgumentException("Key starts with a '/' " + key);
        }
        return key;
    }

    @Override
    public Pair<String, ThrowingSupplier<ObjectLoader, IOException>> modifyKey(final String key, String ref, final byte[] data, final String version, final CommitMetaData commitMetaData) {
        Objects.requireNonNull(data);
        Objects.requireNonNull(version);
        Objects.requireNonNull(key);
        final String finalRef = checkRef(ref);
        checkIfTag(finalRef);
        try {
            final Ref actualRef = findRef(finalRef);
            if (!checkVersion(version, key, finalRef)) {
                throw new WrappingAPIException(new VersionIsNotSame());
            }
            return updater.updateKey(key, actualRef, data, commitMetaData);
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
    public Pair<Pair<ThrowingSupplier<ObjectLoader, IOException>, String>, String> addKey(final String key, String ref, final byte[] data, final MetaData metaData, final CommitMetaData commitMetaData) {
        Objects.requireNonNull(data);
        Objects.requireNonNull(key);
        Objects.requireNonNull(metaData);
        Objects.requireNonNull(commitMetaData);
        final String finalRef = checkRef(ref);
        checkIfTag(finalRef);
        final CompletableFuture<byte[]> metaDataConverter = convertMetaData(metaData);

        try {
            final Ref actualRef = findRef(finalRef);
            checkIfKeyAlreadyExist(key, finalRef);
            return updater.addKey(Pair.of(Pair.of(key, data), Pair.of(key + METADATA, unwrap(metaDataConverter))), actualRef, commitMetaData);
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
    public String modifyMetadata(final MetaData metaData, final String metaDataVersion, final String key, final String ref,
            final CommitMetaData commitMetaData) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(commitMetaData);
        Objects.requireNonNull(metaData);
        Objects.requireNonNull(metaDataVersion);
        final String finalRef = checkRef(ref);
        checkIfTag(finalRef);
        final CompletableFuture<byte[]> metaDataConverter = convertMetaData(Objects.requireNonNull(metaData));
        try {
            final Ref actualRef = findRef(finalRef);
            if (!checkMetaDataVersion(metaDataVersion, key, finalRef)) {
                throw new WrappingAPIException(new VersionIsNotSame());
            }
            return updater.updateMetaData(key + METADATA, actualRef, unwrap(metaDataConverter), commitMetaData);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void checkIfTag(final String finalRef) {
        if (finalRef.startsWith(R_TAGS)) {
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

    private CompletableFuture<byte[]> convertMetaData(final MetaData metaData) {
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
    public void deleteKey(final String key, final String ref, CommitMetaData commitMetaData) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(commitMetaData);
        final String finalRef = checkRef(ref);
        checkIfTag(finalRef);

        try {
            final Ref actualRef = findRef(finalRef);
            final SourceInfo sourceInfo = getSourceInfo(key, finalRef);
            if (sourceInfo == null) {
                return;
            }
            updater.deleteKey(key, actualRef, commitMetaData, sourceInfo.hasKeyMetaData());
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        } catch (final RefNotFoundException e) {
            throw new ShouldNeverHappenException("delete key:" + key + " ref:" + finalRef, e);
        }
    }

    @Override
    public void addRefHolderFactory(final Function<String, RefHolderLock> factory) {
        this.repositoryBus.setRefHolderFactory(factory);
    }

    @Override
    public void createRef(final String ref) throws IOException {
        checkIfTag(Objects.requireNonNull(ref));
        updater.createRef(defaultRef, ref);
    }

    @Override
    public void deleteRef(final String ref) throws IOException {
        checkIfTag(ref);
        if (Objects.requireNonNull(ref).equals(defaultRef)) {
            throw new IllegalArgumentException("Cannot delete default ref " + defaultRef);
        }
        updater.deleteRef(ref);
    }

    @Override
    public List<String> getList(final String key, String ref, boolean recursive) throws RefNotFoundException, IOException {
        Objects.requireNonNull(key);
        ref = checkRef(ref);
        if (!key.endsWith("/")) {
            throw new IllegalArgumentException(String.format("%s doesn't end with /", key));
        }
        return extractor.getListForKey(key, ref, recursive).stream().filter(k -> !k.endsWith(METADATA)).collect(Collectors.toList());
    }

    @Override
    public Pair<String, UserData> getUser(final String userKey, final String ref) throws IOException, RefNotFoundException {
        return userExtractor.extractUserFromRef(userKey, checkRef(ref));
    }

    @Override
    public String updateUser(final String key, String ref, final String username, final UserData data) throws IOException {
        return userUpdater.updateUser(key, findRef(ref), data, new CommitMetaData(username, "none@jitstatic", "update user " + key));
    }

    @Override
    public String addUser(final String key, final String ref, final String username, final UserData data) throws IOException {
        return userUpdater.addUser(key, findRef(ref), data, new CommitMetaData(username, "none@jitstatic", "add user " + key));
    }

    @Override
    public void deleteUser(String key, String ref, String username) throws IOException {
        userUpdater.deleteUser(key,findRef(ref),new CommitMetaData(username, "none@jitstatic", "delete user "+key));
        
    }
}
