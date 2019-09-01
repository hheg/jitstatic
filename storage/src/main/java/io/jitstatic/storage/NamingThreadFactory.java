package io.jitstatic.storage;

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

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.jitstatic.hosted.ErrorReporter;

public class NamingThreadFactory implements ThreadFactory {

    private final String name;
    private final AtomicInteger counter = new AtomicInteger();
    private static final Logger LOG = LoggerFactory.getLogger(NamingThreadFactory.class);

    public NamingThreadFactory(final String name) {
        this.name = name;
    }

    @Override
    public Thread newThread(final Runnable r) {
        final Thread t = new Thread(r);
        t.setName(name + "-" + counter.incrementAndGet());
        t.setUncaughtExceptionHandler((thread, throwable) -> {
            LOG.error("Uncaught error in {}", thread, throwable);
            ErrorReporter.INSTANCE.setFault(throwable);
        });
        return t;
    }

}
