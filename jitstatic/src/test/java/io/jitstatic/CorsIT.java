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

import static io.jitstatic.JitStaticConstants.GIT_FORCEPUSH;
import static io.jitstatic.JitStaticConstants.JITSTATIC_GIT_REALM;
import static io.jitstatic.JitStaticConstants.JITSTATIC_KEYADMIN_REALM;
import static io.jitstatic.JitStaticConstants.JITSTATIC_KEYUSER_REALM;
import static io.jitstatic.JitStaticConstants.GIT_PULL;
import static io.jitstatic.JitStaticConstants.GIT_PUSH;
import static io.jitstatic.JitStaticConstants.GIT_SECRETS;
import static io.jitstatic.JitStaticConstants.USERS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.NoFilepatternException;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.glassfish.jersey.internal.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.io.Files;
import com.mashape.unirest.http.Headers;
import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;

import io.dropwizard.testing.ConfigOverride;
import io.dropwizard.testing.junit5.DropwizardAppExtension;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.jitstatic.api.AddKeyData;
import io.jitstatic.auth.UserData;
import io.jitstatic.client.MetaData;
import io.jitstatic.hosted.HostedFactory;
import io.jitstatic.source.ObjectStreamProvider;
import io.jitstatic.test.BaseTest;
import io.jitstatic.test.TemporaryFolder;
import io.jitstatic.test.TemporaryFolderExtension;
import io.jitstatic.tools.ContainerUtils;

@ExtendWith({ TemporaryFolderExtension.class, DropwizardExtensionsSupport.class })
public class CorsIT extends BaseTest {

    private static final Logger LOG = LoggerFactory.getLogger(CorsIT.class);

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
    private static final String GITUSERPUSH = "gituserpush";
    private static final String GITUSERPUSHPASS = "3333";
    private TemporaryFolder tmpFolder;
    private String rootUser;
    private String rootPassword;

    private DropwizardAppExtension<JitstaticConfiguration> DW = new DropwizardAppExtension<>(JitstaticApplication.class, ContainerUtils
            .getDropwizardConfigurationResource(), ConfigOverride.config("hosted.basePath", getFolder()));
    private String adress;
    private String gitAdress;
    private UserData keyAdminUserData;
    private UserData keyUserUserData;

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
            keyUserUserData = new UserData(Set.of(new Role("role")), KEYUSERPASS, null, null);
            UserData keyUserUserDataNoRole = new UserData(Set.of(), KEYUSERNOROLEPASS, null, null);
            Files.write(MAPPER.writeValueAsBytes(keyAdminUserData), keyAdminUser.toFile());
            Files.write(MAPPER.writeValueAsBytes(keyUserUserData), keyuser.toFile());
            Files.write(MAPPER.writeValueAsBytes(keyUserUserDataNoRole), keyusernorole.toFile());
            Files.write(getData().getBytes(UTF_8), workingFolder.resolve("file").toFile());
            Files.write(getMetaData(new MetaData(Set.of(), JitStaticConstants.APPLICATION_JSON, false, false, List.of(), Set.of(new MetaData.Role("role")), Set
                    .of(new MetaData.Role("role")))).getBytes(UTF_8), workingFolder.resolve("file.metadata").toFile());
            Files.write(getData().getBytes(UTF_8), workingFolder.resolve("file2").toFile());
            Files.write(getMetaData(new MetaData(Set.of(), JitStaticConstants.APPLICATION_JSON, false, false, List
                    .of(new io.jitstatic.client.HeaderPair("X-Test", "testvalue")), Set.of(new MetaData.Role("role")), Set.of(new MetaData.Role("role"))))
                            .getBytes(UTF_8), workingFolder.resolve("file2.metadata").toFile());

