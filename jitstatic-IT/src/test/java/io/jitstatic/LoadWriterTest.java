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
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.ws.rs.client.Client;
import javax.ws.rs.core.Response;

import org.apache.http.client.ClientProtocolException;
import org.apache.http.impl.client.cache.CacheConfig;
import org.apache.http.impl.client.cache.CachingHttpClients;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.CheckoutCommand;
import org.eclipse.jgit.api.CreateBranchCommand.SetupUpstreamMode;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.api.errors.CheckoutConflictException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRefNameException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.RefAlreadyExistsException;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RepositoryState;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.RemoteRefUpdate.Status;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.dropwizard.client.HttpClientBuilder;
import io.dropwizard.client.JerseyClientBuilder;
import io.dropwizard.setup.Environment;
import io.dropwizard.testing.ConfigOverride;
import io.dropwizard.testing.ResourceHelpers;
import io.dropwizard.testing.junit5.DropwizardAppExtension;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.jitstatic.client.APIException;
import io.jitstatic.client.CommitData;
import io.jitstatic.client.JitStaticUpdaterClient;
import io.jitstatic.client.JitStaticUpdaterClientBuilder;
import io.jitstatic.hosted.HostedFactory;
import io.jitstatic.tools.Utils;

@Tag("slow")
@ExtendWith(DropwizardExtensionsSupport.class)
public class LoadWriterTest {

    private static final Pattern PAT = Pattern.compile("^\\w:\\w:\\d+$");
    private static final Logger LOG = LogManager.getLogger(LoadWriterTest.class);
    private static final String USER = "suser";
    private static final String PASSWORD = "ssecret";
    private static final Charset UTF_8 = StandardCharsets.UTF_8;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String METADATA = ".metadata";
    static final String MASTER = "master";

    private DropwizardAppExtension<JitstaticConfiguration> DW = new DropwizardAppExtension<>(JitstaticApplication.class,
            ResourceHelpers.resourceFilePath("simpleserver.yaml"), ConfigOverride.config("hosted.basePath", getFolder()));

    private String gitAdress;
    private String adminAdress;
    private WriteData data;
    private Optional<ResultData> result;

    @BeforeEach
    public void setup()
            throws InvalidRemoteException, TransportException, GitAPIException, IOException, InterruptedException, URISyntaxException {
        final HostedFactory hf = DW.getConfiguration().getHostedFactory();
        int localPort = DW.getLocalPort();
        gitAdress = String.format("http://localhost:%d/application/%s/%s", localPort, hf.getServletName(), hf.getHostedEndpoint());
        adminAdress = String.format("http://localhost:%d/admin", localPort);
    }

    @ParameterizedTest
    @ArgumentsSource(WriteArgumentsProvider.class)
    public void testWrite(WriteData data) throws URISyntaxException, ClientProtocolException, APIException, IOException,
            InvalidRemoteException, TransportException, GitAPIException, InterruptedException, ExecutionException, TimeoutException {
        this.data = data;

        initRepo(getCredentials(DW.getConfiguration().getHostedFactory()), data);

        int size = data.branches.size() * data.names.size();
        ExecutorService service = Executors.newFixedThreadPool(size);
        try {
            @SuppressWarnings("unchecked")
            CompletableFuture<ResultData>[] jobs = new CompletableFuture[size];
            int j = 0;
            for (String branch : data.branches) {
                for (String key : data.names) {
                    jobs[j] = CompletableFuture.supplyAsync(() -> {
                        try (JitStaticUpdaterClient buildKeyClient = buildKeyClient(false);) {
                            String tag = setupRun(buildKeyClient, branch, key);
                            ResultData resultData = execute(buildKeyClient, branch, key, tag);
                            LOG.info("Thread:{} key:{} branch:{} iters:{} duration:{}ms length:{}bytes", Thread.currentThread().getName(),
                                    key, branch, resultData.iterations, resultData.duration, resultData.bytes);
                            return resultData;
                        }
                    }, service);
                    j++;
                }
            }
            CompletableFuture<Void> wait = CompletableFuture.allOf(jobs);
            wait.get(30, TimeUnit.SECONDS);
            result = Stream.of(jobs).map(CompletableFuture::join).reduce(ResultData::sum);

        } finally {
            service.shutdown();
        }
    }

    private double divide(long nominator, long denominator) {
        return nominator / (double) (denominator / 1000);
    }

    private ResultData execute(final JitStaticUpdaterClient buildKeyClient, final String branch, final String key, String tag) {
        int bytes = 0;
        long stop = 0;
        int i = 1;
        long start = System.currentTimeMillis();
        try {
            while ((stop = System.currentTimeMillis()) - start < 20_000) {
                byte[] data2 = getData(i);
                bytes += data2.length;
                tag = buildKeyClient.modifyKey(data2, new CommitData(key, branch, "k:" + key + ":" + i, "userinfo", "mail"), tag);
                i++;
            }
        } catch (Exception e) {
            LOG.error("Error ", e);
        }
        return new ResultData(i, (stop - start), bytes);
    }

