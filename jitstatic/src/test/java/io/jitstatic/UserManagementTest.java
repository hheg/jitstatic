package io.jitstatic;

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

import static io.jitstatic.JitStaticConstants.CREATE;
import static io.jitstatic.JitStaticConstants.FORCEPUSH;
import static io.jitstatic.JitStaticConstants.GIT_REALM;
import static io.jitstatic.JitStaticConstants.JITSTATIC_KEYADMIN_REALM;
import static io.jitstatic.JitStaticConstants.JITSTATIC_KEYUSER_REALM;
import static io.jitstatic.JitStaticConstants.PULL;
import static io.jitstatic.JitStaticConstants.PUSH;
import static io.jitstatic.JitStaticConstants.SECRETS;
import static io.jitstatic.JitStaticConstants.USERS;
import static org.eclipse.jetty.http.HttpStatus.FORBIDDEN_403;
import static org.eclipse.jetty.http.HttpStatus.NOT_FOUND_404;
import static org.eclipse.jetty.http.HttpStatus.UNAUTHORIZED_401;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.http.client.ClientProtocolException;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jgit.api.CreateBranchCommand.SetupUpstreamMode;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand.ListMode;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.NoFilepatternException;
import org.eclipse.jgit.api.errors.RefNotAdvertisedException;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.RemoteRefUpdate.Status;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.io.Files;

import io.dropwizard.testing.ConfigOverride;
import io.dropwizard.testing.junit5.DropwizardAppExtension;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.jitstatic.api.KeyData;
import io.jitstatic.api.KeyDataWrapper;
import io.jitstatic.api.SearchResult;
import io.jitstatic.api.SearchResultWrapper;
import io.jitstatic.auth.UserData;
import io.jitstatic.client.APIException;
import io.jitstatic.client.BulkSearch;
import io.jitstatic.client.CommitData;
import io.jitstatic.client.JitStaticClient;
import io.jitstatic.client.MetaData;
import io.jitstatic.client.ModifyUserKeyData;
import io.jitstatic.client.SearchPath;
import io.jitstatic.hosted.HostedFactory;
import io.jitstatic.test.BaseTest;
import io.jitstatic.test.TemporaryFolder;
import io.jitstatic.test.TemporaryFolderExtension;
import io.jitstatic.tools.AUtils;

@ExtendWith({ TemporaryFolderExtension.class, DropwizardExtensionsSupport.class })
public class UserManagementTest extends BaseTest {

    private static final String REFS_REMOTES_ORIGIN = "refs/remotes/origin/";
    private static final String KEYUSERNOROLE = "keyusernorole";
    private static final String KEYUSERNOROLEPASS = "1456";

    private static final String KEYADMINUSER = "keyadminuser";
    private static final String KEYADMINUSERPASS = "3456";

    private static final String GITUSER = "gituser";
    private static final String GITUSERPASS = "3234";

    private static final String KEYUSER = "keyuser";
    private static final String KEYUSERPASS = "2456";

    private static final String GITUSERFULL = "gituserfull";
    private static final String GITUSERFULLPASS = "1234";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String GITUSERPUSH = "gituserpush";
    private static final String GITUSERPUSHPASS = "3333";
    private static final Set<Role> ALL_ROLES = Set.of(new Role(PULL), new Role(PUSH), new Role(FORCEPUSH), new Role(SECRETS), new Role(CREATE));
    private TemporaryFolder tmpFolder;
    private String rootUser;
    private String rootPassword;

    private DropwizardAppExtension<JitstaticConfiguration> DW = new DropwizardAppExtension<>(JitstaticApplication.class, AUtils
            .getDropwizardConfigurationResource(), ConfigOverride.config("hosted.basePath", getFolder()));
    private String adress;
    private String gitAdress;
    private UserData keyAdminUserData;
    private io.jitstatic.api.UserData keyUserUserData;

    @BeforeEach
    public void setup() throws Exception {
        HostedFactory hostedFactory = DW.getConfiguration().getHostedFactory();
        adress = String.format("http://localhost:%d/application", DW.getLocalPort());
        rootUser = hostedFactory.getUserName();
        rootPassword = hostedFactory.getSecret();
        Path workingFolder = getFolderFile().toPath();
        String servletName = hostedFactory.getServletName();
        String endpoint = hostedFactory.getHostedEndpoint();
        gitAdress = adress + "/" + servletName + "/" + endpoint;
        UsernamePasswordCredentialsProvider provider = new UsernamePasswordCredentialsProvider(rootUser, rootPassword);
        try (Git git = Git.cloneRepository().setDirectory(workingFolder.toFile()).setURI(gitAdress).setCredentialsProvider(provider).call()) {
            Path users = workingFolder.resolve(USERS);

            Path keyAdminRealm = users.resolve(JITSTATIC_KEYADMIN_REALM);
            Path keyUserRealm = users.resolve(JITSTATIC_KEYUSER_REALM);

            mkdirs(users, keyAdminRealm, keyUserRealm);

            Path keyAdminUser = keyAdminRealm.resolve(KEYADMINUSER);
            Path keyuser = keyUserRealm.resolve(KEYUSER);
            Path keyusernorole = keyUserRealm.resolve(KEYUSERNOROLE);
            keyAdminUserData = new UserData(Set.of(new Role("read"), new Role("write")), KEYADMINUSERPASS, null, null);
            keyUserUserData = new io.jitstatic.api.UserData(Set.of(new Role("role")), KEYUSERPASS);
            UserData keyUserUserDataNoRole = new UserData(Set.of(), KEYUSERNOROLEPASS, null, null);
            Files.write(MAPPER.writeValueAsBytes(keyAdminUserData), keyAdminUser.toFile());
            Files.write(MAPPER.writeValueAsBytes(keyUserUserData), keyuser.toFile());
            Files.write(MAPPER.writeValueAsBytes(keyUserUserDataNoRole), keyusernorole.toFile());
            Files.write(getData().getBytes(UTF_8), workingFolder.resolve("file").toFile());
            Files.write(getMetaData(new io.jitstatic.MetaData(JitStaticConstants.APPLICATION_JSON, false, false, List.of(), Set
                    .of(new Role("role")), Set.of(new Role("role")))).getBytes(UTF_8), workingFolder.resolve("file.metadata").toFile());
            commit(git, provider);
            Path gitRealm = users.resolve(GIT_REALM);
            mkdirs(gitRealm);
            Path gitUser = gitRealm.resolve(GITUSERFULL);
            Path gitUserPush = gitRealm.resolve(GITUSERPUSH);
            Path gitUserNoPush = gitRealm.resolve(GITUSER);
            UserData gitUserData = new UserData(ALL_ROLES, GITUSERFULLPASS, null, null);
            UserData gitUserDataNoPush = new UserData(Set.of(new Role(PULL)), GITUSERPASS, null, null);
            UserData gitUserDataPush = new UserData(Set.of(new Role(PULL), new Role(PUSH)), GITUSERPUSHPASS, null, null);
            Files.write(MAPPER.writeValueAsBytes(gitUserData), gitUser.toFile());
            Files.write(MAPPER.writeValueAsBytes(gitUserDataPush), gitUserPush.toFile());
            Files.write(MAPPER.writeValueAsBytes(gitUserDataNoPush), gitUserNoPush.toFile());
            commit(git, provider, SECRETS);
        }
    }

    @Test
    public void testNoUser() {
        assertTrue(assertThrows(TransportException.class, () -> {
            try (Git git = Git.cloneRepository().setDirectory(getFolderFile().toPath().toFile()).setURI(gitAdress).call()) {
            }
        }).getMessage().contains("Authentication is required but no CredentialsProvider has been registered"));
    }

