package io.jitstatic;

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

import static io.jitstatic.JitStaticConstants.GIT_CREATE;
import static io.jitstatic.JitStaticConstants.GIT_FORCEPUSH;
import static io.jitstatic.JitStaticConstants.GIT_PULL;
import static io.jitstatic.JitStaticConstants.GIT_PUSH;
import static io.jitstatic.JitStaticConstants.GIT_SECRETS;
import static io.jitstatic.JitStaticConstants.JITSTATIC_GIT_REALM;
import static io.jitstatic.JitStaticConstants.JITSTATIC_KEYADMIN_REALM;
import static io.jitstatic.JitStaticConstants.JITSTATIC_KEYUSER_REALM;
import static io.jitstatic.JitStaticConstants.USERS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.NoFilepatternException;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.images.builder.ImageFromDockerfile;

import com.codahale.metrics.health.HealthCheck.Result;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.io.Files;
import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;

import io.dropwizard.testing.ConfigOverride;
import io.dropwizard.testing.junit5.DropwizardAppExtension;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.jitstatic.auth.UserData;
import io.jitstatic.client.APIException;
import io.jitstatic.client.CommitData;
import io.jitstatic.client.JitStaticClient;
import io.jitstatic.client.MetaData;
import io.jitstatic.injection.configuration.JitstaticConfiguration;
import io.jitstatic.injection.configuration.hosted.HostedFactory;
import io.jitstatic.test.BaseTest;
import io.jitstatic.test.TemporaryFolder;
import io.jitstatic.test.TemporaryFolderExtension;
import io.jitstatic.tools.ContainerUtils;

@ExtendWith({ TemporaryFolderExtension.class, DropwizardExtensionsSupport.class })
public class CliIT extends BaseTest {

    private static final String KEYADMINUSER = "keyadminuser";
    private static final String KEYADMINUSERPASS = "3456";

    private static final String GITUSERPULL = "gituser";
    private static final String GITUSERPULLPASS = "3234";

    private static final String GITUSERPULLEXTRA = "gituserpull";
    private static final String GITUSERPULLPASSEXTRA = "33445";

    private static final String KEYUSER = "keyuser";
    private static final String KEYUSERPASS = "2456";

    private static final String GITUSERFULL = "gituserfull";
    private static final String GITUSERFULLPASS = "1234";
    private static final String GITUSERPUSH = "gituserpush";
    private static final String GITUSERPUSHPASS = "3333";
    private static final Set<Role> ALL_ROLES = Set
            .of(new Role(GIT_PULL), new Role(GIT_PUSH), new Role(GIT_FORCEPUSH), new Role(GIT_SECRETS), new Role(GIT_CREATE));

    private static final Logger LOG = LoggerFactory.getLogger(CliIT.class);
    private DropwizardAppExtension<JitstaticConfiguration> DW = new DropwizardAppExtension<>(JitstaticApplication.class, ContainerUtils
            .getDropwizardConfigurationResource(), ConfigOverride.config("hosted.basePath", getFolder()));
    private TemporaryFolder tmpfolder;

    private String adress;

    @SuppressWarnings({ "rawtypes" })
    public GenericContainer bash;