    private String setupRun(JitStaticUpdaterClient buildKeyClient, String branch, final String key) {
        String tag;
        try {
            tag = buildKeyClient.getKey(key, branch, LoadWriterTest::read).tag;
        } catch (URISyntaxException | IOException e1) {
            throw new RuntimeException(e1);
        }
        return tag;
    }

    @AfterEach
    public void after() throws InvalidRemoteException, TransportException, GitAPIException, IOException {
        final HostedFactory hf = DW.getConfiguration().getHostedFactory();
        Client statsClient = buildClient("stats");
        try {
            Response response = statsClient.target(adminAdress + "/metrics").queryParam("pretty", true).request().get();
            try {
                LOG.info(response.readEntity(String.class));
                File workingFolder = getFolderFile();
                try (Git git = Git.cloneRepository().setDirectory(workingFolder).setURI(gitAdress)
                        .setCredentialsProvider(getCredentials(hf)).call()) {
                    LOG.info(data.toString());
                    for (String branch : data.branches) {
                        checkoutBranch(branch, git);
                        LOG.info("##### {} #####", branch);
                        Map<String, Integer> data = pairNamesWithData(workingFolder);
                        Map<String, Integer> cnt = new HashMap<>();
                        for (RevCommit rc : git.log().call()) {
                            String msg = matchData(data, cnt, rc);
                            LOG.info("{}-{}--{}", rc.getId(), msg, rc.getAuthorIdent());
                        }
                    }
                    result.ifPresent(r -> LOG.info("Thread: {}  Iters: {} time: {}ms length: {}b Writes: {}/s Bytes: {}b/s",
                            Thread.currentThread().getName(), r.iterations, r.duration, r.bytes, divide(r.iterations, r.duration),
                            divide(r.bytes, r.duration)));
                }
            } finally {
                response.close();
            }
        } finally {
            statsClient.close();
        }
        Utils.checkContainerForErrors(DW);
    }

    private void initRepo(UsernamePasswordCredentialsProvider provider, WriteData testData)
            throws InvalidRemoteException, TransportException, GitAPIException, IOException {
        File workingFolder = getFolderFile();
        try (Git git = Git.cloneRepository().setDirectory(workingFolder).setURI(gitAdress).setCredentialsProvider(provider).call()) {
            int c = 0;
            byte[] data = getData(c);
            writeFiles(testData, workingFolder, data);
            git.add().addFilepattern(".").call();
            git.commit().setMessage("i:a:0").call();
            verifyOkPush(git.push().setCredentialsProvider(provider).call(), MASTER, c);
            String value = new String(data, UTF_8);
            pushBranches(provider, testData, git, c, value);
        }
    }

    private void pushBranches(UsernamePasswordCredentialsProvider provider, WriteData testData, Git git, int c, String value)
            throws GitAPIException, RefAlreadyExistsException, RefNotFoundException, InvalidRefNameException, CheckoutConflictException,
            UnsupportedEncodingException, InvalidRemoteException, TransportException {
        for (String branch : testData.branches) {
            if (!MASTER.equals(branch)) {
                git.checkout().setName(branch).setCreateBranch(true).setUpstreamMode(SetupUpstreamMode.TRACK).call();
                verifyOkPush(git.push().setCredentialsProvider(provider).call(), branch, c);
            }
        }
    }

    private static byte[] getMetaData() throws UnsupportedEncodingException {
        String md = "{\"users\":[{\"password\":\"" + PASSWORD + "\",\"user\":\"" + USER + "\"}]}";
        return md.getBytes(UTF_8);
    }

    private boolean verifyOkPush(Iterable<PushResult> iterable, String branch, int c) throws UnsupportedEncodingException {
        PushResult pushResult = iterable.iterator().next();
        RemoteRefUpdate remoteUpdate = pushResult.getRemoteUpdate("refs/heads/" + branch);
        if (Status.OK == remoteUpdate.getStatus()) {
            return true;
        } else {
            LOG.error("TestSuiteError: FAILED push " + c + " with " + remoteUpdate);
        }
        return false;
    }

    private void writeFiles(WriteData testData, File workingFolder, byte[] data) throws IOException, UnsupportedEncodingException {
        for (String name : testData.names) {
            Files.write(Paths.get(workingFolder.toURI()).resolve(name), data, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.TRUNCATE_EXISTING);
            Files.write(Paths.get(workingFolder.toURI()).resolve(name + METADATA), getMetaData(), StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.TRUNCATE_EXISTING);
        }
    }