    @Test
    public void testGitRealmUserPullPush() throws IOException, InvalidRemoteException, TransportException, GitAPIException {
        Path workingFolder = getFolderFile().toPath();
        UsernamePasswordCredentialsProvider provider = new UsernamePasswordCredentialsProvider(GITUSERFULL, GITUSERFULLPASS);
        try (Git git = Git.cloneRepository().setDirectory(workingFolder.toFile()).setURI(gitAdress).setCredentialsProvider(provider).call()) {
            Files.write(getData(1).getBytes(UTF_8), workingFolder.resolve("file").toFile());
            commit(git, provider);
        }
    }

    @Test
    public void testGitRealmUserPullNoPush() throws IOException, InvalidRemoteException, TransportException, GitAPIException {
        Path workingFolder = getFolderFile().toPath();
        UsernamePasswordCredentialsProvider provider = new UsernamePasswordCredentialsProvider(GITUSER, GITUSERPASS);
        try (Git git = Git.cloneRepository().setDirectory(workingFolder.toFile()).setURI(gitAdress).setCredentialsProvider(provider).call()) {
            Files.write(getData(1).getBytes(UTF_8), workingFolder.resolve("file").toFile());
            assertTrue(assertThrows(TransportException.class, () -> commit(git, provider)).getMessage().contains("authentication not supported"));
        }
    }

    @Test
    public void testKeyAdminAddKey() throws URISyntaxException, ClientProtocolException, APIException, IOException {
        try (JitStaticClient creator = buildClient(DW.getLocalPort()).setPassword(KEYADMINUSERPASS).setUser(KEYADMINUSER).build()) {
            creator.createKey(getData(2)
                    .getBytes(UTF_8), new CommitData("file2", "msg", "ui", "mail"), new io.jitstatic.client.MetaData("application/json", Set.of(), Set.of()));
        }
        try (JitStaticClient creator = buildClient(DW.getLocalPort()).setPassword(KEYADMINUSERPASS).setUser(KEYADMINUSER).build()) {
            creator.createKey(getData(2)
                    .getBytes(UTF_8), new CommitData("file4", "master", "msg", "ui", "mail"), new io.jitstatic.client.MetaData("application/json", Set.of(), Set
                            .of()));
        }
        assertEquals(UNAUTHORIZED_401, assertThrows(APIException.class, () -> {
            try (JitStaticClient creator = buildClient(DW.getLocalPort()).setPassword(KEYADMINUSERPASS).setUser("otheruser").build()) {
                creator.createKey(getData(2)
                        .getBytes(UTF_8), new CommitData("file3", "master", "msg", "ui", "mail"), new io.jitstatic.client.MetaData("application/json", Set
                                .of(), Set.of()));
            }
        }).getStatusCode());
        assertEquals(HttpStatus.CONFLICT_409, assertThrows(APIException.class, () -> {
            try (JitStaticClient creator = buildClient(DW.getLocalPort()).setPassword(KEYADMINUSERPASS).setUser(KEYADMINUSER).build()) {
                creator.createKey(getData(2)
                        .getBytes(UTF_8), new CommitData("file2", "master", "msg", "ui", "mail"), new io.jitstatic.client.MetaData("application/json", Set
                                .of(), Set.of()));
            }
        }).getStatusCode());
        assertEquals(UNAUTHORIZED_401, assertThrows(APIException.class, () -> {
            try (JitStaticClient creator = buildClient(DW.getLocalPort()).setPassword(KEYADMINUSERPASS).setUser("otheruser").build()) {
                creator.createKey(getData(2)
                        .getBytes(UTF_8), new CommitData("file2", "master", "msg", "ui", "mail"), new io.jitstatic.client.MetaData("application/json", Set
                                .of(), Set.of()));
            }
        }).getStatusCode());
    }

    @Test
    public void testKeyAdminModifyAnyKey() throws Exception {
        String version;
        try (JitStaticClient creator = buildClient(DW.getLocalPort()).setPassword(KEYADMINUSERPASS).setUser(KEYADMINUSER).build()) {
            version = creator.createKey(getData(2)
                    .getBytes(UTF_8), new CommitData("file2", "master", "msg", "ui", "mail"), new io.jitstatic.client.MetaData("application/json", Set.of(), Set
                            .of()));
        }
        try (JitStaticClient creator = buildClient(DW.getLocalPort()).setPassword(KEYADMINUSERPASS).setUser(KEYADMINUSER).build()) {
            String modifiedVersion = creator.modifyKey(getData(3).getBytes(UTF_8), new CommitData("file2", "master", "msg", "ui", "mail"), version);
            assertNotEquals(version, modifiedVersion);
        }
    }

    @Test
    public void testKeyUserCreateKey() throws Exception {
        String version;
        assertEquals(UNAUTHORIZED_401, assertThrows(APIException.class, () -> {
            try (JitStaticClient creator = buildClient(DW.getLocalPort()).setPassword("wrong").setUser(KEYUSER).build()) {
                creator.createKey(getData(2)
                        .getBytes(UTF_8), new CommitData("file2", "master", "msg", "ui", "mail"), new io.jitstatic.client.MetaData("application/json", Set
                                .of(), Set.of()));
            }
        }).getStatusCode());
        try (JitStaticClient creator = buildClient(DW.getLocalPort()).setPassword(KEYUSERPASS).setUser(KEYUSER).build()) {
            Set<io.jitstatic.client.MetaData.Role> roles = Set.of(new io.jitstatic.client.MetaData.Role("role"));
            version = creator.createKey(getData(3)
                    .getBytes(UTF_8), new CommitData("file2", "master", "msg", "ui", "mail"), new io.jitstatic.client.MetaData("application/json", roles, roles));
        }
        try (JitStaticClient updater = buildClient(DW.getLocalPort()).setPassword(KEYADMINUSERPASS).setUser(KEYADMINUSER).build()) {
            Entity<JsonNode> entity = updater.getKey("file2", parse(JsonNode.class), version);
            assertEquals(version, entity.getTag());
        }
        assertEquals(UNAUTHORIZED_401, assertThrows(APIException.class, () -> {
            try (JitStaticClient updater = buildClient(DW.getLocalPort()).setPassword("2222").setUser(KEYUSER).build()) {
                updater.getKey("file2", parse(JsonNode.class));
            }
        }).getStatusCode());

        try (JitStaticClient updater = buildClient(DW.getLocalPort()).setPassword(KEYADMINUSERPASS).setUser(KEYADMINUSER).build()) {
            Entity<JsonNode> entity = updater.getKey("file2", parse(JsonNode.class));
            assertEquals(version, entity.getTag());
        }

        assertEquals(FORBIDDEN_403, assertThrows(APIException.class, () -> {
            try (JitStaticClient updater = buildClient(DW.getLocalPort()).setPassword(KEYUSERNOROLEPASS).setUser(KEYUSERNOROLE).build()) {
                updater.getKey("file2", parse(JsonNode.class));
            }
        }).getStatusCode());
    }