    @SuppressWarnings({ "rawtypes", "unchecked", "resource" })
    @BeforeEach
    public void setup() throws UnsupportedOperationException, IOException, InterruptedException, NoFilepatternException, GitAPIException {
        org.testcontainers.Testcontainers.exposeHostPorts(DW.getLocalPort());
        adress = String.format("http://localhost:%d/application", DW.getLocalPort());
        bash = new GenericContainer(new ImageFromDockerfile("testimage", false)
                .withDockerfileFromBuilder(builder -> builder
                        .from("ubuntu:bionic-20191029")
                        .run("apt update && apt install -y curl jq")
                        .cmd("bash")
                        .build()))
                                .withLogConsumer(new Slf4jLogConsumer(LOG))
                                .withWorkingDirectory("/home/test")
                                .withCommand("/bin/sh", "-c", "while true; do sleep 10; done");
        bash.start();
        HostedFactory hostedFactory = DW.getConfiguration().getHostedFactory();
        String rootUser = hostedFactory.getUserName();
        String rootPassword = hostedFactory.getSecret();
        Path workingFolder = getFolderFile().toPath();
        String servletName = hostedFactory.getServletName();
        String endpoint = hostedFactory.getHostedEndpoint();
        String gitAdress = adress + "/" + servletName + "/" + endpoint;
        UsernamePasswordCredentialsProvider provider = new UsernamePasswordCredentialsProvider(rootUser, rootPassword);
        try (Git git = Git.cloneRepository().setDirectory(workingFolder.toFile()).setURI(gitAdress).setCredentialsProvider(provider).call()) {
            Path users = workingFolder.resolve(USERS);

            Path keyAdminRealm = users.resolve(JITSTATIC_KEYADMIN_REALM);
            Path keyUserRealm = users.resolve(JITSTATIC_KEYUSER_REALM);

            mkdirs(users, keyAdminRealm, keyUserRealm);

            Path keyAdminUser = keyAdminRealm.resolve(KEYADMINUSER);
            Path keyuser = keyUserRealm.resolve(KEYUSER);
            UserData keyAdminUserData = new UserData(Set.of(new Role("read"), new Role("write")), KEYADMINUSERPASS, null, null);
            io.jitstatic.api.UserData keyUserUserData = new io.jitstatic.api.UserData(Set.of(new Role("role")), KEYUSERPASS, null);
            Files.write(MAPPER.writeValueAsBytes(keyAdminUserData), keyAdminUser.toFile());
            Files.write(MAPPER.writeValueAsBytes(keyUserUserData), keyuser.toFile());
            Files.write(getData().getBytes(UTF_8), workingFolder.resolve("file").toFile());
            Files.write(getMetaData(new io.jitstatic.MetaData(JitStaticConstants.APPLICATION_JSON, false, false, List.of(), Set
                    .of(new Role("role")), Set.of(new Role("role")))).getBytes(UTF_8), workingFolder.resolve("file.metadata").toFile());
            commitAndPush(git, provider);
            Path gitRealm = users.resolve(JITSTATIC_GIT_REALM);
            mkdirs(gitRealm);
            Path gitUser = gitRealm.resolve(GITUSERFULL);
            Path gitUserPush = gitRealm.resolve(GITUSERPUSH);
            Path gitUserPull = gitRealm.resolve(GITUSERPULL);
            Path gitUserPullExtra = gitRealm.resolve(GITUSERPULLEXTRA);
            UserData gitUserData = new UserData(ALL_ROLES, GITUSERFULLPASS, null, null);
            UserData gitUserDataPull = new UserData(Set.of(new Role(GIT_PULL)), GITUSERPULLPASS, null, null);
            UserData gitUserDataPush = new UserData(Set.of(new Role(GIT_PULL), new Role(GIT_PUSH)), GITUSERPUSHPASS, null, null);
            UserData gitUserDataPullExtra = new UserData(Set.of(new Role(GIT_PULL)), GITUSERPULLPASSEXTRA, null, null);
            Files.write(MAPPER.writeValueAsBytes(gitUserData), gitUser.toFile());
            Files.write(MAPPER.writeValueAsBytes(gitUserDataPush), gitUserPush.toFile());
            Files.write(MAPPER.writeValueAsBytes(gitUserDataPull), gitUserPull.toFile());
            Files.write(MAPPER.writeValueAsBytes(gitUserDataPullExtra), gitUserPullExtra.toFile());
            commit(git, provider, GIT_SECRETS);
        }
    }

    @AfterEach
    public void after() {
        try {
            SortedMap<String, Result> healthChecks = DW.getEnvironment().healthChecks().runHealthChecks();
            List<Throwable> errors = healthChecks.entrySet().stream().map(e -> e.getValue().getError()).filter(Objects::nonNull).collect(Collectors.toList());
            errors.stream().forEach(e -> e.printStackTrace());
            assertThat(errors.toString(), errors.isEmpty(), Matchers.is(true));
        } finally {
            bash.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "createuser.sh", "deleteuser.sh", "fetch.sh", "updateuser.sh" })
    public void testCliInstallAsset(String script) throws UnirestException {
        HttpResponse<String> getRequest = Unirest.get(adress + "/cli/" + script).asString();
        assertEquals(HttpStatus.OK_200, getRequest.getStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = { "createuser.sh", "deleteuser.sh", "fetch.sh", "updateuser.sh" })
    public void testGetScript(String script) throws UnsupportedOperationException, IOException, InterruptedException {
        ExecResult execInContainer = execAndLog("curl", "-s", String
                .format("http://host.testcontainers.internal:%d/%s/cli/%s", DW.getLocalPort(), "application", script), "--output", script);

        execInContainer = execAndLog("test", "-f", script);
        assertEquals(0, execInContainer.getExitCode(), String.format("Script %s failed to be downloaded", script));
    }

    @Test
    public void testCreateGitUser() throws UnsupportedOperationException, IOException, InterruptedException, URISyntaxException {
        String script = "createuser.sh";
        String host = String.format("http://host.testcontainers.internal:%d/%s", DW.getLocalPort(), "application");
        ExecResult execInContainer = execAndLog("curl", "-s", String.format("%s/cli/%s", host, script), "--output", script);
        execAndLog("chmod", "+x", script);
        execInContainer = execAndLog("./"
                + script, "-u", "testuser", "-p", "abc123", "-r", "git", "-o", "pull,push,forcepush", "-h", host, "-cu", getRootUserName(), "-cp", getRootPassword());
        assertEquals(0, execInContainer.getExitCode());
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(getRootUserName()).setPassword(getRootPassword()).build()) {
            Entity<UserData> gitUser = client.getGitUser("testuser", parse(UserData.class));
            assertNotNull(gitUser);
        }
    }