    private JitStaticUpdaterClient buildKeyClient(boolean cache) {
        int localPort = DW.getLocalPort();
        JitStaticUpdaterClientBuilder builder = JitStaticUpdaterClient.create().setHost("localhost").setPort(localPort)
                .setAppContext("/application/").setUser(USER).setPassword(PASSWORD);
        if (cache) {
            builder.setCacheConfig(CacheConfig.custom().setMaxCacheEntries(1000).setMaxObjectSize(8192).build())
                    .setHttpClientBuilder(CachingHttpClients.custom());
        }
        try {
            return builder.build();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    private Ref checkoutBranch(String branch, Git git) throws IOException, GitAPIException, RefAlreadyExistsException, RefNotFoundException,
            InvalidRefNameException, CheckoutConflictException {
        git.checkout().setAllPaths(true).call();
        Ref head = git.getRepository().findRef(branch);
        if (git.getRepository().getRepositoryState() != RepositoryState.SAFE) {
            git.reset().setMode(ResetType.HARD).setRef(head.getObjectId().name()).call();
            git.clean().setCleanDirectories(true).setForce(true).call();
        }
        CheckoutCommand checkout = git.checkout().setName(branch).setUpstreamMode(SetupUpstreamMode.TRACK)
                .setStartPoint("origin/" + branch);
        if (head == null) {
            checkout.setCreateBranch(true);
        }
        checkout.call();
        head = git.getRepository().findRef(branch);
        return head;
    }

    private String matchData(Map<String, Integer> data, Map<String, Integer> cnt, RevCommit rc) {
        String msg = rc.getShortMessage();
        Matcher matcher = PAT.matcher(msg);
        if (matcher.matches()) {
            String[] split = msg.split(":");
            Integer value = cnt.get(split[1]);
            Integer newValue = Integer.valueOf(split[2]);
            if (value != null) {
                assertEquals(Integer.valueOf(value.intValue() - 1), newValue);
            } else {
                assertEquals(data.get(split[1]), newValue);
            }
            cnt.put(split[1], newValue);
        } else {
            LOG.info("Message prints something else {}", msg);
        }
        return msg;
    }

    private Client buildClient(final String name) {
        Environment environment = DW.getEnvironment();
        JerseyClientBuilder jerseyClientBuilder = new JerseyClientBuilder(environment);
        jerseyClientBuilder.setApacheHttpClientBuilder(new HttpClientBuilder(environment));
        return jerseyClientBuilder.build(name);
    }

    private static byte[] getData(int c) throws UnsupportedEncodingException {
        return new StringBuilder("{\"data\":").append(c).append("}").toString().getBytes(UTF_8);
    }

    private static UsernamePasswordCredentialsProvider getCredentials(final HostedFactory hf) {
        return getCredentials(hf.getUserName(), hf.getSecret());
    }

    private static UsernamePasswordCredentialsProvider getCredentials(String name, String pass) {
        return new UsernamePasswordCredentialsProvider(name, pass);
    }

    private Map<String, Integer> pairNamesWithData(File workingFolder) throws IOException, JsonParseException, JsonMappingException {
        Map<String, Integer> data = new HashMap<>();
        for (String name : this.data.names) {
            int d = readData(Paths.get(workingFolder.toURI()).resolve(name)).get("data").asInt();
            data.put(name, d);
            LOG.info("{} contains {}", name, d);
        }
        return data;
    }

    private JsonNode readData(Path filedata) throws IOException, JsonParseException, JsonMappingException {
        try {
            return MAPPER.readValue(filedata.toFile(), JsonNode.class);
        } catch (Exception e) {
            LOG.error("Failed file looks like:" + new String(Files.readAllBytes(filedata), UTF_8));
            throw e;
        }
    }

    private static Supplier<String> getFolder() {
        return () -> {
            try {
                return getFolderFile().toString();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        };
    }

    private static File getFolderFile() throws IOException {
        File file = Files.createTempDirectory("junit").toFile();
        file.deleteOnExit();
        return file;
    }

    private static Entity read(InputStream is, String tag, String contentType) {
        if (is != null) {
            try {
                return new Entity(tag, MAPPER.readValue(is, JsonNode.class));
            } catch (IOException e) {
                LOG.error("ERROR READING ENTITY", e);
                throw new UncheckedIOException(e);
            }
        }
        return new Entity(tag, null);
    }

    private static class ResultData {
        int iterations;
        long duration;
        int bytes;

        public ResultData(int iterations, long duration, int bytes) {
            this.iterations = iterations;
            this.duration = duration;
            this.bytes = bytes;
        }

        static ResultData sum(ResultData a, ResultData b) {
            return new ResultData(a.iterations + b.iterations, Math.max(a.duration, b.duration), a.bytes + b.bytes);
        }
    }

    private static class Entity {

        private final String tag;

        public Entity(String tag, final JsonNode value2) {
            this.tag = tag;
        }

    }
}