    @Test
    public void testKeyUserModifyMetaData() throws Exception {
        String version;
        try (JitStaticClient creator = buildClient(DW.getLocalPort()).setPassword(KEYUSERPASS).setUser(KEYUSER).build()) {
            Set<io.jitstatic.client.MetaData.Role> roles = Set.of(new io.jitstatic.client.MetaData.Role("role"));
            creator.createKey(getData(2)
                    .getBytes(UTF_8), new CommitData("file2", "master", "msg", "ui", "mail"), new io.jitstatic.client.MetaData("application/json", roles, roles));
        }
        try (JitStaticClient creator = buildClient(DW.getLocalPort()).setPassword(KEYUSERPASS).setUser(KEYUSER).build()) {
            Entity<JsonNode> metaKey = creator.getMetaKey("file2", "master", parse(JsonNode.class));
            version = metaKey.getTag();
        }
        try (JitStaticClient creator = buildClient(DW.getLocalPort()).setPassword(KEYUSERPASS).setUser(KEYUSER).build()) {
            Set<io.jitstatic.client.MetaData.Role> roles = Set.of(new io.jitstatic.client.MetaData.Role("new"));
            version = creator
                    .modifyMetaKey("file2", "master", version, new ModifyUserKeyData(new io.jitstatic.client.MetaData("application/json", roles, roles), "msg", "mail", "ui"));
        }

        try (JitStaticClient creator = buildClient(DW.getLocalPort()).setPassword(KEYADMINUSERPASS).setUser(KEYADMINUSER).build()) {
            Entity<JsonNode> metaKey = creator.getMetaKey("file2", "master", parse(JsonNode.class));
            assertEquals(version, metaKey.getTag());
        }

        assertEquals(FORBIDDEN_403, assertThrows(APIException.class, () -> {
            try (JitStaticClient creator = buildClient(DW.getLocalPort()).setPassword(KEYUSERPASS).setUser(KEYUSER).build()) {
                creator.getMetaKey("file2", "master", parse(JsonNode.class));
            }
        }).getStatusCode());
    }

    @Test
    public void testListFiles() throws Exception {
        int i = 0;
        try (JitStaticClient creator = buildClient(DW.getLocalPort()).setPassword(KEYUSERPASS).setUser(KEYUSER).build()) {
            for (String file : List.of("path/file", "path/path/file", "file3")) {
                Set<io.jitstatic.client.MetaData.Role> roles = Set.of(new io.jitstatic.client.MetaData.Role("role"));
                creator.createKey(getData(i)
                        .getBytes(UTF_8), new CommitData(file, "master", "msg", "ui", "mail"), new io.jitstatic.client.MetaData("application/json", roles, roles));
                i++;
            }
        }
        try (JitStaticClient creator = buildClient(DW.getLocalPort()).setPassword(KEYUSERPASS).setUser(KEYUSER).build()) {
            i = 0;
            for (String file : List.of("path/file2", "path/path/file2", "file2")) {
                Set<io.jitstatic.client.MetaData.Role> roles = Set.of(new io.jitstatic.client.MetaData.Role("other"));
                creator.createKey(getData(i)
                        .getBytes(UTF_8), new CommitData(file, "master", "msg", "ui", "mail"), new io.jitstatic.client.MetaData("application/json", roles, roles));
                i++;
            }
        }
        try (JitStaticClient creator = buildClient(DW.getLocalPort()).setPassword(KEYUSERPASS).setUser(KEYUSER).build()) {
            KeyDataWrapper all = creator.listAll("path/", true, readKeyData());
            Set<String> files = all.getResult().stream().map(KeyData::getKey).collect(Collectors.toSet());
            assertEquals(Set.of("path/file", "path/path/file"), files);
        }
    }

