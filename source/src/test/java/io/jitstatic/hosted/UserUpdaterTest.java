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
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.errors.UnmergedPathException;
import org.eclipse.jgit.lib.Constants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.jitstatic.CommitMetaData;
import io.jitstatic.JitStaticConstants;
import io.jitstatic.RepositoryUpdater;
import io.jitstatic.Role;
import io.jitstatic.auth.UserData;
import io.jitstatic.test.TemporaryFolder;
import io.jitstatic.test.TemporaryFolderExtension;
import io.jitstatic.utils.Pair;

@ExtendWith(TemporaryFolderExtension.class)
public class UserUpdaterTest {
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
        users = wBase.toPath().resolve(USERS);
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
    public void testUpdateUsers()
            throws MissingObjectException, IncorrectObjectTypeException, CorruptObjectException, UnmergedPathException, IOException, RefNotFoundException {
        String gitAdmin = USERS + GIT_REALM + "/gitadmin";
        String keyAdmin = USERS + JITSTATIC_KEYADMIN_REALM + "/keyadmin";
        String keyUser = USERS + JITSTATIC_KEYUSER_REALM + "/keyuser";

        UserData gitAdminData = new UserData(Set.of(new Role("pull"), new Role("push"), new Role("forcepush")), "1234", null, null); // Full admin rights
        UserData keyAdminData = new UserData(Set.of(new Role("files")), "2345", null, null);
        UserData keyUserData = new UserData(Set.of(new Role("files")), "3456", null, null);

        UserUpdater uu = new UserUpdater(new RepositoryUpdater(bareGit.getRepository()));

        List<Pair<String, String>> updateUser = uu.updateUser(
                List.of(Pair.of(gitAdmin, gitAdminData), Pair.of(keyAdmin, keyAdminData), Pair.of(keyUser, keyUserData)),
                bareGit.getRepository().findRef(REF_HEAD_MASTER), new CommitMetaData("test", "testmail", "test", "Test", JitStaticConstants.JITSTATIC_NOWHERE));

        UserExtractor ue = new UserExtractor(bareGit.getRepository());

        Pair<String, UserData> extractUserFromRef = ue.extractUserFromRef(gitAdmin, REF_HEAD_MASTER);
        assertEquals(gitAdminData, extractUserFromRef.getRight());
        assertEquals(updateUser.get(0).getRight(), extractUserFromRef.getLeft());

        extractUserFromRef = ue.extractUserFromRef(keyAdmin, REF_HEAD_MASTER);
        assertEquals(keyAdminData, extractUserFromRef.getRight());
        assertEquals(updateUser.get(1).getRight(), extractUserFromRef.getLeft());

        extractUserFromRef = ue.extractUserFromRef(keyUser, REF_HEAD_MASTER);
        assertEquals(keyUserData, extractUserFromRef.getRight());
        assertEquals(updateUser.get(2).getRight(), extractUserFromRef.getLeft());

        gitAdminData = new UserData(Set.of(new Role("pull"), new Role("push"), new Role("forcepush")), "s1234", null, null); // Full admin rights
        keyAdminData = new UserData(Set.of(new Role("files")), "s2345", null, null);
        keyUserData = new UserData(Set.of(new Role("files")), "s3456", null, null);

        updateUser = uu.updateUser(List.of(Pair.of(gitAdmin, gitAdminData), Pair.of(keyAdmin, keyAdminData), Pair.of(keyUser, keyUserData)),
                bareGit.getRepository().findRef(REF_HEAD_MASTER), new CommitMetaData("test", "testmail", "test", "Test", JitStaticConstants.JITSTATIC_NOWHERE));

        extractUserFromRef = ue.extractUserFromRef(gitAdmin, REF_HEAD_MASTER);
        assertEquals(gitAdminData, extractUserFromRef.getRight());
        assertEquals(updateUser.get(0).getRight(), extractUserFromRef.getLeft());

        extractUserFromRef = ue.extractUserFromRef(keyAdmin, REF_HEAD_MASTER);
        assertEquals(keyAdminData, extractUserFromRef.getRight());
        assertEquals(updateUser.get(1).getRight(), extractUserFromRef.getLeft());

        extractUserFromRef = ue.extractUserFromRef(keyUser, REF_HEAD_MASTER);
        assertEquals(keyUserData, extractUserFromRef.getRight());
        assertEquals(updateUser.get(2).getRight(), extractUserFromRef.getLeft());

    }

    @Test
    public void testUpdateSingleUser() throws MissingObjectException, IncorrectObjectTypeException, CorruptObjectException, RefNotFoundException, IOException {
        String gitAdmin = USERS + GIT_REALM + "/gitadmin";
        UserData gitAdminData = new UserData(Set.of(new Role("pull"), new Role("push"), new Role("forcepush")), "1234", null, null); // Full admin rights

        UserUpdater uu = new UserUpdater(new RepositoryUpdater(bareGit.getRepository()));
        String updateUser = uu.updateUser(gitAdmin, bareGit.getRepository().findRef(REF_HEAD_MASTER), gitAdminData,
                new CommitMetaData("user", "test", "msg", "Test", JitStaticConstants.JITSTATIC_NOWHERE));
        UserExtractor ue = new UserExtractor(bareGit.getRepository());
        Pair<String, UserData> extractUserFromRef = ue.extractUserFromRef(gitAdmin, REF_HEAD_MASTER);
        assertEquals(gitAdminData, extractUserFromRef.getRight());
        assertEquals(updateUser, extractUserFromRef.getLeft());
    }

    public void write(Path userPath, UserData userData) throws IOException, JsonProcessingException {
        Files.write(userPath, MAPPER.writeValueAsBytes(userData), StandardOpenOption.CREATE);
    }
}
