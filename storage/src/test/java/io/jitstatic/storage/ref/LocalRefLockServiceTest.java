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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.codahale.metrics.MetricRegistry;
import com.spencerwi.either.Either;

import io.jitstatic.hosted.FailedToLock;
import io.jitstatic.storage.ref.ActionData;
import io.jitstatic.storage.ref.LocalRefLockService;
import io.jitstatic.storage.ref.LockService;
import io.jitstatic.storage.ref.RefHolder;

class LocalRefLockServiceTest {

    @Test
    void testFireKeyEvent() throws Exception {
        MetricRegistry registry = new MetricRegistry();
        try (LocalRefLockService service = new LocalRefLockService(registry);) {
            RefHolder refHolder = mock(RefHolder.class);
            LockService lockService = service.getLockService("refs/heads/master");
            AtomicBoolean b = new AtomicBoolean(true);
            Mockito.when(refHolder.internalAddKey(Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any())).thenAnswer((a) -> {
                while (b.get())
                    ;
                return "result";
            });
            lockService.register(refHolder);
            CompletableFuture<Either<String, FailedToLock>> event = lockService.fireEvent("key", ActionData.addKey("key", null, null, null));
            b.set(false);
            Either<String, FailedToLock> result = event.join();
            assertEquals("result", result.getLeft());
        }
    }

    @Test
    void testReturnLockService() throws Exception {
        MetricRegistry registry = new MetricRegistry();
        try (LocalRefLockService service = new LocalRefLockService(registry);) {
            LockService lockService = service.getLockService("refs/heads/master");
            service.returnLock(lockService);
            LockService lockService2 = service.getLockService("refs/heads/master");
            assertSame(lockService, lockService2);
        }
    }
}