    private Function<InputStream, KeyDataWrapper> readKeyData() {
        return (input) -> {
            try {
                return MAPPER.readValue(input, KeyDataWrapper.class);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }

    @Test
    public void testBulkSearch() throws Exception {
        int i = 0;
        try (JitStaticClient creator = buildClient(DW.getLocalPort()).setPassword(KEYUSERPASS).setUser(KEYUSER).build()) {
            for (String file : List.of("path/file", "path/path/file", "file3")) {
                Set<io.jitstatic.client.MetaData.Role> roles = Set.of(new io.jitstatic.client.MetaData.Role("role"));
                creator.createKey(getData(i)
                        .getBytes(UTF_8), new CommitData(file, "master", "msg", "ui", "mail"), new io.jitstatic.client.MetaData("application/json", roles, roles));
                i++;
            }
        }
        try (JitStaticClient creator = buildClient(DW.getLocalPort()).setPassword(KEYUSERPASS).setUser(KEYUSER).build()) {
            i = 0;
            for (String file : List.of("path/file2", "path/path/file2", "file2")) {
                Set<io.jitstatic.client.MetaData.Role> roles = Set.of(new io.jitstatic.client.MetaData.Role("other"));
                creator.createKey(getData(i)
                        .getBytes(UTF_8), new CommitData(file, "master", "msg", "ui", "mail"), new io.jitstatic.client.MetaData("application/json", roles, roles));
                i++;
            }
        }
        try (JitStaticClient creator = buildClient(DW.getLocalPort()).setPassword(KEYUSERPASS).setUser(KEYUSER).build()) {
            SearchResultWrapper search = creator.search(List.of(new BulkSearch("refs/heads/master", List.of(new SearchPath("path/", true)))), readData());
            Set<String> keys = search.getResult().stream().map(SearchResult::getKey).collect(Collectors.toSet());
            assertEquals(Set.of("path/file", "path/path/file"), keys);
        }
    }

    private Function<InputStream, SearchResultWrapper> readData() {
        return (input) -> {
            try {
                return MAPPER.readValue(input, SearchResultWrapper.class);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }

    @Test
    public void testGitUserCantPullSecretsBranch() throws Exception {
        Path workingFolder = getFolderFile().toPath();
        UsernamePasswordCredentialsProvider provider = new UsernamePasswordCredentialsProvider(GITUSER, GITUSERPASS);
        try (Git git = Git.cloneRepository().setDirectory(workingFolder.toFile()).setURI(gitAdress).setCredentialsProvider(provider).call()) {
            List<Ref> call = git.branchList().setListMode(ListMode.REMOTE).call();
            assertTrue(call.stream().noneMatch(r -> r.getName().equals(REFS_REMOTES_ORIGIN + SECRETS)));
            assertThrows(RefNotAdvertisedException.class, () -> git.pull().setRemoteBranchName(SECRETS).setCredentialsProvider(provider).call());

            git.checkout().setCreateBranch(true).setName(SECRETS).call();

            Path users = workingFolder.resolve(USERS);
            Path gitRealm = users.resolve(GIT_REALM);
            mkdirs(gitRealm);
            Path gitUserNoPush = gitRealm.resolve(GITUSER);

            UserData gitUserDataNoPush = new UserData(Set.of(new Role(PULL), new Role(SECRETS)), GITUSERPASS, null, null);
            Files.write(MAPPER.writeValueAsBytes(gitUserDataNoPush), gitUserNoPush.toFile());
            git.add().addFilepattern(ALLFILESPATTERN).call();
            git.commit().setMessage("New git user roles").call();

            assertTrue(assertThrows(TransportException.class, () -> git.push().setCredentialsProvider(provider).call()).getMessage().contains(""));
            Iterable<PushResult> pushResult = git.push().setCredentialsProvider(new UsernamePasswordCredentialsProvider(GITUSERPUSH, GITUSERPUSHPASS)).call();
            for (PushResult pr : pushResult) {
                Collection<Ref> advertisedRefs = pr.getAdvertisedRefs();
                advertisedRefs.forEach(System.out::println);
                for (RemoteRefUpdate remoteRefUpdate : pr.getRemoteUpdates()) {
                    assertEquals("refs/heads/" + SECRETS, remoteRefUpdate.getRemoteName());
                    assertEquals(Status.REJECTED_OTHER_REASON, remoteRefUpdate.getStatus());
                }
            }
        }
    }

    @Test
    public void testGitMasterCanPush() throws Exception {
        Path workingFolder = getFolderFile().toPath();
        UsernamePasswordCredentialsProvider provider = new UsernamePasswordCredentialsProvider(GITUSERFULL, GITUSERFULLPASS);
        try (Git git = Git.cloneRepository().setDirectory(workingFolder.toFile()).setURI(gitAdress).setCredentialsProvider(provider).call()) {
            List<Ref> call = git.branchList().setListMode(ListMode.REMOTE).call();
            assertTrue(call.stream().anyMatch(r -> r.getName().equals(REFS_REMOTES_ORIGIN + SECRETS)));

            assertNotNull(git.checkout().setStartPoint("origin/" + SECRETS).setCreateBranch(true).setUpstreamMode(SetupUpstreamMode.TRACK).setName(SECRETS)
                    .call());

            Path users = workingFolder.resolve(USERS);
            Path gitRealm = users.resolve(GIT_REALM);
            Path gitUserNoPush = gitRealm.resolve(GITUSER);

            UserData gitUserDataNoPush = new UserData(Set.of(new Role("pull"), new Role(PUSH)), GITUSERPASS, null, null);
            Files.write(MAPPER.writeValueAsBytes(gitUserDataNoPush), gitUserNoPush.toFile());
            git.add().addFilepattern(ALLFILESPATTERN).call();
            git.commit().setMessage("New git user roles").call();

            Iterable<PushResult> pushResult = git.push().setCredentialsProvider(provider).call();
            for (PushResult pr : pushResult) {
                Collection<Ref> advertisedRefs = pr.getAdvertisedRefs();
                advertisedRefs.forEach(System.out::println);
                for (RemoteRefUpdate remoteRefUpdate : pr.getRemoteUpdates()) {
                    assertEquals("refs/heads/" + SECRETS, remoteRefUpdate.getRemoteName());
                    assertEquals(Status.OK, remoteRefUpdate.getStatus());
                }
            }
        }
    }

    @Test
    public void testReadAndWriteEndpoint() throws Exception {
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(KEYUSER).setPassword(KEYUSERPASS).build();
                JitStaticClient noUser = buildClient(DW.getLocalPort()).build();) {
            String key = "key";
            String createKey = client.createKey(getData().getBytes(UTF_8), new CommitData(key, "msg", "info", "mail"), new MetaData("application/json", Set
                    .of(), Set.of(new MetaData.Role("role"))));
            Entity<JsonNode> key2 = noUser.getKey(key, parse(JsonNode.class));
            String tag = key2.getTag();
            assertEquals(createKey, tag);
            assertEquals(UNAUTHORIZED_401, assertThrows(APIException.class, () -> noUser
                    .modifyKey(getData(4).getBytes(UTF_8), new CommitData(key, "msg", "info", "mail"), tag))
                            .getStatusCode());
            String modifyKey = client.modifyKey(getData(5).getBytes(UTF_8), new CommitData(key, "msg", "info", "mail"), key2.getTag());
            key2 = noUser.getKey(key, parse(JsonNode.class));
            assertEquals(modifyKey, key2.getTag());
            Entity<JsonNode> metaKey = client.getMetaKey(key, "master", parse(JsonNode.class));
            MetaData newMetaData = new MetaData("application/json", Set.of(new MetaData.Role("role")), Set.of(new MetaData.Role("role")));
            client.modifyMetaKey(key, "master", metaKey.getTag(), new ModifyUserKeyData(newMetaData, "msg", "mail", "info"));
            assertEquals(FORBIDDEN_403, assertThrows(APIException.class, () -> noUser.getKey(key, parse(JsonNode.class))).getStatusCode());
            assertEquals(UNAUTHORIZED_401, assertThrows(APIException.class, () -> noUser.delete(new CommitData(key, "msg", "userinfo", "mail")))
                    .getStatusCode());
            client.delete(new CommitData(key, "msg", "userinfo", "mail"));
            assertEquals(NOT_FOUND_404, assertThrows(APIException.class, () -> client.getKey(key, parse(JsonNode.class))).getStatusCode());
            createKey = client.createKey(getData().getBytes(UTF_8), new CommitData(key, "msg", "info", "mail"), new MetaData("application/json", Set
                    .of(new MetaData.Role("role")), Set.of()));
            assertEquals(FORBIDDEN_403, assertThrows(APIException.class, () -> noUser.getKey(key, parse(JsonNode.class))).getStatusCode());
            assertEquals(FORBIDDEN_403, assertThrows(APIException.class, () -> client.delete(new CommitData(key, "msg", "userinfo", "mail")))
                    .getStatusCode());
            String tag2 = key2.getTag();
            assertEquals(FORBIDDEN_403, assertThrows(APIException.class, () -> client
                    .modifyKey(getData(5).getBytes(UTF_8), new CommitData(key, "msg", "info", "mail"), tag2))
                            .getStatusCode());
        }
    }

    @Test
    public void testGetKeyAdminUser() throws URISyntaxException, IOException {
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(GITUSER).setPassword(GITUSERPASS).build()) {
            Entity<JsonNode> adminUser = client.getAdminUser(KEYADMINUSER, null, null, parse(JsonNode.class));
            assertNotNull(adminUser.getData());
            io.jitstatic.api.UserData value = MAPPER.treeToValue(adminUser.getData(), io.jitstatic.api.UserData.class);
            assertEquals(keyAdminUserData.getRoles(), value.getRoles());
            assertEquals(keyAdminUserData.getBasicPassword(), value.getBasicPassword());
            assertNotNull(adminUser.getTag());
        }
    }

    @Test
    public void testGetUserWithNoUser() {
        assertEquals(HttpStatus.UNAUTHORIZED_401, assertThrows(APIException.class, () -> {
            try (JitStaticClient client = buildClient(DW.getLocalPort()).build()) {
                client.getAdminUser(KEYADMINUSER, null, null, parse(JsonNode.class));
            }
        }).getStatusCode());
    }

    @Test
    public void testGetUserWithWrongUser() throws RefNotFoundException {
        assertEquals(HttpStatus.UNAUTHORIZED_401, assertThrows(APIException.class, () -> {
            try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(KEYUSER).setPassword(KEYUSERPASS).build()) {
                client.getAdminUser(KEYADMINUSER, null, null, parse(JsonNode.class));
            }
        }).getStatusCode());
    }

    @Test
    public void testGetUserNotChanged() throws RefNotFoundException, URISyntaxException, IOException {
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(GITUSER).setPassword(GITUSERPASS).build()) {
            Entity<JsonNode> adminUser = client.getAdminUser(KEYADMINUSER, null, null, parse(JsonNode.class));
            assertNotNull(adminUser.getData());
            assertNotNull(adminUser.getTag());
            Entity<JsonNode> adminUser2 = client.getAdminUser(KEYADMINUSER, null, adminUser.getTag(), parse(JsonNode.class));
            assertNull(adminUser2.getData());
            assertEquals(adminUser.getTag(), adminUser2.getTag());
        }
    }

    @Test
    public void testPutUserWithNoUser() {
        assertEquals(HttpStatus.UNAUTHORIZED_401, assertThrows(APIException.class, () -> {
            try (JitStaticClient client = buildClient(DW.getLocalPort()).build()) {
                client.modifyAdminUser(KEYADMINUSER, null, new io.jitstatic.client.UserData(Set
                        .of(new io.jitstatic.client.MetaData.Role("role")), "22"), "12345");
            }
        }).getStatusCode());
    }

