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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.io.UnsupportedEncodingException;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.NoFilepatternException;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;

import io.dropwizard.testing.ConfigOverride;
import io.dropwizard.testing.junit5.DropwizardAppExtension;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.jitstatic.api.SearchResultWrapper;
import io.jitstatic.client.BulkSearch;
import io.jitstatic.client.JitStaticClient;
import io.jitstatic.client.JitStaticClientBuilder;
import io.jitstatic.client.SearchPath;
import io.jitstatic.hosted.HostedFactory;
import io.jitstatic.test.TemporaryFolder;
import io.jitstatic.test.TemporaryFolderExtension;
import io.jitstatic.tools.AUtils;

@ExtendWith({ DropwizardExtensionsSupport.class, TemporaryFolderExtension.class })
public class BulkSearchTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Charset UTF_8 = StandardCharsets.UTF_8;
    private static final String USER = "user1";
    private static final String SECRET = "0234";
    private DropwizardAppExtension<JitstaticConfiguration> DW = new DropwizardAppExtension<>(JitstaticApplication.class, AUtils
            .getDropwizardConfigurationResource(), ConfigOverride.config("hosted.basePath", getFolder()));

    private static final String METADATA = ".metadata";
    private static TemporaryFolder tmpFolder;

    @BeforeEach
    public void setup() throws Exception {
        File temporaryGitFolder = tmpFolder.createTemporaryDirectory();
        String adress = String.format("http://localhost:%d/application", DW.getLocalPort());
        HostedFactory hostedFactory = DW.getConfiguration().getHostedFactory();
        String user = hostedFactory.getUserName();
        String pass = hostedFactory.getSecret();
        String servletName = hostedFactory.getServletName();
        String endpoint = hostedFactory.getHostedEndpoint();

        try (Git local = Git.cloneRepository().setURI(adress + "/" + servletName + "/" + endpoint).setDirectory(temporaryGitFolder)
                .setCredentialsProvider(new UsernamePasswordCredentialsProvider(user, pass)).call()) {
            for (String k : List
                    .of("key1", "key2", "key3", "data/key1", "data/key2", "data/key3", "data/data/key1", "data/data/key2", "data/data/key3", "decoy/key1", "decoy/decoy/key1")) {
                addFilesAndPush(k, temporaryGitFolder, local, user, pass);
            }
        }
    }

    @Test
    public void testSearch() throws Exception {
        try (JitStaticClient updaterClient = buildClient().setUser(USER).setPassword(SECRET).build();) {
            SearchResultWrapper search = updaterClient
                    .search(List.of(new BulkSearch("refs/heads/master", List.of(new SearchPath("data/key3", false)))), parse());
            assertNotNull(search);
            assertTrue(search.getResult().size() == 1);
            assertEquals("data/key3", search.getResult().get(0).getKey());
        }
    }

    @Test
    public void testSearchNoUser() throws Exception {
        try (JitStaticClient updaterClient = buildClient().build()) {
            SearchResultWrapper search = updaterClient
                    .search(List.of(new BulkSearch("refs/heads/master", List.of(new SearchPath("data/key3", false)))), parse());
            assertNotNull(search);
            assertTrue(search.getResult().size() == 0);
        }
    }

    @Test
    public void testWithCorruptBulkSearch() throws URISyntaxException, IOException, UnirestException {
        HttpResponse<String> response = Unirest.post(String.format("http://localhost:%s/application/bulk/fetch", DW.getLocalPort()))
                .headers(Map.of("Content-Type", "application/json"))
                .body("[]")
                .asString();
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY_422, response.getStatus(), response.getBody());
    }

    private Function<InputStream, SearchResultWrapper> parse() {
        return (is) -> {
            try {
                return MAPPER.readValue(is, SearchResultWrapper.class);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }

    private void addFilesAndPush(final String key, File temporaryGitFolder, Git local, String user, String pass)
            throws UnsupportedEncodingException, IOException, NoFilepatternException, GitAPIException {
        final Path file = temporaryGitFolder.toPath().resolve(key);
        final Path mfile = temporaryGitFolder.toPath().resolve(key + METADATA);
        if (key.endsWith("/")) {
            assertTrue(file.toFile().mkdirs());
        } else {
            if (!Files.exists(Objects.requireNonNull(file.getParent()))) {
                assertTrue(Objects.requireNonNull(file.getParent().toFile().mkdirs()));
            }
            Files.write(file, getData().getBytes(UTF_8), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }
        Files.write(mfile, getMetaData().getBytes(UTF_8), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        local.add().addFilepattern(".").call();
        local.commit().setMessage("Commit " + file.toString()).call();
        local.push().setCredentialsProvider(new UsernamePasswordCredentialsProvider(user, pass)).call();
    }

    private String getData() { return getData(0); }

    private String getMetaData(int i) {
        return "{\"users\":[{\"password\":\"" + i + "234\",\"user\":\"user1\"}]}";
    }

    private String getMetaData() { return getMetaData(0); }

    private String getData(int i) {
        return "{\"key" + i
                + "\":{\"data\":\"value1\",\"users\":[{\"password\":\"1234\",\"user\":\"user1\"}]},\"mkey3\":{\"data\":\"value3\",\"users\":[{\"password\":\"1234\",\"user\":\"user1\"}]}}";
    }

    private static Supplier<String> getFolder() {
        return () -> {
            try {
                return tmpFolder.createTemporaryDirectory().getAbsolutePath();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        };
    }

    private JitStaticClientBuilder buildClient() {
        return JitStaticClient.create().setHost("localhost").setPort(DW.getLocalPort()).setAppContext("/application/");
    }

}
