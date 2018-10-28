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

import static io.jitstatic.JitStaticConstants.FORCEPUSH;
import static io.jitstatic.JitStaticConstants.GIT_REALM;
import static io.jitstatic.JitStaticConstants.JITSTATIC_KEYUSER_REALM;
import static io.jitstatic.JitStaticConstants.JITSTATIC_KEYADMIN_REALM;
import static io.jitstatic.JitStaticConstants.PULL;
import static io.jitstatic.JitStaticConstants.PUSH;
import static io.jitstatic.JitStaticConstants.SECRETS;
import static io.jitstatic.JitStaticConstants.USERS;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.io.Files;

import io.dropwizard.testing.ConfigOverride;
import io.dropwizard.testing.ResourceHelpers;
import io.dropwizard.testing.junit5.DropwizardAppExtension;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.jitstatic.api.KeyData;
import io.jitstatic.api.SearchResult;
import io.jitstatic.auth.UserData;
import io.jitstatic.client.APIException;
import io.jitstatic.client.BulkSearch;
import io.jitstatic.client.CommitData;
import io.jitstatic.client.JitStaticClient;
import io.jitstatic.client.JitStaticClientBuilder;
import io.jitstatic.client.MetaData;
import io.jitstatic.client.ModifyUserKeyData;
import io.jitstatic.client.SearchPath;
import io.jitstatic.client.TriFunction;
import io.jitstatic.hosted.HostedFactory;
import io.jitstatic.test.TemporaryFolder;
import io.jitstatic.test.TemporaryFolderExtension;

@ExtendWith({ TemporaryFolderExtension.class, DropwizardExtensionsSupport.class })
public class UserManagementTest {

    private static final String ALLFILESPATTERN = ".";
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
    private TemporaryFolder tmpFolder;
    private String rootUser;
    private String rootPassword;

    private DropwizardAppExtension<JitstaticConfiguration> DW = new DropwizardAppExtension<>(JitstaticApplication.class,
            ResourceHelpers.resourceFilePath("simpleserver.yaml"), ConfigOverride.config("hosted.basePath", getFolderString()));
    private String adress;
    private String gitAdress;