            commitAndPush(git, provider);
            Path gitRealm = users.resolve(JITSTATIC_GIT_REALM);
            mkdirs(gitRealm);
            Path gitUser = gitRealm.resolve(GITUSERFULL);
            Path gitUserPush = gitRealm.resolve(GITUSERPUSH);
            Path gitUserNoPush = gitRealm.resolve(GITUSER);
            UserData gitUserData = new UserData(Set
                    .of(new Role(GIT_PULL), new Role(GIT_PUSH), new Role(GIT_FORCEPUSH), new Role(GIT_SECRETS)), GITUSERFULLPASS, null, null);
            UserData gitUserDataNoPush = new UserData(Set.of(new Role(GIT_PULL)), GITUSERPASS, null, null);
            UserData gitUserDataPush = new UserData(Set.of(new Role(GIT_PULL), new Role(GIT_PUSH)), GITUSERPUSHPASS, null, null);
            Files.write(MAPPER.writeValueAsBytes(gitUserData), gitUser.toFile());
            Files.write(MAPPER.writeValueAsBytes(gitUserDataPush), gitUserPush.toFile());
            Files.write(MAPPER.writeValueAsBytes(gitUserDataNoPush), gitUserNoPush.toFile());
            commit(git, provider, GIT_SECRETS);
        }
    }

    @Test
    public void testPreflightRequestPUT() throws UnirestException {
        HttpResponse<String> asString = Unirest.options(String.format("http://localhost:%s/application/storage/file", DW.getLocalPort()))
                .header("Access-Control-Request-Method", "PUT")
                .header("Access-Control-Request-Headers", "content-type,if-match")
                .header("Origin", "http://localhost").asString();
        Headers headers = asString.getHeaders();
        printHeaders(headers);
        assertEquals(List.of("http://localhost"), headers.get("Access-Control-Allow-Origin"));
        assertEquals(List.of("1800"), headers.get("Access-Control-Max-Age"));
        assertEquals(List.of("OPTIONS,GET,PUT,POST,DELETE,HEAD"), headers.get("Access-Control-Allow-Methods"));
        assertEquals(List.of("X-Requested-With,Content-Type,Accept,Origin,if-match"), headers.get("Access-Control-Allow-Headers"));
        assertEquals(List.of("true"), headers.get("Access-Control-Allow-Credentials"));
    }

    @Test
    public void testPreflightRequestAdditionalHeaderPUT() throws UnirestException {
        HttpResponse<String> asString = Unirest.options(String.format("http://localhost:%s/application/storage/file2", DW.getLocalPort()))
                .header("Access-Control-Request-Method", "PUT")
                .header("Access-Control-Request-Headers", "content-type,if-match,x-test")
                .header("Origin", "http://localhost").asString();
        Headers headers = asString.getHeaders();
        printHeaders(headers);
        assertEquals(List.of("http://localhost"), headers.get("Access-Control-Allow-Origin"));
        assertEquals(List.of("1800"), headers.get("Access-Control-Max-Age"));
        assertEquals(List.of("OPTIONS,GET,PUT,POST,DELETE,HEAD"), headers.get("Access-Control-Allow-Methods"));
        assertEquals(List.of("x-requested-with,content-type,accept,origin,if-match,x-test"), headers.get("Access-Control-Allow-Headers"));
        assertEquals(List.of("true"), headers.get("Access-Control-Allow-Credentials"));
    }

    @Test
    public void testPreflightWrongHeader() throws UnirestException {
        HttpResponse<String> asString = Unirest.options(String.format("http://localhost:%s/application/storage/file2", DW.getLocalPort()))
                .header("Access-Control-Request-Method", "PUT")
                .header("Access-Control-Request-Headers", "content-type,if-match,notallowed")
                .header("Origin", "http://localhost").asString();
        Headers headers = asString.getHeaders();
        printHeaders(headers);
        assertEquals(null, headers.get("Access-Control-Allow-Credentials"));
        assertEquals(null, headers.get("Access-Control-Allow-Origin"));
        assertEquals(null, headers.get("Access-Control-Max-Age"));
        assertEquals(null, headers.get("Access-Control-Allow-Methods"));
        assertEquals(null, headers.get("Access-Control-Allow-Headers"));
    }

    @Test
    public void testSimpleRequestGet() throws UnirestException {
        Map<String, String> headers = Map.of("Origin", "http://localhost", "Authorization", "Basic " + baseEncode(KEYUSER, KEYUSERPASS));
        HttpResponse<String> response = Unirest.get(String.format("http://localhost:%s/application/storage/file2", DW.getLocalPort())).headers(headers)
                .asString();
        Headers result = response.getHeaders();
        printHeaders(result);
        assertEquals(List.of("true"), result.get("Access-Control-Allow-Credentials"));
        assertEquals(List.of("http://localhost"), result.get("Access-Control-Allow-Origin"));
        assertEquals(List.of("testvalue"), result.get("X-Test"));
        assertEquals(Set.of("etag", "x-test", "content-length"), toSet(result.get("Access-Control-Expose-Headers")));
    }

    @Test
    public void testCORSRoot() throws UnirestException {
        Map<String, String> headers = Map
                .of("Origin", "http://localhost", "Access-Control-Request-Method", "PUT", "Access-Control-Request-Headers", "Content-Type,Accept");
        HttpResponse<String> response = Unirest.options(String.format("http://localhost:%s/application/storage/", DW.getLocalPort())).headers(headers)
                .asString();
        Headers result = response.getHeaders();
        printHeaders(result);
        assertEquals(List.of("true"), result.get("Access-Control-Allow-Credentials"));
        assertEquals(List.of("http://localhost"), result.get("Access-Control-Allow-Origin"));
    }

    @Test
    public void testCORSWrongHeader() throws UnirestException {
        Map<String, String> headers = Map
                .of("Origin", "http://localhost", "Access-Control-Request-Method", "PUT", "Access-Control-Request-Headers", "Content-Type,Accept,Test");
        HttpResponse<String> response = Unirest.options(String.format("http://localhost:%s/application/storage/file2", DW.getLocalPort())).headers(headers)
                .asString();
        Headers result = response.getHeaders();
        printHeaders(result);
        assertEquals(null, result.get("Access-Control-Allow-Credentials"));
        assertEquals(null, result.get("Access-Control-Allow-Origin"));
        assertEquals(null, result.get("Access-Control-Allow-Methods"));
    }

    @Test
    public void testSimpleRequest() throws UnirestException {
        Map<String, String> headers = Map.of("Origin", "http://localhost", "Authorization", "Basic " + baseEncode(KEYUSER, KEYUSERPASS));
        HttpResponse<String> response = Unirest.get(String.format("http://localhost:%s/application/storage/file2", DW.getLocalPort())).headers(headers)
                .asString();
        Headers responseHeaders = response.getHeaders();
        printHeaders(responseHeaders);
        assertEquals(toSet(List.of("content-length", "x-test", "etag")), toSet(responseHeaders.get("Access-Control-Expose-Headers")));
        assertEquals(List.of("http://localhost"), responseHeaders.get("Access-Control-Allow-Origin"));
        assertEquals(List.of("true"), responseHeaders.get("Access-Control-Allow-Credentials"));
        assertEquals(List.of("testvalue"), responseHeaders.get("X-Test"));
        assertEquals(List.of("application/json"), responseHeaders.get("Content-Type"));
        assertNotNull(responseHeaders.get("ETag"));
        assertNotNull(responseHeaders.get("Content-Length"));
    }

    @Test
    public void testSimpleRequestPost() throws UnirestException, JsonProcessingException {
        Map<String, String> headers = Map.of("Origin", "http://localhost", "Content-Type", "application/json", "Authorization", "Basic "
                + Base64.encodeAsString(KEYADMINUSER + ":" + KEYADMINUSERPASS));
        HttpResponse<String> response = Unirest.post(String.format("http://localhost:%s/application/storage/file3", DW.getLocalPort())).headers(headers)
                .body(MAPPER.writeValueAsBytes(new AddKeyData(ObjectStreamProvider
                        .toProvider(new byte[] { 0 }), new io.jitstatic.MetaData(Set.of(new Role("read")), Set.of(new Role("read"))), "msg", "ui", "um")))
                .asString();
        Headers responseHeaders = response.getHeaders();
        printHeaders(responseHeaders);
        assertEquals(toSet(List.of("etag", "Content-length")), toSet(responseHeaders.get("Access-Control-Expose-Headers")));
        assertEquals(List.of("http://localhost"), responseHeaders.get("Access-Control-Allow-Origin"));
        assertEquals(List.of("true"), responseHeaders.get("Access-Control-Allow-Credentials"));
        assertNotNull(responseHeaders.get("ETag"));
        assertNotNull(responseHeaders.get("Content-Length"));
    }

    @Test
    public void testSimpleRequestHead() throws UnirestException {
        Map<String, String> headers = Map.of("Origin", "http://localhost", "Access-Control-Request-Headers", "Content-Type,Accept", "Authorization", "Basic "
                + baseEncode(KEYUSER, KEYUSERPASS));
        HttpResponse<String> response = Unirest.head(String.format("http://localhost:%s/application/storage/file2", DW.getLocalPort())).headers(headers)
                .asString();
        printHeaders(response.getHeaders());
    }

    @Test
    public void testCORSAllMetakey() throws UnirestException {
        Map<String, String> headers = Map
                .of("Origin", "http://localhost", "Access-Control-Request-Method", "PUT", "Access-Control-Request-Headers", "Content-Type,Accept");
        HttpResponse<String> response = Unirest.options(String.format("http://localhost:%s/application/metakey/", DW.getLocalPort())).headers(headers)
                .asString();
        Headers result = response.getHeaders();
        printHeaders(result);
        assertEquals(List.of("true"), result.get("Access-Control-Allow-Credentials"));
        assertEquals(List.of("http://localhost"), result.get("Access-Control-Allow-Origin"));
        assertEquals(HttpStatus.NOT_FOUND_404, response.getStatus());
    }

    @Test
    public void testAllowedHeaders() throws UnirestException {
        Map<String, String> headers = Map
                .of("Origin", "http://www.xxx.yyy", "Access-Control-Request-Method", "PUT", "Access-Control-Request-Headers", "X-Requested-With,Content-Type,Accept,Origin");
        HttpResponse<String> response = Unirest.options(String.format("http://localhost:%s/application/metakey/key", DW.getLocalPort())).headers(headers)
                .asString();
        Headers result = response.getHeaders();
        printHeaders(result);
        assertEquals(List.of("X-Requested-With,Content-Type,Accept,Origin,if-match"), result.get("Access-Control-Allow-Headers"));
        assertEquals(List.of("1800"), result.get("Access-Control-Max-Age"));
    }

    @Test
    public void testPreflightDelete() throws UnirestException {
        HttpResponse<String> asString = Unirest.options(String.format("http://localhost:%s/application/storage/file", DW.getLocalPort()))
                .header("Access-Control-Request-Method", "DELETE")
                .header("Access-Control-Request-Headers", "content-type,if-match," + JitStaticConstants.X_JITSTATIC_MAIL + ","
                        + JitStaticConstants.X_JITSTATIC_MESSAGE + ","
                        + JitStaticConstants.X_JITSTATIC_NAME)
                .header("Origin", "http://localhost").asString();
        Headers headers = asString.getHeaders();
        printHeaders(headers);
        assertEquals(List.of("http://localhost"), headers.get("Access-Control-Allow-Origin"));
        assertEquals(List.of("1800"), headers.get("Access-Control-Max-Age"));
        assertEquals(List.of("OPTIONS,GET,PUT,POST,DELETE,HEAD"), headers.get("Access-Control-Allow-Methods"));
        assertEquals(toSet(Arrays.asList(("X-Requested-With,Content-Type,Accept,Origin,if-match," + JitStaticConstants.X_JITSTATIC_MAIL + ","
                + JitStaticConstants.X_JITSTATIC_MESSAGE + ","
                + JitStaticConstants.X_JITSTATIC_NAME).split(",")).stream().map(h -> h.toLowerCase(Locale.ROOT))
                .collect(Collectors.toList())), toSet(headers.get("Access-Control-Allow-Headers")));

        assertEquals(List.of("true"), headers.get("Access-Control-Allow-Credentials"));
    }

    @Test
    public void testPreflightDeleteTag() throws UnirestException {
        HttpResponse<String> asString = Unirest.options(String.format("http://localhost:%s/application/storage/file", DW.getLocalPort()))
                .queryString("ref", "refs/tags/tag")
                .header("Access-Control-Request-Method", "DELETE")
                .header("Access-Control-Request-Headers", "content-type,if-match," + JitStaticConstants.X_JITSTATIC_MAIL + ","
                        + JitStaticConstants.X_JITSTATIC_MESSAGE + ","
                        + JitStaticConstants.X_JITSTATIC_NAME)
                .header("Origin", "http://localhost").asString();
        Headers headers = asString.getHeaders();
        printHeaders(headers);
        assertEquals(null, headers.get("Access-Control-Allow-Origin"));
        assertEquals(null, headers.get("Access-Control-Max-Age"));
        assertEquals(null, headers.get("Access-Control-Allow-Methods"));
        assertEquals(null, headers.get("Access-Control-Allow-Headers"));
        assertEquals(null, headers.get("Access-Control-Allow-Credentials"));
    }

    @Test
    public void testNonSimpleRequestDelete() throws UnirestException, JsonProcessingException {
        Map<String, String> headers = Map.of("Origin", "http://localhost", "Content-Type", "application/json", "Authorization", "Basic "
                + baseEncode(KEYUSER, KEYUSERPASS));
        HttpResponse<String> response = Unirest.delete(String.format("http://localhost:%s/application/storage/file2", DW.getLocalPort())).headers(headers)
                .asString();
        Headers responseHeaders = response.getHeaders();
        printHeaders(responseHeaders);
        assertEquals(toSet(List.of("etag", "Content-length")), toSet(responseHeaders.get("Access-Control-Expose-Headers")));
        assertEquals(List.of("http://localhost"), responseHeaders.get("Access-Control-Allow-Origin"));
        assertEquals(List.of("true"), responseHeaders.get("Access-Control-Allow-Credentials"));
        assertNotNull(responseHeaders.get("Content-Length"));
    }

    @Test
    public void testGetShouldntHaveCORSHeaders() throws UnirestException {
        Map<String, String> headers = Map.of("Authorization", "Basic " + baseEncode(KEYUSER, KEYUSERPASS));
        HttpResponse<String> response = Unirest.get(String.format("http://localhost:%s/application/storage/file2", DW.getLocalPort())).headers(headers)
                .asString();
        Headers responseHeaders = response.getHeaders();
        assertEquals(null, responseHeaders.get("Access-Control-Expose-Headers"));
    }

    @Test
    public void testMultipleDeclaredAccessHeaders() throws UnirestException {
        HttpResponse<String> response = Unirest.options(String.format("http://localhost:%s/application/storage/file2", DW.getLocalPort()))
                .header("Authorization", "Basic " + baseEncode(KEYUSER, KEYUSERPASS))
                .header("Access-Control-Request-Method", "DELETE")
                .header("Access-Control-Request-Headers", "content-type,if-match")
                .header("Access-Control-Request-Headers", JitStaticConstants.X_JITSTATIC_MAIL)
                .header("Access-Control-Request-Headers", JitStaticConstants.X_JITSTATIC_MESSAGE)
                .header("Access-Control-Request-Headers", JitStaticConstants.X_JITSTATIC_NAME)
                .header("Origin", "http://localhost")
                .asString();
        Headers headers = response.getHeaders();
        printHeaders(headers);
        assertEquals(List.of("http://localhost"), headers.get("Access-Control-Allow-Origin"));
        assertEquals(List.of("1800"), headers.get("Access-Control-Max-Age"));
        assertEquals(List.of("OPTIONS,GET,PUT,POST,DELETE,HEAD"), headers.get("Access-Control-Allow-Methods"));
        assertEquals(toSet(Arrays.asList(("x-test,X-Requested-With,Content-Type,Accept,Origin,if-match," + JitStaticConstants.X_JITSTATIC_MAIL + ","
                + JitStaticConstants.X_JITSTATIC_MESSAGE + ","
                + JitStaticConstants.X_JITSTATIC_NAME).split(",")).stream().map(h -> h.toLowerCase(Locale.ROOT))
                .collect(Collectors.toList())), toSet(headers.get("Access-Control-Allow-Headers")));
        assertEquals(List.of("true"), headers.get("Access-Control-Allow-Credentials"));
    }

    @Test
    public void testMetaDataResource() throws UnirestException {
        HttpResponse<String> response = Unirest.options(String.format("http://localhost:%s/application/metakey/file2", DW.getLocalPort()))
                .header("Authorization", "Basic " + baseEncode(KEYUSER, KEYUSERPASS))
                .header("Access-Control-Request-Method", "PUT")
                .header("Access-Control-Request-Headers", "content-type,if-match")
                .header("Access-Control-Request-Headers", JitStaticConstants.X_JITSTATIC_MAIL)
                .header("Access-Control-Request-Headers", JitStaticConstants.X_JITSTATIC_MESSAGE)
                .header("Access-Control-Request-Headers", JitStaticConstants.X_JITSTATIC_NAME)
                .header("Origin", "http://localhost")
                .asString();
        Headers headers = response.getHeaders();
        printHeaders(headers);
        assertEquals(List.of("http://localhost"), headers.get("Access-Control-Allow-Origin"));
        assertEquals(List.of("1800"), headers.get("Access-Control-Max-Age"));
        assertEquals(List.of("OPTIONS,GET,PUT,POST,DELETE,HEAD"), headers.get("Access-Control-Allow-Methods"));
        assertEquals(Set.of(Arrays.asList("X-Requested-With", "Content-Type", "Accept", "Origin", "if-match").stream().map(h -> h.toLowerCase(Locale.ROOT))
                .collect(Collectors.toList())), Set
                        .of(headers.get("Access-Control-Allow-Headers").stream().map(s -> Arrays.stream(s.split(","))).flatMap(s -> s)
                                .map(m -> m.toLowerCase(Locale.ROOT)).collect(Collectors.toList())));
        assertEquals(List.of("true"), headers.get("Access-Control-Allow-Credentials"));
    }

    @Test
    public void testUserDataResource() throws UnirestException {
        HttpResponse<String> response = Unirest.options(String.format("http://localhost:%s/application/users/keyuser/keyuser", DW.getLocalPort()))
                .header("Authorization", "Basic " + baseEncode(KEYUSER, KEYUSERPASS))
                .header("Access-Control-Request-Method", "PUT")
                .header("Access-Control-Request-Headers", "content-type,if-match")
                .header("Access-Control-Request-Headers", JitStaticConstants.X_JITSTATIC_MAIL)
                .header("Access-Control-Request-Headers", JitStaticConstants.X_JITSTATIC_MESSAGE)
                .header("Access-Control-Request-Headers", JitStaticConstants.X_JITSTATIC_NAME)
                .header("Origin", "http://localhost")
                .asString();
        Headers headers = response.getHeaders();
        printHeaders(headers);
        assertEquals(List.of("http://localhost"), headers.get("Access-Control-Allow-Origin"));
        assertEquals(List.of("1800"), headers.get("Access-Control-Max-Age"));
        assertEquals(List.of("OPTIONS,GET,PUT,POST,DELETE,HEAD"), headers.get("Access-Control-Allow-Methods"));
        assertEquals(Set.of(Arrays.asList("X-Requested-With", "Content-Type", "Accept", "Origin", "if-match").stream().map(h -> h.toLowerCase(Locale.ROOT))
                .collect(Collectors.toList())), Set
                        .of(headers.get("Access-Control-Allow-Headers").stream().map(s -> Arrays.stream(s.split(","))).flatMap(s -> s)
                                .map(m -> m.toLowerCase(Locale.ROOT)).collect(Collectors.toList())));
        assertEquals(List.of("true"), headers.get("Access-Control-Allow-Credentials"));
    }

    private void printHeaders(Headers result) {
        result.entrySet().forEach(h -> LOG.info("{}", h));
    }

    private Set<String> toSet(List<String> list) {
        Set<String> set = new HashSet<>();
        for (String s : list) {
            set.addAll(Arrays.stream(s.split(",")).map(String::trim).collect(Collectors.toSet()));
        }
        return set;
    }

    private static String getMetaData(MetaData metaData) throws JsonProcessingException {
        return MAPPER.writeValueAsString(metaData);
    }

    private void commit(Git git, UsernamePasswordCredentialsProvider provider, String string) throws NoFilepatternException, GitAPIException {
        git.checkout().setName(string).setCreateBranch(true).call();
        commitAndPush(git, provider);
    }

    @Override
    protected File getFolderFile() throws IOException { return tmpFolder.createTemporaryDirectory(); }

}
