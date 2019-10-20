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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.bridge.SLF4JBridgeHandler;

import com.fasterxml.jackson.databind.JsonNode;

import io.dropwizard.testing.ConfigOverride;
import io.dropwizard.testing.ResourceHelpers;
import io.dropwizard.testing.junit5.DropwizardAppExtension;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.jitstatic.client.APIException;
import io.jitstatic.client.JitStaticClient;
import io.jitstatic.hosted.HostedFactory;
import io.jitstatic.test.BaseTest;
import io.jitstatic.test.TemporaryFolder;
import io.jitstatic.test.TemporaryFolderExtension;

@ExtendWith({ TemporaryFolderExtension.class, DropwizardExtensionsSupport.class })
public class JsonLoggingIT extends BaseTest {
    private static final String METADATA = ".metadata";
    private static final String ACCEPT_STORAGE = "accept/storage";
    private static final String USER = "suser";
    private static final String PASSWORD = "ssecret";
    private DropwizardAppExtension<JitstaticConfiguration> DW = new DropwizardAppExtension<>(JitstaticApplication.class, ResourceHelpers
            .resourceFilePath("simpleserver_json.yaml"), ConfigOverride.config("hosted.basePath", getFolder()));
    private TemporaryFolder tmpfolder;
    private String adress;

    @BeforeEach
    public void setup() throws Exception {
        adress = String.format("http://localhost:%d/application", DW.getLocalPort());
        HostedFactory hostedFactory = DW.getConfiguration().getHostedFactory();
        String user = hostedFactory.getUserName();
        String pass = hostedFactory.getSecret();
        String servletName = hostedFactory.getServletName();
        String endpoint = hostedFactory.getHostedEndpoint();

        setupRepo(user, pass, servletName, endpoint);
    }

    private void setupRepo(String user, String pass, String servletName, String endpoint) throws Exception {
        File workingDirectory = getFolderFile();
        UsernamePasswordCredentialsProvider provider = new UsernamePasswordCredentialsProvider(user, pass);
        try (Git git = Git.cloneRepository().setDirectory(workingDirectory).setURI(adress + "/" + servletName + "/" + endpoint).setCredentialsProvider(provider)
                .call()) {
            writeFile(workingDirectory.toPath(), ACCEPT_STORAGE);
            writeFile(workingDirectory.toPath(), ACCEPT_STORAGE + METADATA);

            final Path filePath = workingDirectory.toPath().resolve("accept/.metadata");
            Files.createDirectories(Objects.requireNonNull(filePath.getParent()));
            try (InputStream is = getClass().getResourceAsStream("/accept/metadata")) {
                Files.copy(is, filePath, StandardCopyOption.REPLACE_EXISTING);
            }

            writeFile(workingDirectory.toPath(), "accept/genkey");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("Initial commit").call();
            verifyOkPush(git.push().setCredentialsProvider(provider).call());
        }
    }

    @Test
    public void testJSONLogging() throws URISyntaxException, APIException, IOException, InterruptedException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        PrintStream oldOutput = System.out;
        Semaphore s = new Semaphore(1);
        PrintStream listener = getPrintStream(bos, s);
        System.setOut(listener);
        try (JitStaticClient client = buildClient(DW.getLocalPort()).setUser(USER).setPassword(PASSWORD).build();) {
            Entity<JsonNode> key = client.getKey(ACCEPT_STORAGE, null, parse(JsonNode.class));
            assertEquals(getData(), key.getData().toString());
            assertNotNull(key.getTag());
            s.acquire();
            listener.flush();
            assertTrue(bos.toByteArray().length > 0);
            JsonNode readTree = MAPPER.readTree(bos.toByteArray());
            assertEquals("INFO", readTree.findValue("level").asText());
        } finally {
            System.setOut(oldOutput);
        }
    }

    @Test
    public void testJavaLoggingSlf4jLink() throws IOException, InterruptedException {
        assertTrue(SLF4JBridgeHandler.isInstalled());
        String msg = "it works";
        Logger logger = Logger.getLogger(getClass().getName());
        logger.setLevel(Level.INFO);
        PrintStream oldOut = System.out;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Semaphore s = new Semaphore(1);
        PrintStream listen = getPrintStream(baos, s);
        s.acquire();
        System.setOut(listen);
        logger.info(msg);
        s.acquire();
        System.setOut(oldOut);
        listen.flush();
        byte[] content = baos.toByteArray();
        if (content.length > 0) {
            JsonNode tree = MAPPER.readTree(content);
            assertEquals(msg, tree.get("message").asText(), new String(content));
        } else {
            System.out.println("WARN: Missed the flush " + new String(content));
        }
    }

    private PrintStream getPrintStream(ByteArrayOutputStream baos, Semaphore s) {
        return new PrintStream(baos, true) {
            @Override
            public void flush() {
                super.flush();
                s.release();
            }
        };
    }

    protected File getFolderFile() throws IOException { return tmpfolder.createTemporaryDirectory(); }

    private void writeFile(Path workBase, String file) throws IOException {
        final Path filePath = workBase.resolve(file);
        Files.createDirectories(Objects.requireNonNull(filePath.getParent()));
        try (InputStream is = getClass().getResourceAsStream("/" + file)) {
            Files.copy(is, filePath, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
