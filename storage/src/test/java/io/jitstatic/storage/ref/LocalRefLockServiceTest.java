package io.jitstatic.storage.ref;

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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;

import org.junit.jupiter.api.Test;

import com.codahale.metrics.MetricRegistry;

import io.jitstatic.source.Source;
import io.jitstatic.storage.HashService;

class LocalRefLockServiceTest {

    @Test
    void testReturnLockService() throws Exception {
        MetricRegistry registry = new MetricRegistry();
        Source source = mock(Source.class);
        HashService hashService = mock(HashService.class);
        ExecutorService workstealingExecutor = ForkJoinPool.commonPool();
        try (LocalRefLockService service = new LocalRefLockService(registry);) {
            LockService lockService = service.getLockService("refs/heads/master", workstealingExecutor, source, hashService);
            service.returnLock(lockService);
            LockService lockService2 = service.getLockService("refs/heads/master", workstealingExecutor, source, hashService);
            assertSame(lockService, lockService2);
        }
    }
}
