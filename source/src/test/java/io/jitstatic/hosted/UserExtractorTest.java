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
import static io.jitstatic.JitStaticConstants.JITSTATIC_KEYUSER_REALM;
import static io.jitstatic.JitStaticConstants.JITSTATIC_KEYADMIN_REALM;
import static io.jitstatic.JitStaticConstants.USERS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Set;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.NoFilepatternException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.jitstatic.JitStaticConstants;
import io.jitstatic.Role;
import io.jitstatic.auth.UserData;
import io.jitstatic.check.FileObjectIdStore;
import io.jitstatic.test.TemporaryFolder;
import io.jitstatic.test.TemporaryFolderExtension;
import io.jitstatic.utils.Pair;

@ExtendWith(TemporaryFolderExtension.class)
public class UserExtractorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REF_HEAD_MASTER = Constants.R_HEADS + "master";
    private static final String store = "data";
    private TemporaryFolder tmpFolder;
    private Git bareGit;
    private Git workingGit;
    private File wBase;
    private Path users;

    @BeforeEach
    public void setup() throws Exception {
        final File base = createTempFiles();
        wBase = createTempFiles();
        bareGit = Git.init().setBare(true).setDirectory(base).call();
        workingGit = Git.cloneRepository().setURI(bareGit.getRepository().getDirectory().toURI().toString()).setDirectory(wBase).call();
        users = wBase.toPath().resolve(JitStaticConstants.USERS);
        assertTrue(users.toFile().mkdirs());
        RemoteTestUtils.copy("/test3.json", wBase.toPath().resolve(store));
        commit();
    }

    private void commit() throws NoFilepatternException, GitAPIException {
        workingGit.add().addFilepattern(".").call();
        workingGit.commit().setMessage("Initial commit").call();
        workingGit.push().call();
    }

    private File createTempFiles() throws IOException {
        return tmpFolder.createTemporaryDirectory();
    }

    @AfterEach
    public void tearDown() {
        bareGit.close();
        workingGit.close();
    }

    @Test
    public void testFetchUserKey() throws JsonProcessingException, IOException, NoFilepatternException, GitAPIException {
        Path gitRealm = users.resolve(GIT_REALM);
        Path creatorRealm = users.resolve(JITSTATIC_KEYADMIN_REALM);
        Path updaterRealm = users.resolve(JITSTATIC_KEYUSER_REALM);
        mkdirs(gitRealm, creatorRealm, updaterRealm);

        String gitUserKey = "gituser";
        String creatorUserKey = "creatorUser";
        String updaterUserKey = "updaterUser";

        Path gituser = gitRealm.resolve(gitUserKey);
        Path creatorUser = creatorRealm.resolve(creatorUserKey);
        Path updaterUser = updaterRealm.resolve(updaterUserKey);

        Path sgituser = gitRealm.resolve("sgituser");
        Path screatorUser = creatorRealm.resolve("screatorUser");
        Path supdaterUser = updaterRealm.resolve("supdaterUser");

        UserData gitUserData = new UserData(Set.of(new Role("pull"), new Role("push"), new Role("forcepush")), "1234", null, null); // Full admin rights
        UserData creatorUserData = new UserData(Set.of(new Role("files")), "2345", null, null);
        UserData updaterUserData = new UserData(Set.of(new Role("files")), "3456", null, null);

        UserData sgitUserData = new UserData(Set.of(new Role("pull"), new Role("push"), new Role("forcepush")), "s1234", null, null); // Full admin rights
        UserData screatorUserData = new UserData(Set.of(new Role("files")), "s2345", null, null);
        UserData supdaterUserData = new UserData(Set.of(new Role("files")), "s3456", null, null);

        write(gituser, gitUserData);
        write(creatorUser, creatorUserData);
        write(updaterUser, updaterUserData);

        write(sgituser, sgitUserData);
        write(screatorUser, screatorUserData);
        write(supdaterUser, supdaterUserData);

        commit();
        UserExtractor ue = new UserExtractor(bareGit.getRepository());
        Pair<String, UserData> extractUserFromRef = ue.extractUserFromRef(USERS + GIT_REALM + "/" + gitUserKey, REF_HEAD_MASTER);
        assertEquals(gitUserData, extractUserFromRef.getRight());
        assertNotNull(extractUserFromRef.getLeft());
        extractUserFromRef = ue.extractUserFromRef(USERS + JITSTATIC_KEYADMIN_REALM + "/" + creatorUserKey, REF_HEAD_MASTER);
        assertEquals(creatorUserData, extractUserFromRef.getRight());
        assertNotNull(extractUserFromRef.getLeft());
        extractUserFromRef = ue.extractUserFromRef(USERS + JITSTATIC_KEYUSER_REALM + "/" + updaterUserKey, REF_HEAD_MASTER);
        assertEquals(updaterUserData, extractUserFromRef.getRight());
        assertNotNull(extractUserFromRef.getLeft());

    }

    @Test
    public void testValidateAll() throws Exception {
        Path gitRealm = users.resolve(GIT_REALM);

        Path creatorRealm = users.resolve(JITSTATIC_KEYUSER_REALM);
        Path updaterRealm = users.resolve(JITSTATIC_KEYADMIN_REALM);
        mkdirs(gitRealm, creatorRealm, updaterRealm);

        String gitUserKey = "gituser";
        String creatorUserKey = "creatorUser";
        String updaterUserKey = "updaterUser";

        Path gituser = gitRealm.resolve(gitUserKey);
        Path creatorUser = creatorRealm.resolve(creatorUserKey);
        Path updaterUser = updaterRealm.resolve(updaterUserKey);

        Path sgituser = gitRealm.resolve("sgituser");
        Path screatorUser = creatorRealm.resolve("screatorUser");
        Path supdaterUser = updaterRealm.resolve("supdaterUser");

        UserData gitUserData = new UserData(Set.of(new Role("pull"), new Role("push"), new Role("forcepush")), "1234", null, null); // Full admin rights
        UserData creatorUserData = new UserData(Set.of(new Role("files")), "2345", null, null);
        UserData updaterUserData = new UserData(Set.of(new Role("files")), "3456", null, null);

        UserData sgitUserData = new UserData(Set.of(new Role("pull"), new Role("push"), new Role("forcepush")), "s1234", null, null); // Full admin rights
        UserData screatorUserData = new UserData(Set.of(new Role("files")), "s2345", null, null);
        UserData supdaterUserData = new UserData(Set.of(new Role("files")), "s3456", null, null);

        write(gituser, gitUserData);
        write(creatorUser, creatorUserData);
        write(updaterUser, updaterUserData);

        write(sgituser, sgitUserData);
        write(screatorUser, screatorUserData);
        write(supdaterUser, supdaterUserData);

        commit();
        UserExtractor ue = new UserExtractor(bareGit.getRepository());
        assertTrue(ue.validateAll().isEmpty());
        Files.write(sgituser, new byte[] { 1 }, StandardOpenOption.TRUNCATE_EXISTING);
        commit();
        List<Pair<Set<Ref>, List<Pair<String, List<Pair<FileObjectIdStore, Exception>>>>>> errors = ue.validateAll();
        assertTrue(errors.size() == 1);
        Pair<Set<Ref>, List<Pair<String, List<Pair<FileObjectIdStore, Exception>>>>> masterBranch = errors.get(0);
        // returned Ref doesn't implement hashcode/equals
        Ref ref = masterBranch.getLeft().iterator().next();
        assertEquals(REF_HEAD_MASTER, ref.getName());
        assertEquals(bareGit.getRepository().getRefDatabase().exactRef(REF_HEAD_MASTER).getObjectId(), ref.getObjectId());
        Pair<String, List<Pair<FileObjectIdStore, Exception>>> realmErrors = masterBranch.getRight().get(0);
        assertEquals(GIT_REALM, realmErrors.getLeft());
        List<Pair<FileObjectIdStore, Exception>> fileErrors = realmErrors.getRight();
        assertEquals(USERS + GIT_REALM + "/sgituser", fileErrors.get(0).getLeft().getFileName());
        assertNotNull(fileErrors.get(0).getRight());
    }

    @Test
    public void testGitRealmUserDoesntHaveCorrectRoles() throws Exception {
        Path gitRealm = users.resolve(GIT_REALM);

        mkdirs(gitRealm);

        Path gituser = gitRealm.resolve("gituser");
        Path sgituser = gitRealm.resolve("sgituser");
        Path tgituser = gitRealm.resolve("tgituser");

        UserData gitUserData = new UserData(Set.of(new Role("pull"), new Role("push"), new Role("forcepush")), "1234", null, null); // Full admin rights
        UserData sgitUserData = new UserData(Set.of(new Role("pull")), "s1234", null, null);
        UserData tgitUserData = new UserData(Set.of(new Role("unknown")), "2345", null, null);
        write(gituser, gitUserData);
        write(sgituser, sgitUserData);
        write(tgituser, tgitUserData);

        commit();
        UserExtractor ue = new UserExtractor(bareGit.getRepository());
        List<Pair<Set<Ref>, List<Pair<String, List<Pair<FileObjectIdStore, Exception>>>>>> errors = ue.validateAll();
        assertFalse(errors.isEmpty());
        assertTrue(errors.size() == 1);
        Pair<Set<Ref>, List<Pair<String, List<Pair<FileObjectIdStore, Exception>>>>> masterBranch = errors.get(0);
        // returned Ref doesn't implement hashcode/equals
        Ref ref = masterBranch.getLeft().iterator().next();
        assertEquals(REF_HEAD_MASTER, ref.getName());
        assertEquals(bareGit.getRepository().getRefDatabase().exactRef(REF_HEAD_MASTER).getObjectId(), ref.getObjectId());
        Pair<String, List<Pair<FileObjectIdStore, Exception>>> realmErrors = masterBranch.getRight().get(0);
        assertEquals(GIT_REALM, realmErrors.getLeft());
        List<Pair<FileObjectIdStore, Exception>> fileErrors = realmErrors.getRight();
        assertEquals(USERS + GIT_REALM + "/tgituser", fileErrors.get(0).getLeft().getFileName());
        assertNotNull(fileErrors.get(0).getRight());

    }

    public void write(Path userPath, UserData userData) throws IOException, JsonProcessingException {
        Files.write(userPath, MAPPER.writeValueAsBytes(userData), StandardOpenOption.CREATE);
    }

    private void mkdirs(Path... realms) {
        for (Path realm : realms) {
            assertTrue(realm.toFile().mkdirs());
        }
    }
}