    @Test
    public void testPutUserWithUserButNotValid() {
        assertEquals(HttpStatus.UNAUTHORIZED_401, assertThrows(APIException.class, () -> {
            try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(KEYUSER).setPassword(KEYUSERPASS).build()) {
                client.modifyAdminUser(KEYADMINUSER, null, new io.jitstatic.client.UserData(Set
                        .of(new io.jitstatic.client.MetaData.Role("role")), "22"), "12345");
            }
        }).getStatusCode());
    }

    @Test
    public void testPutUserWithUserButNotFound() {
        assertEquals(HttpStatus.NOT_FOUND_404, assertThrows(APIException.class, () -> {
            try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(GITUSER).setPassword(GITUSERPASS).build()) {
                client.modifyAdminUser("noparse(JsonNode.class)ound", null, new io.jitstatic.client.UserData(Set
                        .of(new io.jitstatic.client.MetaData.Role("role")), "22"), "12345");
            }
        }).getStatusCode());
    }

    @Test
    public void testPutUserWithUserButFoundWrongEtag() throws RefNotFoundException {
        assertEquals(HttpStatus.PRECONDITION_FAILED_412, assertThrows(APIException.class, () -> {
            try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(GITUSER).setPassword(GITUSERPASS).build()) {
                client.modifyAdminUser(KEYADMINUSER, null, new io.jitstatic.client.UserData(Set
                        .of(new io.jitstatic.client.MetaData.Role("role")), "22"), "12345");
            }
        }).getStatusCode());
    }

    @Test
    public void testPutUserWithUserButRemoved() throws RefNotFoundException {
        assertEquals(HttpStatus.NOT_FOUND_404, assertThrows(APIException.class, () -> {
            try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(GITUSER).setPassword(GITUSERPASS).build()) {
                client.deleteAdminUser(KEYADMINUSER, null);
                client.modifyAdminUser(KEYADMINUSER, null, new io.jitstatic.client.UserData(Set
                        .of(new io.jitstatic.client.MetaData.Role("role")), "22"), "12345");
            }
        }).getStatusCode());
    }

    @Test
    public void testPutUserWithUser() throws URISyntaxException, IOException {
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(GITUSER).setPassword(GITUSERPASS).build()) {
            Entity<io.jitstatic.api.UserData> entity = client.getAdminUser(KEYADMINUSER, null, null, parse(io.jitstatic.api.UserData.class));
            String version = client.modifyAdminUser(KEYADMINUSER, null, from(entity.getData().getRoles(), "22"), entity.getTag());
            entity = client.getAdminUser(KEYADMINUSER, null, null, parse(io.jitstatic.api.UserData.class));
            assertEquals(version, entity.getTag());
        }
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(KEYADMINUSER).setPassword("22").build()) {
            assertNotNull(client.getUser(KEYUSER, null, null, parse(io.jitstatic.api.UserData.class)));
        }
    }

    @Test
    public void testPostUserWithNoUser() {
        assertEquals(HttpStatus.UNAUTHORIZED_401, assertThrows(APIException.class, () -> {
            try (JitStaticClient client = buildClient(DW.getLocalPort()).build()) {
                client.addAdminUser(KEYADMINUSER, null, new io.jitstatic.client.UserData(Set.of(new io.jitstatic.client.MetaData.Role("role")), "22"));
            }
        }).getStatusCode());
    }

    @Test
    public void testPostUserWithWrongUser() {
        assertEquals(HttpStatus.UNAUTHORIZED_401, assertThrows(APIException.class, () -> {
            try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(KEYUSER).setPassword(KEYUSERPASS).build()) {
                client.addAdminUser(KEYADMINUSER, null, new io.jitstatic.client.UserData(Set.of(new io.jitstatic.client.MetaData.Role("role")), "22"));
            }
        }).getStatusCode());
    }

    @Test
    public void testPostUserWithUserButExist() {
        assertEquals(HttpStatus.CONFLICT_409, assertThrows(APIException.class, () -> {
            try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(GITUSER).setPassword(GITUSERPASS).build()) {
                client.addAdminUser(KEYADMINUSER, null, new io.jitstatic.client.UserData(Set.of(new io.jitstatic.client.MetaData.Role("role")), "22"));
            }
        }).getStatusCode());
    }

    @Test
    public void testPostUserWithUser() throws URISyntaxException, IOException {
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(GITUSER).setPassword(GITUSERPASS).build()) {
            String version = client
                    .addAdminUser("newuser", null, new io.jitstatic.client.UserData(Set.of(new io.jitstatic.client.MetaData.Role("role")), "22"));
            try (JitStaticClient newClient = buildClient(DW.getLocalPort()).setUser("newuser").setPassword("22").build()) {
                Entity<io.jitstatic.api.UserData> user = newClient.getUser(KEYUSER, null, null, parse(io.jitstatic.api.UserData.class));
                assertNotNull(user.getData());
            }
            Entity<io.jitstatic.api.UserData> adminUser = client.getAdminUser("newuser", null, null, parse(io.jitstatic.api.UserData.class));
            assertEquals(version, adminUser.getTag());
        }
    }

    @Test
    public void testDeleteUserWithoutUser() {
        assertEquals(HttpStatus.UNAUTHORIZED_401, assertThrows(APIException.class, () -> {
            try (JitStaticClient client = buildClient(DW.getLocalPort()).build()) {
                client.deleteAdminUser(KEYADMINUSER, null);
            }
        }).getStatusCode());
    }

    @Test
    public void testDeleteUserWithWrongUser() {
        assertEquals(HttpStatus.UNAUTHORIZED_401, assertThrows(APIException.class, () -> {
            try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(KEYUSER).setPassword(KEYUSERPASS).build()) {
                client.deleteAdminUser(KEYADMINUSER, null);
            }
        }).getStatusCode());
    }

    @Test
    public void testDeleteUserNotFound() {
        assertEquals(HttpStatus.NOT_FOUND_404, assertThrows(APIException.class, () -> {
            try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(GITUSER).setPassword(GITUSERPASS).build()) {
                client.deleteAdminUser("nothing", null);
            }
        }).getStatusCode());
    }

    @Test
    public void testDeleteUser() throws URISyntaxException, IOException {
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(GITUSER).setPassword(GITUSERPASS).build()) {
            client.deleteAdminUser(KEYADMINUSER, null);
        }
        try (JitStaticClient newClient = buildClient(DW.getLocalPort()).setUser(KEYADMINUSER).setPassword(KEYADMINUSERPASS).build()) {
            assertEquals(HttpStatus.UNAUTHORIZED_401, assertThrows(APIException.class, () -> newClient
                    .getUser(KEYUSER, null, null, parse(io.jitstatic.api.UserData.class))).getStatusCode());
        }
    }

    @Test
    public void testGetKeyUser() throws URISyntaxException, IOException {
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(KEYADMINUSER).setPassword(KEYADMINUSERPASS).build()) {
            Entity<JsonNode> user = client.getUser(KEYUSER, null, null, parse(JsonNode.class));
            assertNotNull(user.getData());
            io.jitstatic.api.UserData value = MAPPER.treeToValue(user.getData(), io.jitstatic.api.UserData.class);
            assertEquals(keyUserUserData.getRoles(), value.getRoles());
            assertEquals(KEYUSERPASS, value.getBasicPassword());
            assertNotNull(user.getTag());
        }
    }

    @Test
    public void testGetKeyUserWithNoUser() {
        assertEquals(HttpStatus.UNAUTHORIZED_401, assertThrows(APIException.class, () -> {
            try (JitStaticClient client = buildClient(DW.getLocalPort()).build()) {
                client.getUser(KEYUSER, null, null, parse(JsonNode.class));
            }
        }).getStatusCode());
    }

    @Test
    public void testGetKeyUserWithWrongUser() {
        assertEquals(HttpStatus.FORBIDDEN_403, assertThrows(APIException.class, () -> {
            try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(KEYUSER).setPassword(KEYUSERPASS).build()) {
                client.getUser(KEYUSERNOROLE, null, null, parse(JsonNode.class));
            }
        }).getStatusCode());
    }

    @Test
    public void testGetKeyUserNotChanged() throws URISyntaxException, IOException {
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(KEYADMINUSER).setPassword(KEYADMINUSERPASS).build()) {
            Entity<JsonNode> user = client.getUser(KEYUSER, null, null, parse(JsonNode.class));
            assertNotNull(user.getData());
            assertNotNull(user.getTag());
            Entity<JsonNode> user2 = client.getUser(KEYUSER, null, user.getTag(), parse(JsonNode.class));
            assertNull(user2.getData());
            assertEquals(user.getTag(), user2.getTag());
        }
    }

    @Test
    public void testPutKeyUserWithNoUser() {
        assertEquals(HttpStatus.UNAUTHORIZED_401, assertThrows(APIException.class, () -> {
            try (JitStaticClient client = buildClient(DW.getLocalPort()).build()) {
                client.modifyUser(KEYUSER, null, new io.jitstatic.client.UserData(Set.of(new io.jitstatic.client.MetaData.Role("role")), "22"), "12345");
            }
        }).getStatusCode());
    }

    @Test
    public void testPutKeyUserWithUserButNotValid() {
        assertEquals(HttpStatus.FORBIDDEN_403, assertThrows(APIException.class, () -> {
            try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(KEYUSER).setPassword(KEYUSERPASS).build()) {
                client.modifyUser("someuser", null, new io.jitstatic.client.UserData(Set.of(new io.jitstatic.client.MetaData.Role("role")), "22"), "12345");
            }
        }).getStatusCode());
    }

    @Test
    public void testPutKeyUserWithUserChangePassword() throws URISyntaxException, IOException {
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(KEYUSER).setPassword(KEYUSERPASS).build()) {
            Entity<io.jitstatic.api.UserData> key = client.getUser(KEYUSER, null, null, parse(io.jitstatic.api.UserData.class));
            assertNotNull(client
                    .modifyUser(KEYUSER, null, new io.jitstatic.client.UserData(Set.of(new io.jitstatic.client.MetaData.Role("role")), "22"), key.getTag()));
        }
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(KEYUSER).setPassword("22").build()) {
            client.getUser(KEYUSER, null, null, parse(io.jitstatic.api.UserData.class));
        }
    }

    @Test
    public void testPutKeyUserWithUserButNotFound() {
        assertEquals(HttpStatus.NOT_FOUND_404, assertThrows(APIException.class, () -> {
            try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(KEYADMINUSER).setPassword(KEYADMINUSERPASS).build()) {
                client.modifyUser("noparse(JsonNode.class)ound", null, new io.jitstatic.client.UserData(Set
                        .of(new io.jitstatic.client.MetaData.Role("role")), "22"), "12345");
            }
        }).getStatusCode());
    }

    @Test
    public void testPutKeyUserWithUserButFoundWrongEtag() {
        assertEquals(HttpStatus.PRECONDITION_FAILED_412, assertThrows(APIException.class, () -> {
            try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(KEYADMINUSER).setPassword(KEYADMINUSERPASS).build()) {
                client.modifyUser(KEYUSER, null, new io.jitstatic.client.UserData(Set.of(new io.jitstatic.client.MetaData.Role("role")), "22"), "12345");
            }
        }).getStatusCode());
    }

    @Test
    public void testPutKeyUserWithUserButRemoved() {
        assertEquals(HttpStatus.NOT_FOUND_404, assertThrows(APIException.class, () -> {
            try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(KEYADMINUSER).setPassword(KEYADMINUSERPASS).build()) {
                client.deleteUser(KEYUSER, null);
                client.modifyUser(KEYUSER, null, new io.jitstatic.client.UserData(Set.of(new io.jitstatic.client.MetaData.Role("role")), "22"), "12345");
            }
        }).getStatusCode());
    }

    @Test
    public void testPutKeyUserWithUser() throws APIException, URISyntaxException, IOException {
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(KEYADMINUSER).setPassword(KEYADMINUSERPASS).build()) {
            Entity<io.jitstatic.api.UserData> entity = client.getUser(KEYUSER, null, null, parse(io.jitstatic.api.UserData.class));
            String version = client.modifyUser(KEYUSER, null, from(entity.getData().getRoles(), "22"), entity.getTag());
            entity = client.getUser(KEYUSER, null, null, parse(io.jitstatic.api.UserData.class));
            assertEquals(version, entity.getTag());
        }
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(KEYUSER).setPassword("22").build()) {
            assertNotNull(client.getKey("file", parse(JsonNode.class)));
        }
    }

    @Test
    public void testPostKeyUserWithNoUser() {
        assertEquals(HttpStatus.UNAUTHORIZED_401, assertThrows(APIException.class, () -> {
            try (JitStaticClient client = buildClient(DW.getLocalPort()).build()) {
                client.addUser(KEYUSER, null, new io.jitstatic.client.UserData(Set.of(new io.jitstatic.client.MetaData.Role("role")), "22"));
            }
        }).getStatusCode());
    }

    @Test
    public void testPostKeyUserWithWrongUser() {
        assertEquals(HttpStatus.FORBIDDEN_403, assertThrows(APIException.class, () -> {
            try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(KEYUSERNOROLE).setPassword(KEYUSERNOROLEPASS).build()) {
                client.addUser(KEYUSER, null, new io.jitstatic.client.UserData(Set.of(new io.jitstatic.client.MetaData.Role("role")), "22"));
            }
        }).getStatusCode());
    }

    @Test
    public void testPostKeyUserWithUserButExist() {
        assertEquals(HttpStatus.CONFLICT_409, assertThrows(APIException.class, () -> {
            try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(KEYADMINUSER).setPassword(KEYADMINUSERPASS).build()) {
                client.addUser(KEYUSER, null, new io.jitstatic.client.UserData(Set.of(new io.jitstatic.client.MetaData.Role("role")), "22"));
            }
        }).getStatusCode());
    }

    @Test
    public void testPostKeyUserWithUser() throws APIException, URISyntaxException, IOException {
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(KEYADMINUSER).setPassword(KEYADMINUSERPASS).build()) {
            String version = client.addUser("newuser", null, new io.jitstatic.client.UserData(Set.of(new io.jitstatic.client.MetaData.Role("role")), "22"));
            try (JitStaticClient newClient = buildClient(DW.getLocalPort()).setUser("newuser").setPassword("22").build()) {
                Entity<JsonNode> data = newClient.getKey("file", parse(JsonNode.class));
                assertNotNull(data.getData());
            }
            Entity<io.jitstatic.api.UserData> user = client.getUser("newuser", null, null, parse(io.jitstatic.api.UserData.class));
            assertEquals(version, user.getTag());
        }
    }

    @Test
    public void testDeleteKeyUserWithoutUser() {
        assertEquals(HttpStatus.UNAUTHORIZED_401, assertThrows(APIException.class, () -> {
            try (JitStaticClient client = buildClient(DW.getLocalPort()).build()) {
                client.deleteUser(KEYUSER, null);
            }
        }).getStatusCode());
    }

    @Test
    public void testDeleteKeyUserWithWrongUser() {
        assertEquals(HttpStatus.FORBIDDEN_403, assertThrows(APIException.class, () -> {
            try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(KEYUSERNOROLE).setPassword(KEYUSERNOROLEPASS).build()) {
                client.deleteUser(KEYUSER, null);
            }
        }).getStatusCode());
    }

    @Test
    public void testDeleteKeyUserNotFound() {
        assertEquals(HttpStatus.NOT_FOUND_404, assertThrows(APIException.class, () -> {
            try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(KEYADMINUSER).setPassword(KEYADMINUSERPASS).build()) {
                client.deleteUser("noparse(JsonNode.class)ound", null);
            }
        }).getStatusCode());
    }

    @Test
    public void testDeleteKeyUser() throws URISyntaxException, IOException {
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(KEYADMINUSER).setPassword(KEYADMINUSERPASS).build()) {
            client.deleteUser(KEYUSER, null);
        }
        try (JitStaticClient newClient = buildClient(DW.getLocalPort()).setUser(KEYUSER).setPassword(KEYUSER).build()) {
            assertEquals(HttpStatus.UNAUTHORIZED_401, assertThrows(APIException.class, () -> newClient.getKey("file", parse(io.jitstatic.api.UserData.class)))
                    .getStatusCode());
        }
    }

    @Test
    public void testUpdateUserWithPassword() throws Exception {
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(KEYADMINUSER).setPassword(KEYADMINUSERPASS).build();
                JitStaticClient keyUserClient = buildClient(DW.getLocalPort()).setUser(KEYUSER).setPassword(KEYUSERPASS).build()) {
            client.createKey(getData(2).getBytes(UTF_8), new CommitData("file2", "msg", "ui", "mail"), new io.jitstatic.client.MetaData("application/json", Set
                    .of(new MetaData.Role("added")), Set.of()));
            assertEquals(HttpStatus.FORBIDDEN_403, assertThrows(APIException.class, () -> keyUserClient.getKey("file2", parse(JsonNode.class)))
                    .getStatusCode());

            Entity<io.jitstatic.api.UserData> user = client.getUser(KEYUSER, null, null, parse(io.jitstatic.api.UserData.class));
            assertTrue(user.getData().getBasicPassword().equals(KEYUSERPASS));
            Set<MetaData.Role> currentRoles = user.getData().getRoles().stream().map(r -> new MetaData.Role(r.getRole()))
                    .collect(Collectors.toSet());
            currentRoles.add(new MetaData.Role("added"));
            String version = client.modifyUser(KEYUSER, null, new io.jitstatic.client.UserData(currentRoles, null), user.getTag());
            user = client.getUser(KEYUSER, null, null, parse(io.jitstatic.api.UserData.class));
            assertEquals(version, user.getTag());
            assertEquals(KEYUSERPASS, user.getData().getBasicPassword());

            Set<MetaData.Role> updatedRoles = user.getData().getRoles().stream().map(r -> new MetaData.Role(r.getRole()))
                    .collect(Collectors.toSet());
            assertEquals(currentRoles, updatedRoles);
            assertNotNull(keyUserClient.getKey("file2", parse(JsonNode.class)));
            version = client.modifyUser(KEYUSER, null, new io.jitstatic.client.UserData(currentRoles, "1234"), user.getTag());
            assertEquals(HttpStatus.UNAUTHORIZED_401, assertThrows(APIException.class, () -> keyUserClient.getKey("file2", parse(JsonNode.class)))
                    .getStatusCode());
        }
    }

    @Test
    public void testUpdateUserShouldHaveNotHashedPassword() throws Exception {
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(KEYADMINUSER).setPassword(KEYADMINUSERPASS).build();) {
            Entity<io.jitstatic.api.UserData> user = client.getUser(KEYUSER, null, null, parse(io.jitstatic.api.UserData.class));
            client.modifyUser(KEYUSER, null, new io.jitstatic.client.UserData(Set.of(new MetaData.Role("role"))), user.getTag());
            assertEquals(422, assertThrows(APIException.class, () -> client
                    .addUser("someuser", null, new io.jitstatic.client.UserData(Set.of(new MetaData.Role("role"))))).getStatusCode());
            client.addUser("someuser", null, new io.jitstatic.client.UserData(Set.of(new MetaData.Role("role")), "pass"));
        }
        Path workingFolder = getFolderFile().toPath();
        UsernamePasswordCredentialsProvider provider = new UsernamePasswordCredentialsProvider(GITUSER, GITUSERPASS);
        try (Git git = Git.cloneRepository().setDirectory(workingFolder.toFile()).setURI(gitAdress).setCredentialsProvider(provider).call()) {
            UserData value = MAPPER.readValue(workingFolder.resolve(USERS).resolve(JITSTATIC_KEYUSER_REALM).resolve(KEYUSER).toFile(), UserData.class);
            assertNull(value.getHash());
            assertNull(value.getSalt());
            assertNotNull(value.getBasicPassword());
            value = MAPPER.readValue(workingFolder.resolve(USERS).resolve(JITSTATIC_KEYUSER_REALM).resolve("someuser").toFile(), UserData.class);
            assertNotNull(value.getHash());
            assertNotNull(value.getSalt());
            assertNull(value.getBasicPassword());
        }
    }

    @Test
    public void testUserWithNewHashedPassword() throws Exception {
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(KEYADMINUSER).setPassword(KEYADMINUSERPASS).build();) {
            Entity<io.jitstatic.api.UserData> user = client.getUser(KEYUSER, null, null, parse(io.jitstatic.api.UserData.class));
            client.modifyUser(KEYUSER, null, new io.jitstatic.client.UserData(Set.of(new MetaData.Role("role")), "newpass"), user.getTag());
        }
        Path workingFolder = getFolderFile().toPath();
        UsernamePasswordCredentialsProvider provider = new UsernamePasswordCredentialsProvider(GITUSER, GITUSERPASS);
        try (Git git = Git.cloneRepository().setDirectory(workingFolder.toFile()).setURI(gitAdress).setCredentialsProvider(provider).call()) {
            UserData value = MAPPER.readValue(workingFolder.resolve(USERS).resolve(JITSTATIC_KEYUSER_REALM).resolve(KEYUSER).toFile(), UserData.class);
            assertNotNull(value.getHash());
            assertNotNull(value.getSalt());
            assertNull(value.getBasicPassword());
        }
    }

    @Test
    public void testGetGitUser() throws Exception {
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(GITUSERFULL).setPassword(GITUSERFULLPASS).build();) {
            final Entity<io.jitstatic.api.UserData> node = client.getGitUser(GITUSERFULL, parse(io.jitstatic.api.UserData.class));
            assertNotNull(node);
            assertNotNull(node.getData().getBasicPassword()); // Old style, deprecated
            assertEquals(ALL_ROLES, node.getData().getRoles());
        }
    }

    @Test
    public void testAddGitUser() throws Exception {
        String addedgituser = "gitadduser";
        String addeduserpass = "1111";
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(GITUSERFULL).setPassword(GITUSERFULLPASS).build();) {
            String version = client.addGitUser(addedgituser, new io.jitstatic.client.UserData(Set
                    .of(new MetaData.Role(PULL), new MetaData.Role(PUSH), new MetaData.Role(FORCEPUSH), new MetaData.Role(CREATE), new MetaData.Role(SECRETS)), addeduserpass));
            assertNotNull(version);
        }
        Path workingFolder = getFolderFile().toPath();
        UsernamePasswordCredentialsProvider provider = new UsernamePasswordCredentialsProvider(addedgituser, addeduserpass);
        try (Git git = Git.cloneRepository().setDirectory(workingFolder.toFile()).setURI(gitAdress).setCredentialsProvider(provider).call()) {
            assertNotNull(git.checkout().setCreateBranch(true).setName("secrets").setUpstreamMode(SetupUpstreamMode.TRACK).setStartPoint("origin/" + "secrets")
                    .call());
            UserData value = MAPPER.readValue(workingFolder.resolve(USERS).resolve(GIT_REALM).resolve(addedgituser).toFile(), UserData.class);
            assertNotNull(value.getHash());
            assertNotNull(value.getSalt());
            assertNull(value.getBasicPassword());
        }
    }

    @Test
    public void testAddGitUserWithWrongRole() throws Exception {
        String addedgituser = "gitadduser";
        String addeduserpass = "1111";
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(GITUSERFULL).setPassword(GITUSERFULLPASS).build();) {
            APIException apiException = assertThrows(APIException.class, () -> client.addGitUser(addedgituser, new io.jitstatic.client.UserData(Set
                    .of(new MetaData.Role("PALL"), new MetaData.Role(PUSH), new MetaData.Role(FORCEPUSH), new MetaData.Role(CREATE), new MetaData.Role(SECRETS)), addeduserpass)));
            assertEquals("POST http://localhost:" + DW.getLocalPort()
                    + "/application/storage/ failed with: 422  {\"errors\":[\"roles Contains not valid git roles\"]}", apiException.getMessage());
            assertEquals(HttpStatus.UNPROCESSABLE_ENTITY_422, apiException.getStatusCode());

        }
    }

    @Test
    public void testDeleteGitUser() throws Exception {
        String addedgituser = "gitadduser";
        String addeduserpass = "1111";
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(GITUSERFULL).setPassword(GITUSERFULLPASS).build();) {
            String version = client.addGitUser(addedgituser, new io.jitstatic.client.UserData(Set
                    .of(new MetaData.Role(PULL), new MetaData.Role(PUSH), new MetaData.Role(FORCEPUSH), new MetaData.Role(CREATE), new MetaData.Role(SECRETS)), addeduserpass));
            assertNotNull(version);
        }
        Path workingFolder = getFolderFile().toPath();
        UsernamePasswordCredentialsProvider provider = new UsernamePasswordCredentialsProvider(addedgituser, addeduserpass);
        try (Git git = Git.cloneRepository().setDirectory(workingFolder.toFile()).setURI(gitAdress).setCredentialsProvider(provider).call()) {
            assertNotNull(git.checkout().setCreateBranch(true).setName("secrets").setUpstreamMode(SetupUpstreamMode.TRACK).setStartPoint("origin/" + "secrets")
                    .call());
            UserData value = MAPPER.readValue(workingFolder.resolve(USERS).resolve(GIT_REALM).resolve(addedgituser).toFile(), UserData.class);
            assertNotNull(value.getHash());
            assertNotNull(value.getSalt());
            assertNull(value.getBasicPassword());
        }
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(GITUSERFULL).setPassword(GITUSERFULLPASS).build();) {
            client.deleteGitUser(addedgituser);
            assertEquals(HttpStatus.NOT_FOUND_404, assertThrows(APIException.class, () -> client
                    .getGitUser(addedgituser, parse(io.jitstatic.api.UserData.class))).getStatusCode());
        }
    }

    @Test
    public void testSameUserAsRoot() throws Exception {
        String addeduser = "huser";
        String addeduserpass = "1111";
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(KEYADMINUSER).setPassword(KEYADMINUSERPASS).build();) {
            assertEquals(HttpStatus.CONFLICT_409, assertThrows(APIException.class, () -> client.addUser(addeduser, null, new io.jitstatic.client.UserData(Set
                    .of(new MetaData.Role(PULL), new MetaData.Role(PUSH), new MetaData.Role(FORCEPUSH), new MetaData.Role(CREATE), new MetaData.Role(SECRETS)), addeduserpass)))
                            .getStatusCode());
        }
    }

    @Test
    public void testEmailAsUserName() throws Exception {
        String userEmail = "user@host.com";
        String userEmailPass = "1111";
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(KEYADMINUSER).setPassword(KEYADMINUSERPASS).build();) {
            String version = client.addUser(userEmail, null, new io.jitstatic.client.UserData(Set
                    .of(new MetaData.Role("role"), new MetaData.Role(PULL), new MetaData.Role(PUSH), new MetaData.Role(FORCEPUSH), new MetaData.Role(CREATE), new MetaData.Role(SECRETS)), userEmailPass));
            assertNotNull(version);
        }
        Path workingFolder = getFolderFile().toPath();
        UsernamePasswordCredentialsProvider provider = new UsernamePasswordCredentialsProvider(GITUSERFULL, GITUSERFULLPASS);
        try (Git git = Git.cloneRepository().setDirectory(workingFolder.toFile()).setURI(gitAdress).setCredentialsProvider(provider).call()) {
            UserData value = MAPPER.readValue(workingFolder.resolve(USERS).resolve(JITSTATIC_KEYUSER_REALM).resolve(userEmail).toFile(), UserData.class);
            assertNotNull(value.getHash());
            assertNotNull(value.getSalt());
            assertNull(value.getBasicPassword());

        }
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(KEYADMINUSER).setPassword(KEYADMINUSERPASS).build();) {
            Entity<io.jitstatic.api.UserData> gu = client.getUser(userEmail, null, null, parse(io.jitstatic.api.UserData.class));
            assertNotNull(gu);
            assertNotNull(gu.getData().getRoles());
            assertNull(gu.getData().getBasicPassword());
        }
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(userEmail).setPassword("1111").build();) {
            Entity<JsonNode> gu = client.getKey("file", parse(JsonNode.class));
            assertNotNull(gu);
            assertNotNull(gu.getData().toString());
        }
    }

    @Test
    public void testUserWithBothPasswordAnd() throws Exception {
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(KEYADMINUSER).setPassword(KEYADMINUSERPASS).build();) {
            Entity<io.jitstatic.api.UserData> user = client.getUser(KEYUSER, "refs/heads/master", null, parse(io.jitstatic.api.UserData.class));
            client.modifyUser(KEYUSER, "refs/heads/master", from(user.getData().getRoles(), "111"), user.getTag());
        }

        Path workingFolder = getFolderFile().toPath();
        UsernamePasswordCredentialsProvider provider = new UsernamePasswordCredentialsProvider(GITUSERPUSH, GITUSERPUSHPASS);
        try (Git git = Git.cloneRepository().setDirectory(workingFolder.toFile()).setURI(gitAdress).setCredentialsProvider(provider).call()) {

            Path users = workingFolder.resolve(USERS);
            Path keyUserRealm = users.resolve(JITSTATIC_KEYUSER_REALM);

            Path keyUserNoPush = keyUserRealm.resolve(KEYUSER);
            UserData userData = MAPPER.readValue(keyUserNoPush.toFile(), UserData.class);
            UserData modified = new UserData(userData.getRoles(), "222", userData.getSalt(), userData.getHash());
            Files.write(MAPPER.writeValueAsBytes(modified), keyUserNoPush.toFile());
            commit(git, provider);
        }
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(KEYUSER).setPassword("111").build();) {
            Entity<JsonNode> user = client.getKey("file", "refs/heads/master", null, parse(JsonNode.class));
            assertTrue(user != null);
        }
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(KEYUSER).setPassword("222").build();) {
            assertThrows(APIException.class, () -> client.getKey("file", "refs/heads/master", null, parse(JsonNode.class)));
        }
    }

    private static String getMetaData(io.jitstatic.MetaData metaData) throws JsonProcessingException {
        return MAPPER.writeValueAsString(metaData);
    }

    protected File getFolderFile() throws IOException { return tmpFolder.createTemporaryDirectory(); }

    private void commit(Git git, UsernamePasswordCredentialsProvider provider, String ref) throws NoFilepatternException, GitAPIException {
        git.checkout().setName(ref).setCreateBranch(true).call();
        commit(git, provider);
    }

    private static io.jitstatic.client.UserData from(Set<Role> roles, String pass) {
        Set<io.jitstatic.client.MetaData.Role> clientRoles = roles.stream().map(r -> r.getRole()).map(io.jitstatic.client.MetaData.Role::new)
                .collect(Collectors.toSet());
        return new io.jitstatic.client.UserData(clientRoles, pass);
    }
}
