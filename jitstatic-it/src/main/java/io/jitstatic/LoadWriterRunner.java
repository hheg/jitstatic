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

import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.http.impl.client.cache.CacheConfig;
import org.apache.http.impl.client.cache.CachingHttpClients;
import org.eclipse.jgit.api.CheckoutCommand;
import org.eclipse.jgit.api.CreateBranchCommand.SetupUpstreamMode;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RepositoryState;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.RemoteRefUpdate.Status;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.jitstatic.client.CommitData;
import io.jitstatic.client.JitStaticClient;
import io.jitstatic.client.JitStaticClientBuilder;

//TODO Remove this SpotBugs Error
@SuppressFBWarnings(value = "RCN_REDUNDANT_NULLCHECK_WOULD_HAVE_BEEN_A_NPE", justification = "This is a false positive in Java 11, should be removed")
public class LoadWriterRunner {
    private static final String USERS = ".users";
    private static final String JITSTATIC_KEYADMIN_REALM = "keyadmin";
    private static final Pattern PAT = Pattern.compile("^\\w+:\\w+:\\d+$");
    private static final boolean log = false;
    private static final Logger LOG = LoggerFactory.getLogger(LoadWriterRunner.class);
    private static final String USER = "suser";
    private static final String PASSWORD = "ssecret";
    static final Charset UTF_8 = StandardCharsets.UTF_8;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String METADATA = ".metadata";
    static final String MASTER = "master";

    private WriteData writeData;
    private Optional<ResultData> result = Optional.empty();

    private final List<DropwizardProcess> processess;
    private final int duration;

    public LoadWriterRunner(DropwizardProcess process) {
        this(List.of(process));
    }

    public LoadWriterRunner(List<DropwizardProcess> processess) {
        this(processess, 20_000);
    }

    public LoadWriterRunner(List<DropwizardProcess> processess, int duration) {
        if (processess.isEmpty()) {
            throw new IllegalArgumentException("The number of processes need to be more than one");
        }
        this.processess = processess;
        this.duration = duration;
    }

    public void testWrite(WriteData data) throws GitAPIException, InterruptedException, ExecutionException, TimeoutException, IOException {
        this.writeData = data;

        initRepo(getCredentials(processess.get(0).getUser(), processess.get(0).getPassword()), data);

        int size = data.branches.size() * data.names.size();
        ExecutorService service = Executors.newFixedThreadPool(size);
        try {
            @SuppressWarnings("unchecked")
            CompletableFuture<ResultData>[] jobs = new CompletableFuture[size];
            int j = 0;
            for (String branch : data.branches) {
                for (String key : data.names) {
                    jobs[j] = CompletableFuture.supplyAsync(() -> {
                        try (JitStaticClient buildKeyClient = buildKeyClient(false);) {
                            String tag = setupRun(buildKeyClient, branch, key);
                            ResultData resultData = execute(buildKeyClient, branch, key, tag, data);
                            printStats(resultData, resultData.iterations);
                            return resultData;
                        }
                    }, service);
                    j++;
                }
            }
            CompletableFuture.allOf(jobs).get(duration * 20, TimeUnit.MILLISECONDS);
            result = Stream.of(jobs).map(CompletableFuture::join).reduce(ResultData::sum);
        } finally {
            service.shutdown();
        }
    }

    private static void log(Runnable r) {
        if (log) {
            r.run();
        }
    }

    private double divide(long nominator,
            long denominator) {
        return nominator / ((double) denominator / 1000);
    }

    private ResultData execute(final JitStaticClient buildKeyClient,
            final String branch,
            final String key,
            String tag,
            WriteData testData) {
        int bytes = 0;
        long stop = 0;
        int i = 1;
        long start = System.currentTimeMillis();
        try {
            while ((stop = System.currentTimeMillis()) - start < duration) {
                byte[] data2 = testData.getData(i);
                bytes += data2.length;
                tag = buildKeyClient.modifyKey(data2, new CommitData(key, branch, "k:" + key + ":" + i, "userinfo", "mail"), tag);
                i++;
            }
        } catch (Exception e) {
            LOG.error("Error ", e);
        }
        return new ResultData(i - 1, (stop - start), bytes);
    }

    private String setupRun(JitStaticClient buildKeyClient,
            String branch,
            final String key) {
        try {
            return buildKeyClient.getKey(key, branch, LoadWriterRunner::read).tag;
        } catch (URISyntaxException | IOException e1) {
            throw new RuntimeException(e1);
        }
    }

