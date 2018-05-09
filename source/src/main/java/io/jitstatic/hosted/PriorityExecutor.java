package io.jitstatic.hosted;

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

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class PriorityExecutor implements Executor {

    private final ReentrantReadWriteLock rrwl = new ReentrantReadWriteLock(true);

    public PriorityExecutor() {
    }

    @Override
    public void execute(final Runnable command) {
        Runnable wrapped = Objects.requireNonNull(command);
        if (command instanceof ReadOperation) {
            wrapped = () -> {
                rrwl.readLock().lock();
                try {
                    command.run();
                } finally {
                    rrwl.readLock().unlock();
                }
            };
        } else if (command instanceof WriteOperation) {
            wrapped = () -> {
                rrwl.writeLock().lock();
                try {
                    command.run();
                } finally {
                    rrwl.writeLock().unlock();
                }
            };
        } else {
            throw new IllegalArgumentException("Trying to execute an uknown command");
        }
        wrapped.run();
    }

    public void shutdown() {
    }

}
