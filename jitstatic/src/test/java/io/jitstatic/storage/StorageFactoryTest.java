package io.jitstatic.storage;

/*-
 * #%L
 * jitstatic
 * %%
 * Copyright (C) 2017 H.Hegardt
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;

import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.codahale.metrics.MetricRegistry;

import io.dropwizard.auth.AuthDynamicFeature;
import io.dropwizard.auth.AuthValueFactoryProvider;
import io.dropwizard.jersey.setup.JerseyEnvironment;
import io.dropwizard.setup.Environment;
import io.jitstatic.hosted.events.ReloadRefEventListener;
import io.jitstatic.source.Source;
import io.jitstatic.storage.ref.LocalRefLockService;
import io.jitstatic.storage.ref.RefLockService;
import io.jitstatic.test.BaseTest;

public class StorageFactoryTest extends BaseTest {

    private static final String ROOT = "root";
    private static final String REFS_HEADS_MASTER = "refs/heads/master";
    private Environment env = mock(Environment.class);
    private JerseyEnvironment jersey = mock(JerseyEnvironment.class);
    private Source source = mock(Source.class);
    private HashService hashService = new HashService();

    private LocalRefLockService clusterService;

    private StorageFactory sf = new StorageFactory();

    private ExecutorService defaultExecutor;
    private ExecutorService workStealer;
    private MetricRegistry registry;
    private BiPredicate<String, String> rootAuth = (u, p) -> false;

    @BeforeEach
    public void setup() {
        defaultExecutor = Executors.newCachedThreadPool(new NamingThreadFactory("test"));
        workStealer = Executors.newWorkStealingPool();
        registry = new MetricRegistry();
        clusterService = new LocalRefLockService(registry);
    }

    @AfterEach
    public void tearDown() throws Exception {
        clusterService.close();
        Exception e1 = BaseTest.shutdownExecutor(defaultExecutor);
        Exception e2 = BaseTest.shutdownExecutor(workStealer);
        if (e1 != null) {
            if (e2 != null) {
                e1.addSuppressed(e2);
            }
            throw e1;
        }
        if (e2 != null) {
            throw e2;
        }
    }

    @Test
    public void testBuild() throws InterruptedException, ExecutionException, IOException, RefNotFoundException {
        when(env.jersey()).thenReturn(jersey);
        when(env.metrics()).thenReturn(registry);
        try (Storage storage = sf
                .build(source, env, REFS_HEADS_MASTER, hashService, ROOT, clusterService, defaultExecutor, workStealer, rootAuth);) {
            assertEquals(Optional.empty(), storage.getKey("key", null).orTimeout(10, TimeUnit.SECONDS).join());
        }
        verify(jersey).register(isA(AuthDynamicFeature.class));
        verify(jersey).register(isA(AuthValueFactoryProvider.Binder.class));
    }

    @Test
    public void testEmptyStoragePath() {
        when(env.jersey()).thenReturn(jersey);
        when(env.metrics()).thenReturn(registry);
        assertEquals("Source cannot be null", assertThrows(NullPointerException.class, () -> {
            try (Storage storage = sf
                    .build(null, env, REFS_HEADS_MASTER, hashService, ROOT, clusterService, defaultExecutor, workStealer, rootAuth);) {
            }
        }).getLocalizedMessage());
    }

    @Test
    public void testListener() {
        when(env.jersey()).thenReturn(jersey);
        when(env.metrics()).thenReturn(registry);
        try (Storage build = sf
                .build(source, env, REFS_HEADS_MASTER, hashService, ROOT, clusterService, defaultExecutor, workStealer, rootAuth);) {
            ArgumentCaptor<ReloadRefEventListener> c = ArgumentCaptor.forClass(ReloadRefEventListener.class);
            verify(source).addListener(c.capture(), Mockito.eq(ReloadRefEventListener.class));
            c.getValue().onReload(REFS_HEADS_MASTER);
        }
    }

    @Test
    public void testListenerWithNullArgument() {
        when(env.jersey()).thenReturn(jersey);
        when(env.metrics()).thenReturn(registry);
        try (Storage build = sf
                .build(source, env, REFS_HEADS_MASTER, hashService, ROOT, clusterService, defaultExecutor, workStealer, rootAuth);) {
            ArgumentCaptor<ReloadRefEventListener> c = ArgumentCaptor.forClass(ReloadRefEventListener.class);
            verify(source).addListener(c.capture(), Mockito.eq(ReloadRefEventListener.class));
            assertThrows(NullPointerException.class, () -> c.getValue().onReload(null));
        }
    }
}