    public void after() throws GitAPIException, IOException {
        log(() -> processess.stream().forEach(p -> {
            try {
                LOG.info(Unirest.get(p.getMetrics()).queryString("pretty", true).asString().getBody());
            } catch (UnirestException e) {
                throw new RuntimeException(e);
            }
        }));

        LOG.info("Checking repositories...");
        LOG.info("{}", writeData);
        AtomicInteger total = new AtomicInteger();
        List<List<RevCommit>> branchTips = processess.stream().map(dp -> {
            File workingFolder;
            try {
                workingFolder = dp.getFolderFile();
                try (Git git = Git.cloneRepository().setDirectory(workingFolder).setURI(dp.getGitAddress())
                        .setCredentialsProvider(getCredentials(processess.get(0).getUser(), processess.get(0).getPassword())).call()) {
                    LOG.info("Checking repository {}...", dp.getGitAddress());
                    return writeData.branches.stream().map(branch -> {
                        try {
                            checkoutBranch(branch, git);
                            LOG.info("##### {} #####", branch);
                            Map<String, Integer> pairs = pairNamesWithData(workingFolder);
                            Map<String, Integer> cnt = new HashMap<>();
                            RevCommit last = null;
                            for (RevCommit rc : git.log().call()) {
                                String msg = matchData(pairs, cnt, rc, branch);
                                log(() -> LOG.info("{}-{}--{}", rc.getId(), msg, rc.getAuthorIdent()));
                                if (last == null) {
                                    last = rc;
                                }
                            }
                            total.addAndGet(pairs.values().stream().mapToInt(Integer::intValue).sum());
                            return last;
                        } catch (IOException | GitAPIException e) {
                            LOG.error("Caught error when trying to read repository", e);
                            return null;
                        }
                    }).filter(Objects::nonNull).collect(Collectors.toList());
                } catch (GitAPIException e1) {
                    LOG.error("Failed to check repo", e1);
                    return List.<RevCommit>of();
                }
            } catch (IOException e2) {
                LOG.error("Failed to check repo", e2);
                return List.<RevCommit>of();
            }
        }).collect(Collectors.toList());
        List<RevCommit> firstResult = branchTips.get(0);
        assertEquals(firstResult.size(), writeData.branches.size(), "The first result doesn't match the number of recorded branches");
        if (branchTips.size() > 1) {
            List<List<RevCommit>> remaining = branchTips.subList(1, branchTips.size());
            for (int j = 0; j < remaining.size(); j++) {
                List<RevCommit> part = remaining.get(j);
                assertEquals(firstResult.size(), part.size(), "Results doesn't contain the same branches");
                for (int i = 0; i < firstResult.size(); i++) {
                    assertEquals(firstResult.get(i), part.get(i), "Result were different in " + processess.get(i).getGitAddress());
                }
            }
        }
        result.ifPresent(r -> {
            printStats(r, total.get());
            assertEquals(r.iterations, total.get(), "Total iterations doesn't match");
        });
        processess.stream().forEach(DropwizardProcess::checkContainerForErrors);

    }

    private void printStats(ResultData r,
            int total) {
        LOG.info("Thread: {}  Iters: {}/{} time: {} ms length: {} B Writes: {} /s Bytes: {} B/s", Thread.currentThread()
                .getName(), r.iterations, total, r.duration, r.bytes, divide(r.iterations, r.duration), divide(r.bytes, r.duration));
    }

