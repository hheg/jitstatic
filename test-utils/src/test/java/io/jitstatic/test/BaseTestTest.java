package io.jitstatic.test;

/*-
 * #%L
 * jitstatic
 * %%
 * Copyright (C) 2017 - 2019 H.Hegardt
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

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.opentest4j.AssertionFailedError;

@ExtendWith(TemporaryFolderExtension.class)
class BaseTestTest extends BaseTest {

    private TemporaryFolder folder;

    @Test
    void testSetUpGitRepo() throws IllegalStateException, GitAPIException, IOException {
        File base = getFolderFile();
        File cloneDir = getFolderFile();
        try (Git git = Git.init().setDirectory(base).call()) {
            setupUser(git, "realm", "userName", "pass", Set.of("role"));
            assertTrue(Files.exists(base.toPath().resolve(".users").resolve("realm").resolve("userName")));
            try (Git clone = Git.cloneRepository().setDirectory(cloneDir).setURI(base.toString()).call()) {
                assertTrue(Files.exists(cloneDir.toPath().resolve(".users").resolve("realm").resolve("userName")));
                Files.write(cloneDir.toPath().resolve("file"), MAPPER.writeValueAsBytes(getData()), StandardOpenOption.CREATE_NEW);
                Files.write(cloneDir.toPath().resolve("file.metadata"), MAPPER.writeValueAsBytes(getMetaData()), StandardOpenOption.CREATE_NEW);
                Files.write(cloneDir.toPath().resolve("hidden.file"), MAPPER.writeValueAsBytes(getMetaDataHidden()), StandardOpenOption.CREATE_NEW);
                Files.write(cloneDir.toPath().resolve("protected.file"), MAPPER.writeValueAsBytes(getMetaDataProtected()), StandardOpenOption.CREATE_NEW);
                commitAndPush(clone, null);
                try (InputStream is = Files.newInputStream(cloneDir.toPath().resolve(".users").resolve("realm").resolve("userName"))) {
                    Entity<User> apply = parse(User.class).apply(is, "tag", "type");
                    assertEquals("tag", apply.getTag());
                    assertEquals("type", apply.getContentType());
                    assertEquals(Set.of(new Role("role")), apply.getData().getRoles());
                }
            }
            git.reset().setMode(ResetType.HARD).call();
            assertTrue(Files.exists(base.toPath().resolve("file")));
        }
    }

    @Test
    void testShutDownExecutor() {
        ExecutorService es = Executors.newCachedThreadPool();
        shutdownExecutor(es);
        assertTrue(es.isShutdown());
    }

    @Test
    void testCreateRoles() {
        Set<io.jitstatic.client.MetaData.Role> roles = roleOf("hello");
        assertTrue(roles.stream().allMatch(s -> s.getRole().equals("hello")));
    }

    @Test
    void testFailedPush() throws IOException, IllegalStateException, GitAPIException {
        File base = getFolderFile();
        File cloneDir = getFolderFile();
        try (Git git = Git.init().setDirectory(base).call()) {
            setupUser(git, "realm", "userName", "pass", Set.of("role"));
            assertTrue(Files.exists(base.toPath().resolve(".users").resolve("realm").resolve("userName")));
            try (Git clone = Git.cloneRepository().setDirectory(cloneDir).setURI(base.toString()).call()) {
                assertTrue(Files.exists(cloneDir.toPath().resolve(".users").resolve("realm").resolve("userName")));
                Files.write(cloneDir.toPath().resolve("file"), MAPPER.writeValueAsBytes(getData()), StandardOpenOption.CREATE_NEW);
                commit(clone);
                Files.write(base.toPath().resolve("file"), MAPPER.writeValueAsBytes(getData(2)), StandardOpenOption.CREATE_NEW);
                commit(git);
                assertThrows(AssertionFailedError.class, () -> commitAndPush(clone, null));
            }
        }
    }

    @Test
    void testGetFolder() {
        Supplier<String> folder2 = getFolder();
        assertTrue(Paths.get(folder2.get()).toFile().exists());
    }

    @Test
    void testGetClient() {
        assertNotNull(buildClient(0));
    }

    @Override
    protected File getFolderFile() throws IOException { return folder.createTemporaryDirectory(); }
}
