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
import java.util.Base64;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.AbortedByHookException;
import org.eclipse.jgit.api.errors.ConcurrentRefUpdateException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.NoFilepatternException;
import org.eclipse.jgit.api.errors.NoHeadException;
import org.eclipse.jgit.api.errors.NoMessageException;
import org.eclipse.jgit.api.errors.UnmergedPathsException;
import org.eclipse.jgit.api.errors.WrongRepositoryStateException;
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
        repo.add().addFilepattern(ALLFILESPATTERN).call();
        repo.commit().setMessage("Added user " + userName).call();
    }

    static class User {
        private String basicPassword;
        private Set<Role> roles;

        public User() {
            this(null, null);
        }

        public User(Set<Role> roles, String password) {
            this.setBasicPassword(password);
            this.setRoles(roles);
        }

        public String getBasicPassword() { return basicPassword; }

        public Set<Role> getRoles() { return roles; }

        public void setRoles(Set<Role> roles) { this.roles = roles; }

        public void setBasicPassword(String basicPassword) { this.basicPassword = basicPassword; }
    }

    static class Role {
        private String role;

        public Role() {
            this(null);
        }

        public Role(String role) {
            this.setRole(role);
        }

        public String getRole() { return role; }

        public void setRole(String role) { this.role = role; }
        
        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            Role other = (Role) obj;
            return Objects.equals(role, other.role);
        }
        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((role == null) ? 0 : role.hashCode());
            return result;
        }
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
    
    protected void commit(Git git) throws NoHeadException, NoMessageException, UnmergedPathsException, ConcurrentRefUpdateException, WrongRepositoryStateException, AbortedByHookException, GitAPIException {
        git.add().addFilepattern(ALLFILESPATTERN).call();
        git.commit().setMessage("Test commit").call();
    }

    protected void commitAndPush(Git git, UsernamePasswordCredentialsProvider provider) throws NoFilepatternException, GitAPIException {
        commit(git);
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

    protected String baseEncode(String user, String password) {
        return Base64.getEncoder().encodeToString((user + ":" + password).getBytes(UTF_8));
    }

    public static Exception shutdownExecutor(final ExecutorService service) {
        try {
            service.shutdown();
            service.awaitTermination(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            return e;
        }
        return null;
    }
}
