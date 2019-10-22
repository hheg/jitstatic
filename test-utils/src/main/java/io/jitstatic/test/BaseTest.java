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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.NoFilepatternException;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate.Status;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import com.fasterxml.jackson.core.JsonParser.Feature;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.jitstatic.client.JitStaticClient;
import io.jitstatic.client.JitStaticClientBuilder;
import io.jitstatic.client.TriFunction;

public abstract class BaseTest {

    protected static class Entity<T> {

        private final T data;
        private final String tag;
        private final String contentType;

        public Entity(String tag, String contentType, T data) {
            this.tag = tag;
            this.contentType = contentType;
            this.data = data;
        }

        public String getTag() { return tag; }

        public String getContentType() { return contentType; }

        public T getData() { return data; }
    }

    protected static final ObjectMapper MAPPER = new ObjectMapper().enable(Feature.ALLOW_COMMENTS);
    protected static final String REFS_HEADS_MASTER = "refs/heads/master";
    protected static final Charset UTF_8 = StandardCharsets.UTF_8;
    protected static final String ALLFILESPATTERN = ".";

    protected String getData() { return getData(0); }

    protected String getMetaData() { return "{\"users\":[],\"read\":[{\"role\":\"read\"}],\"write\":[{\"role\":\"write\"}]}"; }
    protected String getMetaDataHidden() { return "{\"users\":[],\"read\":[{\"role\":\"read\"}],\"write\":[{\"role\":\"write\"}],\"hidden\":true}"; }
    protected String getMetaDataProtected() { return "{\"users\":[],\"read\":[{\"role\":\"read\"}],\"write\":[{\"role\":\"write\"}],\"protected\":true}"; }

    protected String getData(int i) {
        return "{\"key" + i
                + "\":{\"data\":\"value1\",\"users\":[{\"captain\":\"america\",\"black\":\"widow\"}]},\"mkey3\":{\"data\":\"value3\",\"users\":[{\"tony\":\"stark\",\"spider\":\"man\"}]}}";
    }

    protected File getFolderFile() throws IOException {
        throw new UnsupportedOperationException("implement this");
    }

    protected Supplier<String> getFolder() {
        return () -> {
            try {
                return getFolderFile().getAbsolutePath();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        };
    }

    protected JitStaticClientBuilder buildClient(int port) {
        return JitStaticClient.create().setScheme("http").setHost("localhost").setPort(port).setAppContext("/application/");
    }

    protected <T> TriFunction<InputStream, String, String, Entity<T>> parse(Class<T> clazz) {
        return (is, v, t) -> {
            if (is != null) {
                try {
                    return new Entity<>(v, t, MAPPER.readValue(is, clazz));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return new Entity<>(v, t, null);
        };
    }

    protected void setupUser(Git repo, String realm, String userName, String password, Set<String> roles)
            throws JsonProcessingException, IOException, NoFilepatternException, GitAPIException {
        File gitBase = repo.getRepository().getDirectory().getParentFile();
        Path user = gitBase.toPath().resolve(".users/" + realm + "/" + userName);
        mkdirs(user.getParent());
        Set<Role> newroles = roles.stream().map(Role::new).collect(Collectors.toSet());
        Files.write(user, MAPPER.writeValueAsBytes(new User(newroles, password)), StandardOpenOption.CREATE);
        repo.add().addFilepattern(".").call();
        repo.commit().setMessage("Added user " + userName);
    }

    static class User {
        String basicPassword;
        Set<Role> roles;

        public User(Set<Role> roles, String password) {
            this.basicPassword = password;
            this.roles = roles;
        }

        public String getBasicPassword() { return basicPassword; }

        public Set<Role> getRoles() { return roles; }
    }

    static class Role {
        String role;

        public Role(String role) {
            this.role = role;
        }

        public String getRole() { return role; }
    }

    protected void verifyOkPush(Iterable<PushResult> call) {
        assertTrue(StreamSupport.stream(call.spliterator(), false)
                .allMatch(p -> p.getRemoteUpdates().stream()
                        .allMatch(u -> u.getStatus() == Status.OK)), () -> StreamSupport.stream(call.spliterator(), false)
                                .map(pr -> pr.getRemoteUpdates().stream()
                                        .map(ru -> String.format("%s %s %s", ru.getStatus(), ru.getRemoteName(), ru.getMessage()))
                                        .collect(Collectors.joining(",")))
                                .collect(Collectors.joining(",")));
    }

    protected void commit(Git git, UsernamePasswordCredentialsProvider provider) throws NoFilepatternException, GitAPIException {
        git.add().addFilepattern(ALLFILESPATTERN).call();
        git.commit().setMessage("Test commit").call();
        verifyOkPush(git.push().setCredentialsProvider(provider).call());
    }

    protected void mkdirs(Path... paths) {
        for (Path p : paths) {
            assertTrue(p.toFile().mkdirs());
        }
    }

    protected Set<io.jitstatic.client.MetaData.Role> roleOf(String... roles) {
        return Arrays.stream(roles).map(io.jitstatic.client.MetaData.Role::new).collect(Collectors.toSet());
    }
}
