package io.jitstatic;

import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.SocketException;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.ws.rs.client.Client;
import javax.ws.rs.core.Response;

import org.apache.http.client.ClientProtocolException;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.client.cache.CacheConfig;
import org.apache.http.impl.client.cache.CachingHttpClients;
import org.eclipse.jgit.api.CheckoutCommand;
import org.eclipse.jgit.api.CreateBranchCommand.SetupUpstreamMode;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.api.errors.CheckoutConflictException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRefNameException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.NoFilepatternException;
import org.eclipse.jgit.api.errors.RefAlreadyExistsException;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.dircache.DirCache;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.io.CountingInputStream;

import io.dropwizard.client.HttpClientBuilder;
import io.dropwizard.client.JerseyClientBuilder;
import io.dropwizard.setup.Environment;
import io.dropwizard.testing.ConfigOverride;
import io.dropwizard.testing.ResourceHelpers;
import io.dropwizard.testing.junit5.DropwizardAppExtension;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.jitstatic.client.APIException;
import io.jitstatic.client.CommitData;
import io.jitstatic.client.JitStaticClient;
import io.jitstatic.client.JitStaticClientBuilder;
import io.jitstatic.client.TriFunction;
import io.jitstatic.hosted.HostedFactory;
import io.jitstatic.test.TemporaryFolder;
import io.jitstatic.test.TemporaryFolderExtension;
import io.jitstatic.tools.AUtils;

/*
 * This test is a stress test, and the expected behavior is that the commits will end up in order.
 * Two common "normal" errors:
 * WantNotValidException happens when the jgit client is out of sync when pulling.
 * Broken pipe. The jgit client sometimes suffers a broken pipe. Most likely due to that a connection isn't closed properly.
 * This bug happens in the jgit client.
 */
@ExtendWith({ TemporaryFolderExtension.class, DropwizardExtensionsSupport.class })
@Tag("slow")
public class LoadTesterIT {

    private static final Pattern PAT = Pattern.compile("^\\w:\\w:\\d+$");
    private static final String METADATA = ".metadata";
    private static final boolean log = false;
    static final String MASTER = "master";
    private static final Logger LOG = LoggerFactory.getLogger(LoadTesterIT.class);
    private static final String USER = "suser";
    private static final String PASSWORD = "ssecret";
    static final Charset UTF_8 = StandardCharsets.UTF_8;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private TemporaryFolder tmpfolder;
    private DropwizardAppExtension<JitstaticConfiguration> DW = new DropwizardAppExtension<>(JitstaticApplication.class,
            ResourceHelpers.resourceFilePath("simpleserver_silent.yaml"), ConfigOverride.config("hosted.basePath", getFolder()));

    private String gitAdress;
    private String adminAdress;
    private TestData data;
    private Counters putCounters;
    private Counters gitCounters;

    @BeforeEach
    public void setup()
            throws InvalidRemoteException, TransportException, GitAPIException, IOException, InterruptedException, URISyntaxException {
        final HostedFactory hf = DW.getConfiguration().getHostedFactory();
        int localPort = DW.getLocalPort();
        gitAdress = String.format("http://localhost:%d/application/%s/%s", localPort, hf.getServletName(), hf.getHostedEndpoint());
        adminAdress = String.format("http://localhost:%d/admin", localPort);
    }

    @AfterEach
    public void after() throws InvalidRemoteException, TransportException, GitAPIException, IOException {
        final HostedFactory hf = DW.getConfiguration().getHostedFactory();
        Client statsClient = buildClient("stats");
        try {
            log(() -> {
                Response response = statsClient.target(adminAdress + "/metrics").queryParam("pretty", true).request().get();
                LOG.info(response.readEntity(String.class));
                response.close();
            });
            File workingFolder = getFolderFile();
            try (Git git = Git.cloneRepository().setDirectory(workingFolder).setURI(gitAdress)
                    .setCredentialsProvider(getCredentials(hf)).call()) {
                LOG.info(data.toString());
                for (String branch : data.branches) {
                    try {
                        checkoutBranch(branch, git);
                        LOG.info("##### {} #####", branch);
                        Map<String, Integer> data = pairNamesWithData(workingFolder);
                        Map<String, Integer> cnt = new HashMap<>();
                        for (RevCommit rc : git.log().call()) {
                            String msg = matchData(data, cnt, rc);
                            log(() -> LOG.info("{}-{}--{}", rc.getId(), msg, rc.getAuthorIdent()));
                        }
                    } catch (IOException | GitAPIException e) {
                        LOG.error("Caught error when trying to read repository", e);
                    }
                }
            }
        } finally {
            statsClient.close();
        }
        LOG.info("Git updates: {}", gitCounters.updates);
        LOG.info("Git failures: {}", gitCounters.failiures);
        LOG.info("Put updates: {}", putCounters.updates);
        LOG.info("Put failures: {}", putCounters.failiures);
        LOG.info("Data sent: {}", putCounters.data);
        AUtils.checkContainerForErrors(DW);
    }

