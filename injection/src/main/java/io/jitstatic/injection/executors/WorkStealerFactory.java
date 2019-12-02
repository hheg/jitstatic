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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.glassfish.hk2.api.Factory;

import com.codahale.metrics.InstrumentedExecutorService;
import com.codahale.metrics.MetricRegistry;

public class WorkStealerFactory implements Factory<ExecutorService> {

    private ExecutorService executorService;
    
    @Inject
    public WorkStealerFactory(final MetricRegistry metricRegistry) {
        this.executorService = new InstrumentedExecutorService(Executors.newWorkStealingPool(Runtime.getRuntime().availableProcessors()), metricRegistry);
    }

    @Override
    @Singleton
    @WorkStealer
    public ExecutorService provide() {
        return executorService;
    }

    @Override
    public void dispose(ExecutorService instance) {
        instance.shutdown();
        try {
            instance.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

}