    private void initRepo(UsernamePasswordCredentialsProvider provider,
            WriteData testData) throws GitAPIException, IOException {
        File workingFolder = processess.get(0).getFolderFile();
        try (Git git = Git.cloneRepository().setDirectory(workingFolder).setURI(processess.get(0).getGitAddress()).setCredentialsProvider(provider).call()) {
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

    private void pushBranches(UsernamePasswordCredentialsProvider provider,
            WriteData testData,
            Git git,
            int c)
            throws GitAPIException {
        for (String branch : testData.branches) {
            if (!MASTER.equals(branch)) {
                git.checkout().setName(branch).setCreateBranch(true).setUpstreamMode(SetupUpstreamMode.TRACK).call();
                assertTrue(verifyOkPush(git.push().setCredentialsProvider(provider).call(), branch, c));
            }
        }
    }

    private static byte[] getMetaData() {
        String md = "{\"users\":[],\"contentType\":\"application/json\",\"protected\":false,\"hidden\":false,\"read\":[{\"role\":\"read\"}],\"write\":[{\"role\":\"write\"}]}}";
        return md.getBytes(UTF_8);
    }

    private boolean verifyOkPush(Iterable<PushResult> iterable,
            String branch,
            int c) {
        PushResult pushResult = iterable.iterator().next();
        RemoteRefUpdate remoteUpdate = pushResult.getRemoteUpdate("refs/heads/" + branch);
        if (Status.OK == remoteUpdate.getStatus()) {
            return true;
        } else {
            LOG.error("TestSuiteError: FAILED push {} with {}", c, remoteUpdate);
        }
        return false;
    }

    private void writeFiles(WriteData testData,
            File workingFolder,
            byte[] data) throws IOException {
        Path path = Paths.get(workingFolder.toURI());
        Path user = path.resolve(USERS).resolve(JITSTATIC_KEYADMIN_REALM).resolve(USER);
        assertTrue(user.getParent().toFile().mkdirs());
        Files.write(user, ("{\"roles\":[{\"role\":\"write\"},{\"role\":\"read\"}],\"basicPassword\":\"" + PASSWORD + "\"}")
                .getBytes(UTF_8), CREATE_NEW, TRUNCATE_EXISTING);

        for (String name : testData.names) {
            Files.write(Paths.get(workingFolder.toURI()).resolve(name), data, CREATE_NEW, TRUNCATE_EXISTING);
            Files.write(Paths.get(workingFolder.toURI()).resolve(name + METADATA), getMetaData(), CREATE_NEW, TRUNCATE_EXISTING);
        }
    }

    private JitStaticClient buildKeyClient(boolean cache) {
        int localPort = processess.get(0).getLocalPort();
        JitStaticClientBuilder builder = JitStaticClient.create().setHost("localhost").setPort(localPort)
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

    private Ref checkoutBranch(String branch,
            Git git) throws IOException, GitAPIException {
        git.checkout().setAllPaths(true).call();
        Ref head = git.getRepository().findRef(branch);
        if (git.getRepository().getRepositoryState() != RepositoryState.SAFE) {
            git.reset().setMode(ResetType.HARD).setRef(head.getObjectId().name()).call();
            git.clean().setCleanDirectories(true).setForce(true).call();
        }
        CheckoutCommand checkout = git.checkout()
                .setName(branch).setUpstreamMode(SetupUpstreamMode.TRACK)
                .setStartPoint("origin/" + branch);
        if (head == null) {
            checkout.setCreateBranch(true);
        }
        checkout.call();
        head = git.getRepository().findRef(branch);
        return head;
    }

    private String matchData(Map<String, Integer> data,
            Map<String, Integer> cnt,
            RevCommit rc,
            String ref) {
        String msg = rc.getShortMessage();
        Matcher matcher = PAT.matcher(msg);
        if (matcher.matches()) {
            String[] split = msg.split(":");
            Integer value = cnt.get(split[1]);
            Integer newValue = Integer.valueOf(split[2]);
            if (value != null) {
                assertEquals(Integer.valueOf(value.intValue() - 1), newValue, ref + " " + msg);
            } else {
                assertEquals(data.get(split[1]), newValue, msg);
            }
            cnt.put(split[1], newValue);
        } else {
            LOG.info("Message prints something else {}", msg);
        }
        return msg;
    }

    private static UsernamePasswordCredentialsProvider getCredentials(String name,
            String pass) {
        return new UsernamePasswordCredentialsProvider(name, pass);
    }

    private Map<String, Integer> pairNamesWithData(File workingFolder) throws IOException {
        Map<String, Integer> data = new HashMap<>();
        for (String name : this.writeData.names) {
            int d = readData(Paths.get(workingFolder.toURI()).resolve(name)).get("data").asInt();
            data.put(name, d);
            LOG.info("{} contains {}", name, d);
        }
        return data;
    }

    private JsonNode readData(Path filedata) throws IOException {
        try {
            return MAPPER.readValue(filedata.toFile(), JsonNode.class);
        } catch (Exception e) {
            LOG.error("Failed file looks like:{}", new String(Files.readAllBytes(filedata), UTF_8));
            throw e;
        }
    }

    private static Entity read(InputStream is,
            String tag,
            String contentType) {
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

        static ResultData sum(ResultData a,
                ResultData b) {
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