    private static void log(Runnable r) {
        if (log) {
            r.run();
        }
    }

    private String matchData(Map<String, Integer> data, Map<String, Integer> cnt, RevCommit rc) {
        String msg = rc.getShortMessage();
        Matcher matcher = PAT.matcher(msg);
        if (matcher.matches()) {
            String[] split = msg.split(":");
            Integer value = cnt.get(split[1]);
            Integer newValue = Integer.valueOf(split[2]);
            if (value != null) {
                assertEquals(Integer.valueOf(value.intValue() - 1), newValue, msg);
            } else {
                assertEquals(data.get(split[1]), newValue, msg);
            }
            cnt.put(split[1], newValue);
        } else {
            LOG.info("Message prints something else {}", msg);
        }
        return msg;
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

    @ParameterizedTest
    @ArgumentsSource(DataArgumentProvider.class)
    public void testLoad(TestData data) throws Exception {
        this.data = data;
        ConcurrentLinkedQueue<JitStaticUpdater> clients = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<GitClientUpdater> updaters = new ConcurrentLinkedQueue<>();
        final HostedFactory hf = DW.getConfiguration().getHostedFactory();
        int localPort = DW.getLocalPort();
        String gitAdress = String.format("http://localhost:%d/application/%s/%s", localPort, hf.getServletName(), hf.getHostedEndpoint());

        ExecutorService clientPool = Executors.newFixedThreadPool(Math.max(1, data.clients));
        ExecutorService updaterPool = Executors.newFixedThreadPool(Math.max(1, data.updaters));
        try {
            initRepo(getCredentials(hf), data);
            for (int i = 0; i < data.clients; i++) {
                clients.add(new JitStaticUpdater(buildKeyClient(data.cache)));
            }
            for (int i = 0; i < data.updaters; i++) {
                GitClientUpdater gitClientUpdater = new GitClientUpdater(gitAdress, hf.getUserName(), hf.getSecret());
                gitClientUpdater.initRepo();
                updaters.add(gitClientUpdater);
            }
            CompletableFuture<?>[] clientJobs = new CompletableFuture[data.clients];
            CompletableFuture<?>[] updaterJobs = new CompletableFuture[data.updaters];
            doWork(data, clients, updaters, clientPool, updaterPool, clientJobs, updaterJobs);
            waitForJobstoFinish(clientJobs, updaterJobs);
        } finally {
            putCounters = clients.stream().map(JitStaticUpdater::counter).reduce(Counters::add).orElse(new Counters(0, 0, 0));
            gitCounters = updaters.stream().map(GitClientUpdater::counter).reduce(Counters::add).orElse(new Counters(0, 0, 0));
            clients.stream().forEach(c -> {
                try {
                    c.close();
                } catch (Exception e1) {
                }
            });
            CompletableFuture.allOf(
                    CompletableFuture.runAsync(shutdownAndAwait(updaterPool)),
                    CompletableFuture.runAsync(shutdownAndAwait(clientPool))).get(15, TimeUnit.SECONDS);
        }
    }

    private static Runnable shutdownAndAwait(ExecutorService pool) {
        pool.shutdown();
        return () -> {
            try {
                pool.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e1) {
                // Ignore
            }
        };
    }

    private void doWork(TestData data, ConcurrentLinkedQueue<JitStaticUpdater> clients, ConcurrentLinkedQueue<GitClientUpdater> updaters,
            ExecutorService clientPool, ExecutorService updaterPool, CompletableFuture<?>[] clientJobs,
            CompletableFuture<?>[] updaterJobs) {
        long start = System.currentTimeMillis();
        do {
            if (Math.random() >= 0.5) {
                execClientJobs(clientPool, clientJobs, clients, data);
                execUpdatersJobs(updaterPool, updaterJobs, updaters, data);
            } else {
                execUpdatersJobs(updaterPool, updaterJobs, updaters, data);
                execClientJobs(clientPool, clientJobs, clients, data);
            }
        } while (System.currentTimeMillis() - start < 10_000);
        LOG.info("doWork is done!");
    }

    private void waitForJobstoFinish(CompletableFuture<?>[] clientJobs, CompletableFuture<?>[] updaterJobs)
            throws InterruptedException, ExecutionException, TimeoutException {
        CompletableFuture.allOf(
                CompletableFuture.runAsync(() -> waitForJobs(CompletableFuture.allOf(clientJobs), "direct clients")),
                CompletableFuture.runAsync(() -> waitForJobs(CompletableFuture.allOf(updaterJobs), "git clients"))).get(160, TimeUnit.SECONDS);
    }

    private void waitForJobs(CompletableFuture<Void> jobs, String type) {
        long start = System.currentTimeMillis();
        try {
            LOG.info("Waiting for {}...", type);
            jobs.get(100, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            LOG.error("Failed to wait {}", type, e);
        } finally {
            LOG.info("Waited {} ms to terminate {}", (System.currentTimeMillis() - start), type);
        }
    }

    private void execUpdatersJobs(ExecutorService updaterPool, CompletableFuture<?>[] updaterJobs, ConcurrentLinkedQueue<GitClientUpdater> updaters,
            TestData data) {
        for (int i = 0; i < updaterJobs.length; i++) {
            CompletableFuture<?> f = updaterJobs[i];
            if (f == null || f.isDone()) {
                updaterJobs[i] = CompletableFuture.runAsync(() -> {
                    GitClientUpdater cu = take(updaters);
                    try {
                        for (String branch : data.branches) {
                            for (String name : data.names) {
                                cu.updateClient(name, branch, data);
                            }
                        }
                    } catch (Exception e) {
                        LOG.error("TestSuiteError: Repo error ", e);
                    } finally {
                        updaters.add(cu);
                    }
                }, updaterPool);
            }
        }
    }

    private File getFolderFile() throws IOException {
        return tmpfolder.createTemporaryDirectory();
    }

    private void execClientJobs(ExecutorService clientPool, CompletableFuture<?>[] clientJobs,
            ConcurrentLinkedQueue<JitStaticUpdater> clients, TestData testData) {
        for (int i = 0; i < clientJobs.length; i++) {
            CompletableFuture<?> f = clientJobs[i];
            if (f == null || f.isDone()) {
                clientJobs[i] = CompletableFuture.runAsync(() -> {
                    try {
                        clientCode(clients, testData);
                    } catch (InterruptedException | IOException e) {
                        LOG.error("TestSuiteError: Interrupted ", e);
                    }
                }, clientPool);
            }
        }
    }

    private void initRepo(UsernamePasswordCredentialsProvider provider, TestData testData)
            throws InvalidRemoteException, TransportException, GitAPIException, IOException {
        LOG.info("Setting up repo");
        File workingFolder = getFolderFile();
        try (Git git = Git.cloneRepository().setDirectory(workingFolder).setURI(gitAdress).setCredentialsProvider(provider).call()) {
            int c = 0;
            byte[] data = testData.getData(c);
            writeFiles(testData, workingFolder, data);
            git.add().addFilepattern(".").call();
            git.commit().setMessage("i:a:0").call();
            assertTrue(verifyOkPush(git.push().setCredentialsProvider(provider).call(), MASTER, c));
            pushBranches(provider, testData, git, c);
        }
        LOG.info("Done setting up repo");
    }

    private void pushBranches(UsernamePasswordCredentialsProvider provider, TestData testData, Git git, int c)
            throws GitAPIException, RefAlreadyExistsException, RefNotFoundException, InvalidRefNameException, CheckoutConflictException,
            InvalidRemoteException, TransportException {
        for (String branch : testData.branches) {
            if (!MASTER.equals(branch)) {
                git.checkout().setName(branch).setCreateBranch(true).setUpstreamMode(SetupUpstreamMode.TRACK).call();
                assertTrue(verifyOkPush(git.push().setCredentialsProvider(provider).call(), branch, c));
            }
        }
    }

    private void writeFiles(TestData testData, File workingFolder, byte[] data) throws IOException {
        Path path = Paths.get(workingFolder.toURI());
        Path user = path.resolve(JitStaticConstants.USERS).resolve(JitStaticConstants.JITSTATIC_KEYADMIN_REALM).resolve(USER);
        assertTrue(user.getParent().toFile().mkdirs());
        Files.write(user, ("{\"roles\":[{\"role\":\"write\"},{\"role\":\"read\"}],\"basicPassword\":\"" + PASSWORD + "\"}").getBytes(UTF_8),
                CREATE_NEW, TRUNCATE_EXISTING);

        for (String name : testData.names) {
            Files.write(path.resolve(name), data, CREATE_NEW, TRUNCATE_EXISTING);
            Files.write(path.resolve(name + METADATA), testData.getMetaData(), CREATE_NEW, TRUNCATE_EXISTING);
        }
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

    private void clientCode(ConcurrentLinkedQueue<JitStaticUpdater> clients, TestData testData)
            throws InterruptedException, JsonParseException, JsonMappingException, IOException {
        JitStaticUpdater client = take(clients);
        try {
            for (String branch : testData.branches) {
                for (String name : testData.names) {
                    String ref = "refs/heads/" + branch;
                    try {
                        Entity entity = client.getKey(name, ref, LoadTesterIT::read);
                        String readValue = entity.getValue().toString();
                        if (Math.random() < 0.5) {
                            client.modifyKey(testData, branch, name, ref, entity, readValue);
                        }
                    } catch (SocketException se) {
                        LOG.error("Got socker error {}", se.getLocalizedMessage());
                    } catch (Exception e) {
                        LOG.error("TestSuiteError: Failed with ", e);
                    }
                }
            }
        } finally {
            clients.add(client);
        }
    }

    private <T> T take(ConcurrentLinkedQueue<T> clients) {
        T client;
        while ((client = clients.poll()) == null) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException ignore) {
            }
        }
        return client;
    }

    private static boolean verifyOkPush(Iterable<PushResult> iterable, String branch, int c) {
        PushResult pushResult = iterable.iterator().next();
        RemoteRefUpdate remoteUpdate = pushResult.getRemoteUpdate("refs/heads/" + branch);
        if (Status.OK == remoteUpdate.getStatus()) {
            return true;
        } else {
            log(() -> LOG.warn("TestSuiteError: FAILED push {} with {}", c, remoteUpdate));
        }
        return false;
    }

    private JitStaticClient buildKeyClient(boolean cache) throws URISyntaxException {
        int localPort = DW.getLocalPort();
        JitStaticClientBuilder builder = JitStaticClient.create()
                .setHost("localhost").setPort(localPort)
                .setAppContext("/application/")
                .setUser(USER).setPassword(PASSWORD);
        if (cache) {
            builder.setCacheConfig(CacheConfig.custom()
                    .setMaxCacheEntries(1000)
                    .setMaxObjectSize(8192).build())
                    .setHttpClientBuilder(CachingHttpClients.custom().disableAutomaticRetries());
        } else {
            builder.setHttpClientBuilder(HttpClients.custom().disableAutomaticRetries());
        }
        return builder.build();
    }

    private Client buildClient(final String name) {
        Environment environment = DW.getEnvironment();
        JerseyClientBuilder jerseyClientBuilder = new JerseyClientBuilder(environment);
        jerseyClientBuilder.setApacheHttpClientBuilder(new HttpClientBuilder(environment));
        return jerseyClientBuilder.build(name);
    }

    private Supplier<String> getFolder() {
        return () -> {
            try {
                return getFolderFile().toString();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        };
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

    private JsonNode readData(Path filedata) throws IOException, JsonParseException, JsonMappingException {
        try {
            return MAPPER.readValue(filedata.toFile(), JsonNode.class);
        } catch (Exception e) {
            LOG.error("Failed file looks like:{}", new String(Files.readAllBytes(filedata), UTF_8));
            throw e;
        }
    }

    private class JitStaticUpdater implements AutoCloseable {
        private final Counters counter;
        private final JitStaticClient updater;

        public JitStaticUpdater(JitStaticClient updater) {
            this.updater = updater;
            this.counter = new Counters(0, 0, 0);
        }

        public Entity getKey(String name, String ref, TriFunction<InputStream, String, String, Entity> object)
                throws ClientProtocolException, APIException, URISyntaxException, IOException {
            return updater.getKey(name, ref, object);
        }

        private void modifyKey(TestData testData, String branch, String name, String ref, Entity entity, String readValue) {
            int c = entity.getValue().get("data").asInt() + 1;
            byte[] data2 = testData.getData(c);
            CountingInputStream cis = new CountingInputStream(new ByteArrayInputStream(data2));
            try {
                String newTag = updater.modifyKey(cis, new CommitData(name, ref, "m:" + name + ":" + c, "user's name", "mail"),
                        entity.getTag());
                counter.updates++;
                log(() -> LOG.info("Ok modified {}:{} with {}", name, branch, c));
                if (Math.random() < 0.5) {
                    updater.getKey(name, branch, newTag, LoadTesterIT::read);
                }
            } catch (APIException e) {
                log(() -> LOG.info("TestSuiteError: Failed to modify {}:{}:{} {}", name, ref, c, e.getLocalizedMessage()));
                counter.failiures++;
            } catch (SocketException e) {
                counter.failiures++;
                LOG.error("TestSuiteError: Socket write error {}:{}:{} wrote {} of {} bytes", name, ref, c, cis.getCount(), data2.length,
                        e.getLocalizedMessage());
            } catch (Exception e) {
                counter.failiures++;
                LOG.error("TestSuiteError: General error {}:{}:{} wrote {} of {} bytes {}", name, ref, c, cis.getCount(), data2.length, e);
            } finally {
                counter.data += cis.getCount();
            }
        }

        public Counters counter() {
            return counter;
        }

        @Override
        public void close() throws Exception {
            updater.close();
        }
    }

    private class GitClientUpdater {

        private final File workingFolder;
        private final Counters counter;
        private final UsernamePasswordCredentialsProvider provider;
        private final String gitAdress;

        public GitClientUpdater(final String gitAdress, String name, String pass) throws IOException {
            this.workingFolder = getFolderFile();
            this.counter = new Counters(0, 0, 0);
            this.provider = getCredentials(name, pass);
            this.gitAdress = gitAdress;
        }

        public void initRepo() throws InvalidRemoteException, TransportException, GitAPIException, IOException {
            Git.cloneRepository().setDirectory(workingFolder).setURI(gitAdress).setCredentialsProvider(provider).call()
                    .close();
        }

        public void updateClient(String key, String branch, TestData testData)
                throws IOException, NoFilepatternException, GitAPIException {
            try (Git git = Git.open(workingFolder)) {
                Ref head = checkoutBranch(branch, git);
                try {
                    PullResult pr = git.pull().setCredentialsProvider(provider).call();
                    if (pr.isSuccessful()) {
                        Path filedata = Paths.get(workingFolder.toURI()).resolve(key);
                        JsonNode readData = readData(filedata);
                        int c = readData.get("data").asInt() + 1;
                        byte[] data = testData.getData(c);
                        Files.write(filedata, data, StandardOpenOption.CREATE, TRUNCATE_EXISTING);
                        DirCache dc = git.add().addFilepattern(".").call();
                        if (dc.getEntryCount() > 0) {
                            String message = "g:" + key + ":" + c;
                            git.commit().setMessage(message).call();
                            boolean ok = false;
                            try {
                                ok = verifyOkPush(git.push().setCredentialsProvider(provider).call(), branch, c);
                            } finally {
                                if (!ok) {
                                    git.reset().setMode(ResetType.HARD).setRef(head.getObjectId().name()).call();
                                    git.clean().setCleanDirectories(true).setForce(true).call();
                                }
                            }
                            if (ok) {
                                counter.updates++;
                                log(() -> compileAndLog(key, branch, readData, c, data, message));
                            } else {
                                counter.failiures++;
                            }
                        }
                    } else {
                        LOG.info("Failed to pull f:{} m:{}", pr.getFetchResult(), pr.getMergeResult());
                    }
                } catch (TransportException e) {
                    counter.failiures++;
                    Throwable cause = e.getCause();
                    if (cause == null) {
                        throw e;
                    }
                    if (!(cause instanceof org.eclipse.jgit.errors.TransportException)) {
                        throw e;
                    }
                    LOG.warn("Got known error {}", cause.getMessage());
                }
            }
        }

        private void compileAndLog(String key, String branch, JsonNode readData, int c, byte[] data, String message) {
            LOG.info("OK push {} {}:{} from {} to {} commit {}", c, key, branch, readData, new String(data, UTF_8), message);
        }

        public Counters counter() {
            return counter;
        }
    }

    private static class Entity {

        private final JsonNode value;
        private final String tag;

        public Entity(String tag, final JsonNode value2) {
            this.tag = tag;
            this.value = value2;
        }

        public JsonNode getValue() {
            return value;
        }

        public String getTag() {
            return tag;
        }

    }

    private static class Counters {
        private int updates;
        private int failiures;
        private int data;

        public Counters(final int gitUpdates, final int gitFailiures, int data) {
            this.updates = gitUpdates;
            this.failiures = gitFailiures;
            this.data = data;
        }

        public static Counters add(Counters a, Counters b) {
            return new Counters(a.updates + b.updates, a.failiures + b.failiures, a.data + b.data);
        }
    }
}