    @BeforeEach
    public void setup() throws Exception {
        HostedFactory hostedFactory = DW.getConfiguration().getHostedFactory();
        adress = String.format("http://localhost:%d/application", DW.getLocalPort());
        rootUser = hostedFactory.getUserName();
        rootPassword = hostedFactory.getSecret();
        Path workingFolder = getFolder();
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
            UserData keyAdminUserData = new UserData(Set.of(new Role("read"), new Role("write")), KEYADMINUSERPASS);
            UserData keyUserUserData = new UserData(Set.of(new Role("role")), KEYUSERPASS);
            UserData keyUserUserDataNoRole = new UserData(Set.of(), KEYUSERNOROLEPASS);
            Files.write(MAPPER.writeValueAsBytes(keyAdminUserData), keyAdminUser.toFile());
            Files.write(MAPPER.writeValueAsBytes(keyUserUserData), keyuser.toFile());
            Files.write(MAPPER.writeValueAsBytes(keyUserUserDataNoRole), keyusernorole.toFile());
            Files.write(getData().getBytes(UTF_8), workingFolder.resolve("file").toFile());
            Files.write(getMetaData(new MetaData(Set.of(), JitStaticConstants.APPLICATION_JSON, false, false, List.of(), Set.of(new MetaData.Role("role")),
                    Set.of(new MetaData.Role("role")))).getBytes(UTF_8), workingFolder.resolve("file.metadata").toFile());
            commit(git, provider);
            Path gitRealm = users.resolve(GIT_REALM);
            mkdirs(gitRealm);
            Path gitUser = gitRealm.resolve(GITUSERFULL);
            Path gitUserPush = gitRealm.resolve(GITUSERPUSH);
            Path gitUserNoPush = gitRealm.resolve(GITUSER);
            UserData gitUserData = new UserData(Set.of(new Role(PULL), new Role(PUSH), new Role(FORCEPUSH), new Role(SECRETS)), GITUSERFULLPASS);
            UserData gitUserDataNoPush = new UserData(Set.of(new Role(PULL)), GITUSERPASS);
            UserData gitUserDataPush = new UserData(Set.of(new Role(PULL), new Role(PUSH)), GITUSERPUSHPASS);
            Files.write(MAPPER.writeValueAsBytes(gitUserData), gitUser.toFile());
            Files.write(MAPPER.writeValueAsBytes(gitUserDataPush), gitUserPush.toFile());
            Files.write(MAPPER.writeValueAsBytes(gitUserDataNoPush), gitUserNoPush.toFile());
            commit(git, provider, SECRETS);
        }
    }

    @Test
    public void testNoUser() {
        assertTrue(assertThrows(TransportException.class, () -> {
            try (Git git = Git.cloneRepository().setDirectory(getFolder().toFile()).setURI(gitAdress).call()) {
            }
        }).getMessage().contains("Authentication is required but no CredentialsProvider has been registered"));
    }

    @Test
    public void testGitRealmUserPullPush() throws IOException, InvalidRemoteException, TransportException, GitAPIException {
        Path workingFolder = getFolder();
        UsernamePasswordCredentialsProvider provider = new UsernamePasswordCredentialsProvider(GITUSERFULL, GITUSERFULLPASS);
        try (Git git = Git.cloneRepository().setDirectory(workingFolder.toFile()).setURI(gitAdress).setCredentialsProvider(provider).call()) {
            Files.write(getData(1).getBytes(UTF_8), workingFolder.resolve("file").toFile());
            commit(git, provider);
        }
    }

    @Test
    public void testGitRealmUserPullNoPush() throws IOException, InvalidRemoteException, TransportException, GitAPIException {
        Path workingFolder = getFolder();
        UsernamePasswordCredentialsProvider provider = new UsernamePasswordCredentialsProvider(GITUSER, GITUSERPASS);
        try (Git git = Git.cloneRepository().setDirectory(workingFolder.toFile()).setURI(gitAdress).setCredentialsProvider(provider).call()) {
            Files.write(getData(1).getBytes(UTF_8), workingFolder.resolve("file").toFile());
            assertTrue(assertThrows(TransportException.class, () -> commit(git, provider)).getMessage().contains("authentication not supported"));
        }
    }

    @Test
    public void testKeyAdminAddKey() throws URISyntaxException, ClientProtocolException, APIException, IOException {
        try (JitStaticClient creator = buildClient().setPassword(KEYADMINUSERPASS).setUser(KEYADMINUSER).build()) {
            creator.createKey(getData(2).getBytes(UTF_8), new CommitData("file2", "msg", "ui", "mail"),
                    new io.jitstatic.client.MetaData(Set.of(), "application/json", List.of()));
        }
        try (JitStaticClient creator = buildClient().setPassword(KEYADMINUSERPASS).setUser(KEYADMINUSER).build()) {
            creator.createKey(getData(2).getBytes(UTF_8), new CommitData("file4", "master", "msg", "ui", "mail"),
                    new io.jitstatic.client.MetaData(Set.of(), "application/json", List.of()));
        }
        assertEquals(HttpStatus.FORBIDDEN_403, assertThrows(APIException.class, () -> {
            try (JitStaticClient creator = buildClient().setPassword(KEYADMINUSERPASS).setUser("otheruser").build()) {
                creator.createKey(getData(2).getBytes(UTF_8), new CommitData("file3", "master", "msg", "ui", "mail"),
                        new io.jitstatic.client.MetaData(Set.of(), "application/json", List.of()));
            }
        }).getStatusCode());
        assertEquals(HttpStatus.CONFLICT_409, assertThrows(APIException.class, () -> {
            try (JitStaticClient creator = buildClient().setPassword(KEYADMINUSERPASS).setUser(KEYADMINUSER).build()) {
                creator.createKey(getData(2).getBytes(UTF_8), new CommitData("file2", "master", "msg", "ui", "mail"),
                        new io.jitstatic.client.MetaData(Set.of(), "application/json", List.of()));
            }
        }).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN_403, assertThrows(APIException.class, () -> {
            try (JitStaticClient creator = buildClient().setPassword(KEYADMINUSERPASS).setUser("otheruser").build()) {
                creator.createKey(getData(2).getBytes(UTF_8), new CommitData("file2", "master", "msg", "ui", "mail"),
                        new io.jitstatic.client.MetaData(Set.of(), "application/json", List.of()));
            }
        }).getStatusCode());
    }

    @Test
    public void testKeyAdminModifyAnyKey() throws Exception {
        String version;
        try (JitStaticClient creator = buildClient().setPassword(KEYADMINUSERPASS).setUser(KEYADMINUSER).build()) {
            version = creator.createKey(getData(2).getBytes(UTF_8), new CommitData("file2", "master", "msg", "ui", "mail"),
                    new io.jitstatic.client.MetaData(Set.of(), "application/json", List.of()));
        }
        try (JitStaticClient creator = buildClient().setPassword(KEYADMINUSERPASS).setUser(KEYADMINUSER).build()) {
            String modifiedVersion = creator.modifyKey(getData(3).getBytes(UTF_8), new CommitData("file2", "master", "msg", "ui", "mail"), version);
            assertNotEquals(version, modifiedVersion);
        }
    }

    @Test
    public void testKeyUserCreateKey() throws Exception {
        String version;
        assertEquals(HttpStatus.FORBIDDEN_403, assertThrows(APIException.class, () -> {
            try (JitStaticClient creator = buildClient().setPassword("wrong").setUser(KEYUSER).build()) {
                creator.createKey(getData(2).getBytes(UTF_8), new CommitData("file2", "master", "msg", "ui", "mail"),
                        new io.jitstatic.client.MetaData(Set.of(), "application/json", List.of()));
            }
        }).getStatusCode());
        try (JitStaticClient creator = buildClient().setPassword(KEYUSERPASS).setUser(KEYUSER).build()) {
            Set<io.jitstatic.client.MetaData.Role> roles = Set.of(new io.jitstatic.client.MetaData.Role("role"));
            version = creator.createKey(getData(3).getBytes(UTF_8), new CommitData("file2", "master", "msg", "ui", "mail"),
                    new io.jitstatic.client.MetaData(Set.of(), "application/json", roles, roles));
        }
        try (JitStaticClient updater = buildClient().setPassword(KEYADMINUSERPASS).setUser(KEYADMINUSER).build()) {
            Entity<JsonNode> entity = updater.getKey("file2", tf, version);
            assertEquals(version, entity.tag);
        }
        assertEquals(HttpStatus.FORBIDDEN_403, assertThrows(APIException.class, () -> {
            try (JitStaticClient updater = buildClient().setPassword("2222").setUser(KEYUSER).build()) {
                updater.getKey("file2", tf);
            }
        }).getStatusCode());

        try (JitStaticClient updater = buildClient().setPassword(KEYADMINUSERPASS).setUser(KEYADMINUSER).build()) {
            Entity<JsonNode> entity = updater.getKey("file2", tf);
            assertEquals(version, entity.tag);
        }

        assertEquals(HttpStatus.FORBIDDEN_403, assertThrows(APIException.class, () -> {
            try (JitStaticClient updater = buildClient().setPassword(KEYUSERNOROLEPASS).setUser(KEYUSERNOROLE).build()) {
                updater.getKey("file2", tf);
            }
        }).getStatusCode());
    }

    @Test
    public void testKeyUserModifyMetaData() throws Exception {
        String version;
        try (JitStaticClient creator = buildClient().setPassword(KEYUSERPASS).setUser(KEYUSER).build()) {
            Set<io.jitstatic.client.MetaData.Role> roles = Set.of(new io.jitstatic.client.MetaData.Role("role"));
            creator.createKey(getData(2).getBytes(UTF_8), new CommitData("file2", "master", "msg", "ui", "mail"),
                    new io.jitstatic.client.MetaData(Set.of(), "application/json", roles, roles));
        }
        try (JitStaticClient creator = buildClient().setPassword(KEYUSERPASS).setUser(KEYUSER).build()) {
            Entity<JsonNode> metaKey = creator.getMetaKey("file2", "master", tf);
            version = metaKey.tag;
        }
        try (JitStaticClient creator = buildClient().setPassword(KEYUSERPASS).setUser(KEYUSER).build()) {
            Set<io.jitstatic.client.MetaData.Role> roles = Set.of(new io.jitstatic.client.MetaData.Role("role"), new io.jitstatic.client.MetaData.Role("new"));
            version = creator.modifyMetaKey("file2", "master", version,
                    new ModifyUserKeyData(new io.jitstatic.client.MetaData(Set.of(), "application/json", roles, roles), "msg", "mail", "ui"));
        }

        try (JitStaticClient creator = buildClient().setPassword(KEYADMINUSERPASS).setUser(KEYADMINUSER).build()) {
            Entity<JsonNode> metaKey = creator.getMetaKey("file2", "master", tf);
            assertEquals(version, metaKey.tag);
        }

        assertEquals(HttpStatus.FORBIDDEN_403, assertThrows(APIException.class, () -> {
            try (JitStaticClient creator = buildClient().setPassword(KEYUSERPASS).setUser(KEYUSER).build()) {
                creator.getMetaKey("file2", "master", tf);
            }
        }).getStatusCode());
    }

    @Test
    public void testListFiles() throws Exception {
        int i = 0;
        try (JitStaticClient creator = buildClient().setPassword(KEYUSERPASS).setUser(KEYUSER).build()) {
            for (String file : List.of("path/file", "path/path/file", "file3")) {
                Set<io.jitstatic.client.MetaData.Role> roles = Set.of(new io.jitstatic.client.MetaData.Role("role"));
                creator.createKey(getData(i).getBytes(UTF_8), new CommitData(file, "master", "msg", "ui", "mail"),
                        new io.jitstatic.client.MetaData(Set.of(), "application/json", roles, roles));
                i++;
            }
        }
        try (JitStaticClient creator = buildClient().setPassword(KEYUSERPASS).setUser(KEYUSER).build()) {
            i = 0;
            for (String file : List.of("path/file2", "path/path/file2", "file2")) {
                Set<io.jitstatic.client.MetaData.Role> roles = Set.of(new io.jitstatic.client.MetaData.Role("other"));
                creator.createKey(getData(i).getBytes(UTF_8), new CommitData(file, "master", "msg", "ui", "mail"),
                        new io.jitstatic.client.MetaData(Set.of(), "application/json", roles, roles));
                i++;
            }
        }
        try (JitStaticClient creator = buildClient().setPassword(KEYUSERPASS).setUser(KEYUSER).build()) {
            Set<KeyData> all = creator.listAll("path/", true, (input) -> {
                try {
                    // If extracted to read() method, below type in TypeReference is null.
                    return MAPPER.readValue(input, new TypeReference<Set<KeyData>>() {
                    });
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            Set<String> files = all.stream().map(KeyData::getKey).collect(Collectors.toSet());
            assertEquals(Set.of("path/file", "path/path/file"), files);
        }
    }

    @Test
    public void testBulkSearch() throws Exception {
        int i = 0;
        try (JitStaticClient creator = buildClient().setPassword(KEYUSERPASS).setUser(KEYUSER).build()) {
            for (String file : List.of("path/file", "path/path/file", "file3")) {
                Set<io.jitstatic.client.MetaData.Role> roles = Set.of(new io.jitstatic.client.MetaData.Role("role"));
                creator.createKey(getData(i).getBytes(UTF_8), new CommitData(file, "master", "msg", "ui", "mail"),
                        new io.jitstatic.client.MetaData(Set.of(), "application/json", roles, roles));
                i++;
            }
        }
        try (JitStaticClient creator = buildClient().setPassword(KEYUSERPASS).setUser(KEYUSER).build()) {
            i = 0;
            for (String file : List.of("path/file2", "path/path/file2", "file2")) {
                Set<io.jitstatic.client.MetaData.Role> roles = Set.of(new io.jitstatic.client.MetaData.Role("other"));
                creator.createKey(getData(i).getBytes(UTF_8), new CommitData(file, "master", "msg", "ui", "mail"),
                        new io.jitstatic.client.MetaData(Set.of(), "application/json", roles, roles));
                i++;
            }
        }
        try (JitStaticClient creator = buildClient().setPassword(KEYUSERPASS).setUser(KEYUSER).build()) {
            Set<SearchResult> search = creator.search(List.of(new BulkSearch("refs/heads/master", List.of(new SearchPath("path/", true)))), (input) -> {
                try {
                    // If extracted to a read() method, below type in TypeReference is null.
                    return MAPPER.readValue(input, new TypeReference<Set<SearchResult>>() {
                    });
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            Set<String> keys = search.stream().map(SearchResult::getKey).collect(Collectors.toSet());
            assertEquals(Set.of("path/file", "path/path/file"), keys);
        }
    }

    @Test
    public void testGitUserCantPullSecretsBranch() throws Exception {
        Path workingFolder = getFolder();
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

            UserData gitUserDataNoPush = new UserData(Set.of(new Role(PULL), new Role(SECRETS)), GITUSERPASS);
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
        Path workingFolder = getFolder();
        UsernamePasswordCredentialsProvider provider = new UsernamePasswordCredentialsProvider(GITUSERFULL, GITUSERFULLPASS);
        try (Git git = Git.cloneRepository().setDirectory(workingFolder.toFile()).setURI(gitAdress).setCredentialsProvider(provider).call()) {
            List<Ref> call = git.branchList().setListMode(ListMode.REMOTE).call();
            assertTrue(call.stream().anyMatch(r -> r.getName().equals(REFS_REMOTES_ORIGIN + SECRETS)));

            assertNotNull(
                    git.checkout().setStartPoint("origin/" + SECRETS).setCreateBranch(true).setUpstreamMode(SetupUpstreamMode.TRACK).setName(SECRETS).call());

            Path users = workingFolder.resolve(USERS);
            Path gitRealm = users.resolve(GIT_REALM);
            Path gitUserNoPush = gitRealm.resolve(GITUSER);

            UserData gitUserDataNoPush = new UserData(Set.of(new Role("pull"), new Role(PUSH)), GITUSERPASS);
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
        try (JitStaticClient client = buildClient().setUser(KEYUSER).setPassword(KEYUSERPASS).build(); JitStaticClient noUser = buildClient().build();) {
            String key = "key";
            String createKey = client.createKey(getData().getBytes(UTF_8), new CommitData(key, "msg", "info", "mail"),
                    new MetaData("application/json", Set.of(), Set.of(new MetaData.Role("role"))));
            Entity<JsonNode> key2 = noUser.getKey(key, tf);
            String tag = key2.tag;
            assertEquals(createKey, tag);
            assertEquals(HttpStatus.UNAUTHORIZED_401,
                    assertThrows(APIException.class, () -> noUser.modifyKey(getData(4).getBytes(UTF_8), new CommitData(key, "msg", "info", "mail"), tag))
                            .getStatusCode());
            String modifyKey = client.modifyKey(getData(5).getBytes(UTF_8), new CommitData(key, "msg", "info", "mail"), key2.tag);
            key2 = noUser.getKey(key, tf);
            assertEquals(modifyKey, key2.tag);
            Entity<JsonNode> metaKey = client.getMetaKey(key, "master", tf);
            MetaData newMetaData = new MetaData("application/json", Set.of(new MetaData.Role("role")), Set.of(new MetaData.Role("role")));
            client.modifyMetaKey(key, "master", metaKey.tag, new ModifyUserKeyData(newMetaData, "msg", "mail", "info"));
            assertEquals(HttpStatus.UNAUTHORIZED_401, assertThrows(APIException.class, () -> noUser.getKey(key, tf)).getStatusCode());
            assertEquals(HttpStatus.UNAUTHORIZED_401,
                    assertThrows(APIException.class, () -> noUser.delete(new CommitData(key, "msg", "userinfo", "mail"))).getStatusCode());
            client.delete(new CommitData(key, "msg", "userinfo", "mail"));
            assertEquals(HttpStatus.NOT_FOUND_404, assertThrows(APIException.class, () -> client.getKey(key, tf)).getStatusCode());
            createKey = client.createKey(getData().getBytes(UTF_8), new CommitData(key, "msg", "info", "mail"),
                    new MetaData("application/json", Set.of(new MetaData.Role("role")), Set.of()));
            assertEquals(HttpStatus.UNAUTHORIZED_401, assertThrows(APIException.class, () -> noUser.getKey(key, tf)).getStatusCode());
            assertEquals(HttpStatus.BAD_REQUEST_400,
                    assertThrows(APIException.class, () -> client.delete(new CommitData(key, "msg", "userinfo", "mail"))).getStatusCode());
            String tag2 = key2.tag;
            assertEquals(HttpStatus.BAD_REQUEST_400,
                    assertThrows(APIException.class, () -> client.modifyKey(getData(5).getBytes(UTF_8), new CommitData(key, "msg", "info", "mail"), tag2))
                            .getStatusCode());
        }
    }

    private static String getData() {
        return getData(0);
    }

    private static String getData(int c) {
        return "{\"key" + c
                + "\":{\"data\":\"value1\",\"users\":[{\"captain\":\"america\",\"black\":\"widow\"}]},\"mkey3\":{\"data\":\"value3\",\"users\":[{\"tony\":\"stark\",\"spider\":\"man\"}]}}";
    }

    private static String getMetaData(MetaData metaData) throws JsonProcessingException {
        return MAPPER.writeValueAsString(metaData);
    }

    private JitStaticClientBuilder buildClient() {
        return JitStaticClient.create().setHost("localhost").setPort(DW.getLocalPort()).setAppContext("/application/");
    }

    private Supplier<String> getFolderString() {
        return () -> {
            try {
                return getFolder().toString();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }

    private Path getFolder() throws IOException {
        return tmpFolder.createTemporaryDirectory().toPath();
    }

    private void commit(Git git, UsernamePasswordCredentialsProvider provider) throws NoFilepatternException, GitAPIException {
        git.add().addFilepattern(ALLFILESPATTERN).call();
        git.commit().setMessage("Test commit").call();
        git.push().setCredentialsProvider(provider).call();
    }

    private void commit(Git git, UsernamePasswordCredentialsProvider provider, String string) throws NoFilepatternException, GitAPIException {
        git.checkout().setName(string).setCreateBranch(true).call();
        git.add().addFilepattern(ALLFILESPATTERN).call();
        git.commit().setMessage("Test commit").call();
        git.push().setCredentialsProvider(provider).call();

    }

    private void mkdirs(Path... paths) {
        for (Path p : paths) {
            assertTrue(p.toFile().mkdirs());
        }
    }

    static class Entity<T> {

        final T data;
        private final String tag;
        private final String contentType;

        public Entity(String tag, String contentType, T data) {
            this.tag = tag;
            this.contentType = contentType;
            this.data = data;
        }

        public String getTag() {
            return tag;
        }

        public String getContentType() {
            return contentType;
        }
    }

    private TriFunction<InputStream, String, String, Entity<JsonNode>> tf = (is, v, t) -> {
        if (is != null) {
            try {
                return new Entity<>(v, t, MAPPER.readValue(is, JsonNode.class));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return new Entity<>(v, t, null);
    };
}
