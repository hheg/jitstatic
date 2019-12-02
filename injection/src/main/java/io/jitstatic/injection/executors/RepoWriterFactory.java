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

import io.jitstatic.utils.NamingThreadFactory;

public class RepoWriterFactory implements Factory<ExecutorService> {

    private ExecutorService executor;

    @Inject
    public RepoWriterFactory(MetricRegistry metricRegistry) {
        this.executor = new InstrumentedExecutorService(Executors.newSingleThreadExecutor(new NamingThreadFactory("RepoWriter")), metricRegistry);
    }
    
    @Override
    @Singleton
    @RepoWriter
    public ExecutorService provide() {
        return executor;
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
