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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.io.UnsupportedEncodingException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.ws.rs.client.Client;
import javax.ws.rs.core.Response;

import org.apache.http.impl.client.cache.CacheConfig;
import org.apache.http.impl.client.cache.CachingHttpClients;
import org.eclipse.jgit.api.CheckoutCommand;
import org.eclipse.jgit.api.CreateBranchCommand.SetupUpstreamMode;
import org.eclipse.jgit.api.Git;
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
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.RuleChain;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.health.HealthCheck.Result;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.dropwizard.client.HttpClientBuilder;
import io.dropwizard.client.JerseyClientBuilder;
import io.dropwizard.setup.Environment;
import io.dropwizard.testing.ConfigOverride;
import io.dropwizard.testing.ResourceHelpers;
import io.dropwizard.testing.junit.DropwizardAppRule;
import io.jitstatic.JitstaticApplication;
import io.jitstatic.JitstaticConfiguration;
import io.jitstatic.LoadTest;
import io.jitstatic.client.APIException;
import io.jitstatic.client.CommitData;
import io.jitstatic.client.JitStaticUpdaterClient;
import io.jitstatic.client.JitStaticUpdaterClientBuilder;
import io.jitstatic.hosted.HostedFactory;

/*
 * This test is a stress test, and the expected behavior is that the commits will end up in order.
 */
@RunWith(Parameterized.class)
@Category(LoadTest.class)
public class LoadTesterTest {

    private static final Pattern PAT = Pattern.compile("^\\w:\\w:\\d+$");
    private static final String METADATA = ".metadata";
    private static final String MASTER = "master";
    private static final Logger LOG = LoggerFactory.getLogger(LoadTesterTest.class);
    private static final String USER = "suser";
    private static final String PASSWORD = "ssecret";
    private static final String UTF_8 = "UTF-8";
    private static final TemporaryFolder TMP_FOLDER = new TemporaryFolder();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AtomicInteger GITUPDATES = new AtomicInteger(0);
    private static final AtomicInteger PUTUPDATES = new AtomicInteger(0);
    private static final AtomicInteger GITFAILURES = new AtomicInteger(0);
    private static final AtomicInteger PUTFAILURES = new AtomicInteger(0);
    private static final int A_CLIENTS = 10;
    private static final int A_UPDTRS = 10;

    private final DropwizardAppRule<JitstaticConfiguration> DW;
    private final BlockingQueue<JitStaticUpdaterClient> clients = new LinkedBlockingDeque<>(A_CLIENTS);
    private final BlockingQueue<ClientUpdater> updaters = new LinkedBlockingQueue<>(A_UPDTRS);
    private final String[] names;
    private final String[] branches;
    private final boolean cache;

