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

import java.util.concurrent.Callable;
import java.util.concurrent.Executor;

public class SubmittingExecutor {

    private final Executor executor;

    public SubmittingExecutor(final Executor executor) {
        this.executor = executor;
    }

    public <T> SubmittedSupplier<T> submit(final Callable<T> callable) {
        final SubmittedSupplier<T> supp = new SubmittedSupplier<>(callable);
        runCommand(callable, supp);
        return supp;
    }

    public SubmittedSupplier<Void> submit(final Runnable runnable) {
        final SubmittedSupplier<Void> supp = new SubmittedSupplier<>(runnable);
        runCommand(runnable, supp);
        return supp;
    }

    private <T> void runCommand(final Object command, final SubmittedSupplier<T> supp) {
        if (command instanceof ReadOperation) {
            executor.execute((Runnable & ReadOperation) () -> supp.run());
        } else if (command instanceof WriteOperation) {
            executor.execute((Runnable & WriteOperation) () -> supp.run());
        } else {
            throw new IllegalArgumentException("Trying to execute an uknown command");
        }
    }
}