    @Test
    public void testCreateAdminUser() throws UnsupportedOperationException, IOException, InterruptedException, URISyntaxException {
        String script = "createuser.sh";
        String host = String.format("http://host.testcontainers.internal:%d/%s", DW.getLocalPort(), "application");
        ExecResult execInContainer = execAndLog("curl", "-s", String.format("%s/cli/%s", host, script), "--output", script);
        execAndLog("chmod", "+x", script);
        execInContainer = execAndLog("./"
                + script, "-u", "testuser", "-p", "abc123", "-r", "keyadmin", "-o", "pull,push,forcepush", "-h", host, "-cu", getRootUserName(), "-cp", getRootPassword());
        assertEquals(0, execInContainer.getExitCode());
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(getRootUserName()).setPassword(getRootPassword()).build()) {
            Entity<UserData> adminUser = client.getAdminUser("testuser", null, null, parse(UserData.class));
            assertNotNull(adminUser);
        }
    }

    @Test
    public void testCreateKeyUser() throws UnsupportedOperationException, IOException, InterruptedException, URISyntaxException {
        String script = "createuser.sh";
        String host = String.format("http://host.testcontainers.internal:%d/%s", DW.getLocalPort(), "application");
        ExecResult execInContainer = execAndLog("curl", "-s", String.format("%s/cli/%s", host, script), "--output", script);
        execAndLog("chmod", "+x", script);
        execInContainer = execAndLog("./"
                + script, "-u", "testuser", "-p", "abc123", "-r", "keyuser", "-o", "pull,push,forcepush", "-h", host, "-cu", getRootUserName(), "-cp", getRootPassword());
        assertEquals(0, execInContainer.getExitCode());
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(getRootUserName()).setPassword(getRootPassword()).build()) {
            Entity<UserData> keyUser = client.getUser("testuser", null, null, parse(UserData.class));
            assertNotNull(keyUser);
        }
    }

    @Test
    public void testDeleteGitUser() throws UnsupportedOperationException, IOException, InterruptedException, URISyntaxException {
        String script = "deleteuser.sh";
        String host = String.format("http://host.testcontainers.internal:%d/%s", DW.getLocalPort(), "application");
        ExecResult execInContainer = execAndLog("curl", "-s", String.format("%s/cli/%s", host, script), "--output", script);
        execAndLog("chmod", "+x", script);
        execInContainer = execAndLog("./" + script, "-u", GITUSERPULL, "-r", "git", "-cr", GITUSERFULL + ":" + GITUSERFULLPASS, "-h", host);
        assertEquals(0, execInContainer.getExitCode());
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(getRootUserName()).setPassword(getRootPassword()).build()) {
            assertEquals(HttpStatus.NOT_FOUND_404, assertThrows(APIException.class, () -> client.getGitUser(GITUSERPULL, null, parse(UserData.class)))
                    .getStatusCode());
        }
    }

    @Test
    public void testDeleteAdminUser() throws UnsupportedOperationException, IOException, InterruptedException, URISyntaxException {
        String script = "deleteuser.sh";
        String host = String.format("http://host.testcontainers.internal:%d/%s", DW.getLocalPort(), "application");
        ExecResult execInContainer = execAndLog("curl", "-s", String.format("%s/cli/%s", host, script), "--output", script);
        execAndLog("chmod", "+x", script);
        execInContainer = execAndLog("./" + script, "-u", KEYADMINUSER, "-r", "keyadmin", "-cr", GITUSERFULL + ":" + GITUSERFULLPASS, "-h", host);
        assertEquals(0, execInContainer.getExitCode());
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(getRootUserName()).setPassword(getRootPassword()).build()) {
            assertEquals(HttpStatus.NOT_FOUND_404, assertThrows(APIException.class, () -> client.getAdminUser(KEYADMINUSER, null, null, parse(UserData.class)))
                    .getStatusCode());
        }
    }

    @Test
    public void testDeleteKeyUser() throws UnsupportedOperationException, IOException, InterruptedException, URISyntaxException {
        String script = "deleteuser.sh";
        String host = String.format("http://host.testcontainers.internal:%d/%s", DW.getLocalPort(), "application");
        ExecResult execInContainer = execAndLog("curl", "-s", String.format("%s/cli/%s", host, script), "--output", script);
        execAndLog("chmod", "+x", script);
        execInContainer = execAndLog("./" + script, "-u", KEYUSER, "-r", "keyuser", "-cr", GITUSERFULL + ":" + GITUSERFULLPASS, "-h", host);
        assertEquals(0, execInContainer.getExitCode());
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(getRootUserName()).setPassword(getRootPassword()).build()) {
            assertEquals(HttpStatus.NOT_FOUND_404, assertThrows(APIException.class, () -> client.getUser(KEYUSER, null, null, parse(UserData.class)))
                    .getStatusCode());
        }
    }

    @Test
    public void testUpdateGitUserRoles() throws UnsupportedOperationException, IOException, InterruptedException, URISyntaxException {
        String script = "updateuser.sh";
        String host = String.format("http://host.testcontainers.internal:%d/%s", DW.getLocalPort(), "application");
        ExecResult execInContainer = execAndLog("curl", "-s", String.format("%s/cli/%s", host, script), "--output", script);
        execAndLog("chmod", "+x", script);
        execInContainer = execAndLog("./" + script, "-u", GITUSERPUSH, "-p", "pass", "-r", "git", "-o", "pull,push,forcepush", "-cr", GITUSERFULL + ":"
                + GITUSERFULLPASS, "-h", host);
        assertEquals(0, execInContainer.getExitCode());
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(getRootUserName()).setPassword(getRootPassword()).build()) {
            Entity<UserData> gitUser = client.getGitUser(GITUSERPUSH, null, parse(UserData.class));
            assertEquals(Set.of(new Role("pull"), new Role("push"), new Role("forcepush")), gitUser.getData().getRoles());
        }
    }

    @Test
    public void testUpdateKeyUserRoles() throws UnsupportedOperationException, IOException, InterruptedException, URISyntaxException {
        String script = "updateuser.sh";
        String host = String.format("http://host.testcontainers.internal:%d/%s", DW.getLocalPort(), "application");
        ExecResult execInContainer = execAndLog("curl", "-s", String.format("%s/cli/%s", host, script), "--output", script);
        execAndLog("chmod", "+x", script);
        execInContainer = execAndLog("./" + script, "-u", KEYUSER, "-p", "pass", "-r", "keyuser", "-o", "pull,push,forcepush", "-cr", GITUSERFULL + ":"
                + GITUSERFULLPASS, "-h", host);
        assertEquals(0, execInContainer.getExitCode());
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(getRootUserName()).setPassword(getRootPassword()).build()) {
            Entity<UserData> gitUser = client.getUser(KEYUSER, null, null, parse(UserData.class));
            assertEquals(Set.of(new Role("pull"), new Role("push"), new Role("forcepush")), gitUser.getData().getRoles());
        }
    }

    @Test
    public void testUpdateKeyUserRolesAdd() throws UnsupportedOperationException, IOException, InterruptedException, URISyntaxException {
        String script = "updateuser.sh";
        String host = String.format("http://host.testcontainers.internal:%d/%s", DW.getLocalPort(), "application");
        ExecResult execInContainer = execAndLog("curl", "-s", String.format("%s/cli/%s", host, script), "--output", script);
        execAndLog("chmod", "+x", script);
        execInContainer = execAndLog("./" + script, "-u", KEYUSER, "-p", "pass", "-r", "keyuser", "-a", "new", "-cr", GITUSERFULL + ":"
                + GITUSERFULLPASS, "-h", host);
        assertEquals(0, execInContainer.getExitCode());
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(getRootUserName()).setPassword(getRootPassword()).build()) {
            Entity<UserData> gitUser = client.getUser(KEYUSER, null, null, parse(UserData.class));
            assertEquals(Set.of(new Role("role"), new Role("new")), gitUser.getData().getRoles());
        }
    }

    @Test
    public void testUpdateKeyUserRolesAddTwo() throws UnsupportedOperationException, IOException, InterruptedException, URISyntaxException {
        String script = "updateuser.sh";
        String host = String.format("http://host.testcontainers.internal:%d/%s", DW.getLocalPort(), "application");
        ExecResult execInContainer = execAndLog("curl", "-s", String.format("%s/cli/%s", host, script), "--output", script);
        execAndLog("chmod", "+x", script);
        execInContainer = execAndLog("./" + script, "-u", KEYUSER, "-p", "pass", "-r", "keyuser", "-a", "new,other", "-cr", GITUSERFULL + ":"
                + GITUSERFULLPASS, "-h", host);
        assertEquals(0, execInContainer.getExitCode());
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(getRootUserName()).setPassword(getRootPassword()).build()) {
            Entity<UserData> gitUser = client.getUser(KEYUSER, null, null, parse(UserData.class));
            assertEquals(Set.of(new Role("role"), new Role("new"), new Role("other")), gitUser.getData().getRoles());
        }
    }

    @Test
    public void testUpdateKeyUserRolesDeleteTwo() throws UnsupportedOperationException, IOException, InterruptedException, URISyntaxException {
        String script = "updateuser.sh";
        String host = String.format("http://host.testcontainers.internal:%d/%s", DW.getLocalPort(), "application");
        ExecResult execInContainer = execAndLog("curl", "-s", String.format("%s/cli/%s", host, script), "--output", script);
        execAndLog("chmod", "+x", script);
        execInContainer = execAndLog("./"
                + script, "-u", KEYUSER, "-p", "pass", "-r", "keyuser", "-a", "new,other", "-d", "role,other,delete", "-cr", GITUSERFULL + ":"
                        + GITUSERFULLPASS, "-h", host);
        assertEquals(0, execInContainer.getExitCode());
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(getRootUserName()).setPassword(getRootPassword()).build()) {
            Entity<UserData> gitUser = client.getUser(KEYUSER, null, null, parse(UserData.class));
            assertEquals(Set.of(new Role("new")), gitUser.getData().getRoles());
        }
    }

    @Test
    public void testFetchRootFiles() throws URISyntaxException, APIException, IOException, UnsupportedOperationException, InterruptedException {
        String treetreekey = "tree/tree/key";
        String treekey = "tree/key";
        String key = "key";
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(getRootUserName()).setPassword(getRootPassword()).build()) {
            client.createKey(new byte[] { 1 }, new CommitData(key, "message", "userInfo", "userMail"), new MetaData("text/plain"));
            client.createKey(new byte[] { 2 }, new CommitData(treekey, "message", "userInfo", "userMail"), new MetaData("text/plain"));
            client.createKey("decode".getBytes(UTF_8), new CommitData(treetreekey, "message", "userInfo", "userMail"), new MetaData("text/plain"));
        }
        String script = "fetch.sh";
        String host = String.format("http://host.testcontainers.internal:%d/%s", DW.getLocalPort(), "application");
        ExecResult execInContainer = execAndLog("curl", "-s", String.format("%s/cli/%s", host, script), "--output", script);
        execAndLog("chmod", "+x", script);
        execInContainer = execAndLog("./" + script, "-p", "/", "-r", "-b", "refs/heads/master", "-cr", GITUSERFULL + ":" + GITUSERFULLPASS, "-h", host);
        assertEquals(0, execInContainer.getExitCode());
        execInContainer = execAndLog("test", "-f", key);
        assertEquals(0, execInContainer.getExitCode(), String.format("File %s failed to be downloaded", key));
        execInContainer = execAndLog("test", "-f", treekey);
        assertEquals(0, execInContainer.getExitCode(), String.format("File %s failed to be downloaded", treekey));
        execInContainer = execAndLog("test", "-f", treetreekey);
        assertEquals(0, execInContainer.getExitCode(), String.format("File %s failed to be downloaded", treetreekey));
        execInContainer = execAndLog("/bin/bash", "-c", "cat $(pwd)/" + treetreekey + " | grep decode");
        assertEquals(0, execInContainer.getExitCode());
    }

    @Test
    public void testFetchTreeFiles() throws URISyntaxException, APIException, IOException, UnsupportedOperationException, InterruptedException {
        String treetreekey = "tree/tree/key";
        String treekey = "tree/key";
        String key = "key";
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(getRootUserName()).setPassword(getRootPassword()).build()) {
            client.createKey(new byte[] { 1 }, new CommitData(key, "message", "userInfo", "userMail"), new MetaData("text/plain"));
            client.createKey(new byte[] { 2 }, new CommitData(treekey, "message", "userInfo", "userMail"), new MetaData("text/plain"));
            client.createKey(new byte[] { 3 }, new CommitData(treetreekey, "message", "userInfo", "userMail"), new MetaData("text/plain"));
        }
        String script = "fetch.sh";
        String host = String.format("http://host.testcontainers.internal:%d/%s", DW.getLocalPort(), "application");
        ExecResult execInContainer = execAndLog("curl", "-s", String.format("%s/cli/%s", host, script), "--output", script);
        execAndLog("chmod", "+x", script);
        execInContainer = execAndLog("./" + script, "-p", "/tree/", "-r", "-cr", GITUSERFULL + ":" + GITUSERFULLPASS, "-h", host);
        assertEquals(0, execInContainer.getExitCode());
        execInContainer = execAndLog("test", "-f", treekey);
        assertEquals(0, execInContainer.getExitCode(), String.format("File %s failed to be downloaded", treekey));
        execInContainer = execAndLog("test", "-f", treetreekey);
        assertEquals(0, execInContainer.getExitCode(), String.format("File %s failed to be downloaded", treetreekey));
    }

    @Test
    public void testFetchTreeFilesAlternativeDestination()
            throws URISyntaxException, APIException, IOException, UnsupportedOperationException, InterruptedException {
        String treetreekey = "tree/tree/key";
        String treekey = "tree/key";
        String key = "key";
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(getRootUserName()).setPassword(getRootPassword()).build()) {
            client.createKey(new byte[] { 1 }, new CommitData(key, "message", "userInfo", "userMail"), new MetaData("text/plain"));
            client.createKey(new byte[] { 2 }, new CommitData(treekey, "message", "userInfo", "userMail"), new MetaData("text/plain"));
            client.createKey(new byte[] { 3 }, new CommitData(treetreekey, "message", "userInfo", "userMail"), new MetaData("text/plain"));
        }
        String script = "fetch.sh";
        String host = String.format("http://host.testcontainers.internal:%d/%s", DW.getLocalPort(), "application");
        ExecResult execInContainer = execAndLog("curl", "-s", String.format("%s/cli/%s", host, script), "--output", script);
        execAndLog("chmod", "+x", script);
        execInContainer = execAndLog("./" + script, "-p", "/tree/","-r", "-d", "/alt/", "-cr", GITUSERFULL + ":" + GITUSERFULLPASS, "-h", host);
        assertEquals(0, execInContainer.getExitCode());
        execInContainer = execAndLog("test", "-f", "alt/key");
        assertEquals(0, execInContainer.getExitCode(), String.format("File %s failed to be downloaded", treekey));
        execInContainer = execAndLog("test", "-f", "alt/tree/key");
        assertEquals(0, execInContainer.getExitCode(), String.format("File %s failed to be downloaded", treetreekey));
    }

    private String getRootUserName() { return DW.getConfiguration().getHostedFactory().getUserName(); }

    private String getRootPassword() { return DW.getConfiguration().getHostedFactory().getSecret(); }

    private ExecResult execAndLog(String... cmds) throws UnsupportedOperationException, IOException, InterruptedException {
        ExecResult execInContainer = bash.execInContainer(cmds);
        logIf(execInContainer.getStderr(), s -> LOG.warn(s));
        logIf(execInContainer.getStdout(), s -> LOG.info(s));
        return execInContainer;
    }

    private void logIf(String input, Consumer<String> consumer) {
        if (input != null && !input.isBlank()) {
            consumer.accept(input);
        }
    }

    private static String getMetaData(io.jitstatic.MetaData metaData) throws JsonProcessingException {
        return MAPPER.writeValueAsString(metaData);
    }

    @Override
    protected File getFolderFile() throws IOException { return tmpfolder.createTemporaryDirectory(); }
}
