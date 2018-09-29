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

import static io.jitstatic.JitStaticConstants.GIT_REALM;
import static io.jitstatic.JitStaticConstants.JITSTATIC_KEYADMIN_REALM;
import static io.jitstatic.JitStaticConstants.JITSTATIC_KEYUSER_REALM;

import static io.jitstatic.JitStaticConstants.USERS;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spencerwi.either.Either;

import io.jitstatic.JitStaticConstants;
import io.jitstatic.Role;
import io.jitstatic.auth.UserData;
import io.jitstatic.check.FileObjectIdStore;
import io.jitstatic.check.RepositoryDataError;
import io.jitstatic.utils.Pair;

public class UserExtractor {
    private static final Set<Role> ROLES = JitStaticConstants.ROLES.stream().map(Role::new).collect(Collectors.toSet());
    private static final Set<String> REALMS = Set.of(GIT_REALM, JITSTATIC_KEYADMIN_REALM, JITSTATIC_KEYUSER_REALM);
    private static final Logger LOG = LoggerFactory.getLogger(UserExtractor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Repository repository;

    UserExtractor(final Repository repository) {
        this.repository = repository;
    }

    public Pair<String, UserData> extractUserFromRef(final String userKey, final String ref)
            throws MissingObjectException, IncorrectObjectTypeException, CorruptObjectException, IOException, RefNotFoundException {
        if (!Objects.requireNonNull(userKey).startsWith(JitStaticConstants.USERS)) {
            throw new IllegalArgumentException("Trying to get users through illegal key " + userKey + " in ref " + ref);
        }
        final Ref findBranch = findBranch(Objects.requireNonNull(ref));
        final AnyObjectId reference = findBranch.getObjectId();
        try (final RevWalk rev = new RevWalk(repository)) {
            final RevCommit parsedCommit = rev.parseCommit(reference);
            final RevTree currentTree = rev.parseTree(parsedCommit.getTree());
            try (final TreeWalk treeWalker = new TreeWalk(repository)) {
                treeWalker.addTree(currentTree);
                treeWalker.setFilter(PathFilterGroup.createFromStrings(userKey));
                treeWalker.setRecursive(true);
                while (treeWalker.next()) {
                    final FileMode mode = treeWalker.getFileMode();
                    if (mode == FileMode.REGULAR_FILE || mode == FileMode.EXECUTABLE_FILE) {
                        final ObjectId objectId = treeWalker.getObjectId(0);
                        try (InputStream is = repository.open(objectId).openStream()) {
                            return Pair.of(objectId.getName(), MAPPER.readValue(is, UserData.class));
                        }
                    }
                }
            } finally {
                rev.dispose();
            }
        }
        return Pair.ofNothing();
    }

    public List<Pair<Set<Ref>, List<Pair<FileObjectIdStore, Exception>>>> checkOnTestBranch(final String ref, final String alias)
            throws RefNotFoundException, IOException {
        Ref branch = findBranch(ref);
        if (alias != null) {
            branch = new WrappingRef(branch, alias);
        }
        return validate(Map.of(branch.getObjectId(), Set.of(branch))).stream()
                .map(p -> Pair.of(p.getLeft(), p.getRight().stream().map(Pair::getRight).flatMap(List::stream).collect(Collectors.toList())))
                .collect(Collectors.toList());
    }

    public List<Pair<Set<Ref>, List<Pair<String, List<Pair<FileObjectIdStore, Exception>>>>>> validate(final Map<AnyObjectId, Set<Ref>> mappedRefs) {
        return mappedRefs.entrySet().stream().map(e -> {
            final Set<Ref> refs = e.getValue().stream().filter(ref -> !ref.isSymbolic()).collect(Collectors.toSet());
            return Pair.of(e.getKey(), refs);
        }).map(this::extracted).map(this::splitIntoRealms)
                .map(list -> Pair.of(list.getLeft(), list.getRight().entrySet().stream().map(e -> Pair.of(e.getKey(), e.getValue().parallelStream().map(p -> {
                    InputStreamHolder inputStream = p.getRight();
                    if (inputStream.isPresent()) {
                        try (InputStream is = inputStream.inputStream()) {
                            return Pair.of(p.getLeft(), Either.<UserData, Exception>left(MAPPER.readValue(is, UserData.class)));
                        } catch (IOException e1) {
                            return Pair.of(p.getLeft(), Either.<UserData, Exception>right(e1));
                        }
                    }
                    return Pair.of(p.getLeft(), Either.<UserData, Exception>right(inputStream.exception()));
                }).collect(Collectors.toList()))).map(this::getUserDataIOErrors).filter(this::hasErrors).collect(Collectors.toList())))
                .filter(p -> !p.getRight().isEmpty()).collect(Collectors.toList());
    }

    public List<Pair<Set<Ref>, List<Pair<String, List<Pair<FileObjectIdStore, Exception>>>>>> validateAll() {
        return validate(repository.getAllRefsByPeeledObjectId());
    }

    private Pair<Set<Ref>, Map<String, List<Pair<FileObjectIdStore, InputStreamHolder>>>> splitIntoRealms(
            final Pair<Set<Ref>, Pair<List<Pair<FileObjectIdStore, InputStreamHolder>>, RepositoryDataError>> iterationResult) {

        final Map<String, List<Pair<FileObjectIdStore, InputStreamHolder>>> realms = new HashMap<>();
        final Pair<List<Pair<FileObjectIdStore, InputStreamHolder>>, RepositoryDataError> data = iterationResult.getRight();
        final RepositoryDataError rde = data.getRight();

        if (rde != null) {
            List<Pair<FileObjectIdStore, InputStreamHolder>> list = new ArrayList<>();
            list.add(Pair.of(rde.getFileObjectIdStore(), rde.getInputStreamHolder()));
            realms.put(USERS, list);
        }
        for (Pair<FileObjectIdStore, InputStreamHolder> p : data.getLeft()) {
            FileObjectIdStore fileObjectIdStore = p.getLeft();
            final String pathName = fileObjectIdStore.getFileName().substring(USERS.length());
            int firstSlash = pathName.indexOf('/');
            String realm = pathName.substring(0, firstSlash);
            realms.compute(realm, (k, v) -> {
                Pair<FileObjectIdStore, InputStreamHolder> userData = Pair.of(fileObjectIdStore, p.getRight());
                if (v == null) {
                    List<Pair<FileObjectIdStore, InputStreamHolder>> list = new ArrayList<>();
                    list.add(userData);
                    return list;
                }
                v.add(userData);
                return v;
            });
        }
        final Set<Ref> refs = iterationResult.getLeft();
        realms.keySet().forEach(realm -> {
            if (REALMS.contains(realm)) {
                LOG.info("Found realm {} in refs {}", realm, refs);
            } else {
                LOG.warn("Found unmapped realm {} in refs {}", realm, refs);
            }
        });
        return Pair.of(iterationResult.getLeft(), realms);
    }

    private boolean hasErrors(final Pair<String, List<Pair<FileObjectIdStore, Exception>>> p) {
        return !p.getRight().isEmpty();
    }

    private Pair<String, List<Pair<FileObjectIdStore, Exception>>> getUserDataIOErrors(
            final Pair<String, List<Pair<FileObjectIdStore, Either<UserData, Exception>>>> p) {
        final String realm = p.getLeft();
        final Stream<Pair<FileObjectIdStore, Either<UserData, Exception>>> stream;
        if (realm.equals(JitStaticConstants.GIT_REALM)) {
            stream = p.getRight().stream().map(fp -> {
                final Either<UserData, Exception> value = fp.getValue();
                if (value.isLeft()) {
                    final Set<Role> userRoles = value.getLeft().getRoles();
                    if (!ROLES.containsAll(userRoles)) {
                        userRoles.retainAll(ROLES);
                        return Pair.of(fp.getLeft(), Either.right(new UnknownRolesException(fp.getLeft().getFileName(), userRoles)));
                    }
                }
                return fp;
            });
        } else {
            stream = p.getRight().stream();
        }
        return Pair.of(p.getLeft(),
                stream.filter(l -> l.getRight().isRight()).map(err -> Pair.of(err.getLeft(), err.getRight().getRight())).collect(Collectors.toList()));
    }

    private Pair<Set<Ref>, Pair<List<Pair<FileObjectIdStore, InputStreamHolder>>, RepositoryDataError>> extracted(Pair<AnyObjectId, Set<Ref>> p) {
        return Pair.of(p.getRight(), extractAll(p.getLeft()));
    }

    public Pair<List<Pair<FileObjectIdStore, InputStreamHolder>>, RepositoryDataError> extractAll(final AnyObjectId tip) {
        List<Pair<FileObjectIdStore, InputStreamHolder>> files = new ArrayList<>();
        RepositoryDataError error = null;
        try (final RevWalk rev = new RevWalk(repository)) {
            final RevCommit parsedCommit = rev.parseCommit(tip);
            final RevTree currentTree = rev.parseTree(parsedCommit.getTree());

            try (final TreeWalk treeWalker = new TreeWalk(repository)) {
                treeWalker.addTree(currentTree);
                treeWalker.setRecursive(true);
                treeWalker.setFilter(PathFilter.create(USERS));
                while (treeWalker.next()) {
                    final FileMode mode = treeWalker.getFileMode();
                    if (mode == FileMode.REGULAR_FILE || mode == FileMode.EXECUTABLE_FILE) {
                        final ObjectId objectId = treeWalker.getObjectId(0);
                        final String path = new String(treeWalker.getRawPath(), UTF_8);
                        final InputStreamHolder inputStreamHolder = getInputStreamFor(objectId);
                        files.add(Pair.of(new FileObjectIdStore(path, objectId), inputStreamHolder));
                    }
                }
            }
        } catch (final IOException e) {
            error = new RepositoryDataError(new FileObjectIdStore(USERS, tip.toObjectId()), new InputStreamHolder(e));
        }
        return Pair.of(files, error);
    }

    private InputStreamHolder getInputStreamFor(final ObjectId objectId) {
        try {
            return new InputStreamHolder(repository.open(objectId));
        } catch (final IOException e) {
            return new InputStreamHolder(e);
        }
    }

    private Ref findBranch(final String refName) throws IOException, RefNotFoundException {
        final Ref branchRef = repository.findRef(refName);
        if (branchRef == null) {
            throw new RefNotFoundException(refName);
        }
        return branchRef;
    }

    private static class WrappingRef implements Ref {

        private final Ref wrapped;
        private final String alias;

        public WrappingRef(final Ref ref, final String alias) {
            this.wrapped = Objects.requireNonNull(ref);
            this.alias = Objects.requireNonNull(alias);
        }

        @Override
        public String getName() {
            return wrapped.getName();
        }

        @Override
        public boolean isSymbolic() {
            return wrapped.isSymbolic();
        }

        @Override
        public Ref getLeaf() {
            return wrapped.getLeaf();
        }

        @Override
        public Ref getTarget() {
            return wrapped.getTarget();
        }

        @Override
        public ObjectId getObjectId() {
            return wrapped.getObjectId();
        }

        @Override
        public ObjectId getPeeledObjectId() {
            return wrapped.getPeeledObjectId();
        }

        @Override
        public boolean isPeeled() {
            return wrapped.isPeeled();
        }

        @Override
        public Storage getStorage() {
            return wrapped.getStorage();
        }

        @Override
        public String toString() {
            return alias + "=" + wrapped.getObjectId().name();
        }

    }
}
