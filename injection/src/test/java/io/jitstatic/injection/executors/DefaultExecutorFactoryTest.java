package io.jitstatic.injection.executors;

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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ExecutorService;

import org.junit.jupiter.api.Test;

import com.codahale.metrics.MetricRegistry;

import io.jitstatic.injection.executors.DefaultExecutorFactory;

public class DefaultExecutorFactoryTest {
    @Test
    public void testRegistration() {
        DefaultExecutorFactory def = new DefaultExecutorFactory(new MetricRegistry());
        ExecutorService executorService = def.provide();
        assertNotNull(executorService);
        assertFalse(executorService.isTerminated());
        def.dispose(executorService);
        assertTrue(executorService.isShutdown());
        assertTrue(executorService.isTerminated());
    }

    @Test
    public void testAnnotation() {
        assertSame(DefaultExecutorAnnotation.INSTANCE, DefaultExecutorAnnotation.INSTANCE);
    }
}