    @Parameterized.Parameters
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][] { { new String[] { "a" }, new String[] { MASTER }, false },
                { new String[] { "a", "b", "c" }, new String[] { MASTER, "develop", "something" }, false },
                { new String[] { "a" }, new String[] { MASTER }, true },
                { new String[] { "a", "b", "c" }, new String[] { MASTER, "develop", "something" }, true } });
    }

    public LoadTesterTest(String[] names, String[] branches, boolean cache) {
        this.cache = cache;
        this.names = names;
        this.branches = branches;
        this.versions = new ConcurrentHashMap<>();
        for (String branch : branches) {
            for (String name : names) {
                Map<String, String> m = new ConcurrentHashMap<>();
                m.put(name, "nothing");
                versions.put(branch, m);
            }
        }
    }

    @Rule
    public final RuleChain chain = RuleChain.outerRule(TMP_FOLDER).around((DW = new DropwizardAppRule<>(JitstaticApplication.class,
            ResourceHelpers.resourceFilePath("simpleserver.yaml"), ConfigOverride.config("hosted.basePath", getFolder()))));

    private final Map<String, Map<String, String>> versions;
    private UsernamePasswordCredentialsProvider provider;

    private String gitAdress;
    private String adminAdress;

    @Before
    public synchronized void setup() throws InvalidRemoteException, TransportException, GitAPIException, IOException, InterruptedException, URISyntaxException {
        final HostedFactory hf = DW.getConfiguration().getHostedFactory();
        provider = new UsernamePasswordCredentialsProvider(hf.getUserName(), hf.getSecret());
        int localPort = DW.getLocalPort();
        gitAdress = String.format("http://localhost:%d/application/%s/%s", localPort, hf.getServletName(), hf.getHostedEndpoint());
        adminAdress = String.format("http://localhost:%d/admin", localPort);
        for (int i = 0; i < A_CLIENTS; i++) {
            clients.add(buildKeyClient());
        }
        initRepo();
        for (int i = 0; i < A_UPDTRS; i++) {
            updaters.put(new ClientUpdater(gitAdress, provider));
        }
        GITFAILURES.set(0);
        GITUPDATES.set(0);
        PUTFAILURES.set(0);
        PUTUPDATES.set(0);
    }

    @After
    public void after() throws InvalidRemoteException, TransportException, GitAPIException, IOException {
        SortedMap<String, Result> healthChecks = DW.getEnvironment().healthChecks().runHealthChecks();
        List<Throwable> errors = healthChecks.entrySet().stream().map(e -> e.getValue().getError()).filter(Objects::nonNull).collect(Collectors.toList());
        errors.stream().forEach(e -> e.printStackTrace());
        assertThat(errors.toString(), errors.isEmpty(), Matchers.is(true));
        Client statsClient = buildClient("stats");
        try {
            Response response = statsClient.target(adminAdress + "/metrics").queryParam("pretty", true).request().get();
            try {
                LOG.info(response.readEntity(String.class));
                File workingFolder = TMP_FOLDER.newFolder();
                try (Git git = Git.cloneRepository().setDirectory(workingFolder).setURI(gitAdress).setCredentialsProvider(provider).call()) {
                    for (String branch : branches) {
                        checkoutBranch(branch, git);
                        LOG.info("##### {} #####", branch);
                        Map<String, Integer> data = new HashMap<>();
                        for (String name : names) {
                            int d = readData(Paths.get(workingFolder.toURI()).resolve(name));
                            data.put(name, d);
                            LOG.info("{} contains {}", name, d);
                        }
                        Map<String, Integer> cnt = new HashMap<>();
                        for (RevCommit rc : git.log().call()) {
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
                            LOG.info("{}-{}--{}", rc.getId(), msg, rc.getAuthorIdent());
                        }
                    }
                }
            } finally {
                response.close();
            }
        } finally {
            statsClient.close();
            clients.stream().forEach(c -> {
                try {
                    c.close();
                } catch (Exception e1) {
                }
            });
        }
        LOG.info("Git updates: {}", GITUPDATES.get());
        LOG.info("Git failures: {}", GITFAILURES.get());
        LOG.info("Put updates: {}", PUTUPDATES.get());
        LOG.info("Put failures: {}", PUTFAILURES.get());
    }

    @Test
    public void testLoad() {
        ExecutorService clientPool = Executors.newFixedThreadPool(A_CLIENTS);
        ExecutorService updaterPool = Executors.newFixedThreadPool(A_UPDTRS);
        Future<?>[] clientJobs = new Future[A_CLIENTS];
        Future<?>[] updaterJobs = new Future[A_UPDTRS];
        long start = System.currentTimeMillis();
        do {
            execClientJobs(clientPool, clientJobs);
            execUpdatersJobs(updaterPool, updaterJobs);
        } while (System.currentTimeMillis() - start < 10_000);
        waitForJobstoFinish(clientJobs, updaterJobs);
        updaterPool.shutdown();
        clientPool.shutdown();
    }

    private void waitForJobstoFinish(Future<?>[] clientJobs, Future<?>[] updaterJobs) {
        int idx = clientJobs.length + updaterJobs.length;
        while (idx > 0) {
            idx = completeJobs(clientJobs, idx);
            idx = completeJobs(updaterJobs, idx);
        }
    }

    private int completeJobs(Future<?>[] jobs, int idx) {
        for (int i = 0; i < jobs.length; i++) {
            Future<?> f = jobs[i];
            if (f != null && f.isDone()) {
                jobs[i] = null;
                idx--;
            }
        }
        return idx;
    }

    private void execUpdatersJobs(ExecutorService updaterPool, Future<?>[] updaterJobs) {
        for (int i = 0; i < updaterJobs.length; i++) {
            Future<?> f = updaterJobs[i];
            if (f == null || f.isDone()) {
                updaterJobs[i] = updaterPool.submit(() -> {
                    ClientUpdater cu;
                    try {
                        cu = updaters.take();
                        try {
                            for (String branch : branches) {
                                for (String name : names) {
                                    cu.updateClient(name, branch);
                                }
                            }
                        } catch (GitAPIException | IOException e) {
                            LOG.error("TestSuiteError: Repo error ", e);
                        } finally {
                            updaters.put(cu);
                        }
                    } catch (InterruptedException e1) {
                        LOG.error("TestSuiteError: Updater interrupted and killed", e1);
                    }
                });
            }
        }
    }

    private void execClientJobs(ExecutorService clientPool, Future<?>[] clientJobs) {
        for (int i = 0; i < clientJobs.length; i++) {
            Future<?> f = clientJobs[i];
            if (f == null || f.isDone()) {
                clientJobs[i] = clientPool.submit(() -> {
                    try {
                        clientCode();
                    } catch (InterruptedException | IOException e) {
                        LOG.error("TestSuiteError: Interrupted ", e);
                    }
                });
            }
        }
    }

    private void initRepo() throws InvalidRemoteException, TransportException, GitAPIException, IOException {
        File workingFolder = TMP_FOLDER.newFolder();
        try (Git git = Git.cloneRepository().setDirectory(workingFolder).setURI(gitAdress).setCredentialsProvider(provider).call()) {
            int c = 0;
            byte[] data = getData(c);
            String v = new String(data);
            for (String name : names) {
                Files.write(Paths.get(workingFolder.toURI()).resolve(name), data, StandardOpenOption.CREATE_NEW, StandardOpenOption.TRUNCATE_EXISTING);
                Files.write(Paths.get(workingFolder.toURI()).resolve(name + METADATA), getMetaData(), StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.TRUNCATE_EXISTING);
            }
            git.add().addFilepattern(".").call();
            git.commit().setMessage("i:a:0").call();
            verifyOkPush(git.push().setCredentialsProvider(provider).call(), MASTER, c);
            for (String branch : branches) {
                if (!MASTER.equals(branch)) {
                    git.checkout().setName(branch).setCreateBranch(true).setUpstreamMode(SetupUpstreamMode.TRACK).call();
                    verifyOkPush(git.push().setCredentialsProvider(provider).call(), branch, c);
                }
                for (String name : names) {
                    versions.get(branch).put(name, v);
                }
            }
        }
    }

    private static byte[] getMetaData() throws UnsupportedEncodingException {
        String md = "{\"users\":[{\"password\":\"" + PASSWORD + "\",\"user\":\"" + USER + "\"}]}";
        return md.getBytes(UTF_8);
    }

    private static byte[] getData(int c) throws UnsupportedEncodingException {
        String s = "{\"data\":\"" + c + "\"}";
        return s.getBytes(UTF_8);
    }

    private static Entity read(InputStream is, String tag, String contentType) {
        if (is != null) {
            try {
                return new Entity(tag, MAPPER.readValue(is, JsonNode.class));
            } catch (IOException e) {
                LOG.error("ERROR READING ENTITY");
                throw new UncheckedIOException(e);
            }
        }
        return new Entity(tag, null);
    }

    private void clientCode() throws InterruptedException, JsonParseException, JsonMappingException, IOException {
        JitStaticUpdaterClient client = clients.take();
        try {
            for (String branch : branches) {
                for (String name : names) {
                    String ref = "refs/heads/" + branch;
                    try {
                        Entity entity = client.getKey(name, ref, LoadTesterTest::read);
                        String v = versions.get(branch).get(name);
                        if (!v.equals(entity.getValue().toString())) {
                            LOG.error("TestSuiteError: Version comparison " + name + ":" + branch + " failed version=" + v + " actual="
                                    + entity.getValue().toString());
                        } else {
                            if (Math.random() < 0.5) {
                                int c = entity.getValue().get("data").asInt() + 1;
                                try {
                                    byte[] data = getData(c);
                                    String newTag = client.modifyKey(data, new CommitData(name, ref, "m:" + name + ":" + c, "user's name", "mail"),
                                            entity.getTag());
                                    PUTUPDATES.incrementAndGet();
                                    versions.get(branch).put(name, new String(data));
                                    LOG.info("Ok modified " + name + ":" + branch + " with " + c);
                                    if (Math.random() < 0.5) {
                                        client.getKey(name, branch, newTag, LoadTesterTest::read);
                                    }
                                } catch (APIException e) {
                                    LOG.error("TestSuiteError: Failed to modify " + c + " " + e.getMessage());
                                    PUTFAILURES.incrementAndGet();
                                } catch (Exception e) {
                                    PUTFAILURES.incrementAndGet();
                                    LOG.error("TestSuiteError: General error " + c, e);
                                }
                            }
                        }
                    } catch (Exception e) {
                        LOG.error("TestSuiteError: Failed with ", e);
                    }
                }
            }
        } finally {
            clients.put(client);
        }
    }

    private static boolean verifyOkPush(Iterable<PushResult> iterable, String branch, int c) throws UnsupportedEncodingException {
        PushResult pushResult = iterable.iterator().next();
        RemoteRefUpdate remoteUpdate = pushResult.getRemoteUpdate("refs/heads/" + branch);
        if (Status.OK == remoteUpdate.getStatus()) {
            GITUPDATES.incrementAndGet();
            return true;
        } else {
            GITFAILURES.incrementAndGet();
            LOG.error("TestSuiteError: FAILED push " + c + " with " + remoteUpdate);
        }
        return false;
    }

    private JitStaticUpdaterClient buildKeyClient() throws URISyntaxException {
        JitStaticUpdaterClientBuilder builder = JitStaticUpdaterClient.create().setHost("localhost").setPort(DW.getLocalPort())
                .setAppContext("/application/").setUser(USER).setPassword(PASSWORD);
        if (cache) {
            builder.setCacheConfig(CacheConfig.custom().setMaxCacheEntries(1000).setMaxObjectSize(8192).build())
                    .setHttpClientBuilder(CachingHttpClients.custom());
        }
        return builder.build();
    }

    private Client buildClient(final String name) {
        Environment environment = DW.getEnvironment();
        JerseyClientBuilder jerseyClientBuilder = new JerseyClientBuilder(environment);
        jerseyClientBuilder.setApacheHttpClientBuilder(new HttpClientBuilder(environment));
        return jerseyClientBuilder.build(name);
    }

    private static Supplier<String> getFolder() {
        return () -> {
            try {
                return TMP_FOLDER.newFolder().toString();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        };
    }

    private Ref checkoutBranch(String branch, Git git)
            throws IOException, GitAPIException, RefAlreadyExistsException, RefNotFoundException, InvalidRefNameException, CheckoutConflictException {
        git.checkout().setAllPaths(true).call();
        Ref head = git.getRepository().findRef(branch);
        if (git.getRepository().getRepositoryState() != RepositoryState.SAFE) {
            git.reset().setMode(ResetType.HARD).setRef(head.getObjectId().name()).call();
            git.clean().setCleanDirectories(true).setForce(true).call();
        }
        CheckoutCommand checkout = git.checkout().setName(branch).setUpstreamMode(SetupUpstreamMode.TRACK).setStartPoint("origin/" + branch);
        if (head == null) {
            checkout.setCreateBranch(true);
        }
        checkout.call();
        head = git.getRepository().findRef(branch);
        return head;
    }

    private int readData(Path filedata) throws IOException, JsonParseException, JsonMappingException {
        try {
            JsonNode readValue = MAPPER.readValue(filedata.toFile(), JsonNode.class);
            return readValue.get("data").asInt();
        } catch (Exception e) {
            LOG.error("Failed file looks like:" + new String(Files.readAllBytes(filedata)));
            throw e;
        }
    }

    private class ClientUpdater {

        private final File workingFolder;
        private final UsernamePasswordCredentialsProvider provider;

        public ClientUpdater(final String gitAdress, final UsernamePasswordCredentialsProvider provider)
                throws InvalidRemoteException, TransportException, GitAPIException, IOException {
            this.workingFolder = TMP_FOLDER.newFolder();
            Git.cloneRepository().setDirectory(workingFolder).setURI(gitAdress).setCredentialsProvider(provider).call().close();
            this.provider = provider;
        }

        public void updateClient(String name, String branch) throws UnsupportedEncodingException, IOException, NoFilepatternException, GitAPIException {
            try (Git git = Git.open(workingFolder)) {
                Ref head = checkoutBranch(branch, git);
                git.pull().setCredentialsProvider(provider).call();
                Path filedata = Paths.get(workingFolder.toURI()).resolve(name);
                int c = readData(filedata) + 1;
                byte[] data = getData(c);
                Files.write(filedata, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                DirCache dc = git.add().addFilepattern(".").call();
                if (dc.getEntryCount() > 0) {
                    String message = "g:" + name + ":" + c;
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
                        String v = new String(data);
                        LOG.info("OK push " + c + " " + name + ":" + branch + " from " + versions.get(branch).put(name, v) + " to " + v + " commit " + message);
                    }
                }
            }
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
}
