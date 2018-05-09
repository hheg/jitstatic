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
import java.util.function.Supplier;

public class SubmittedSupplier<T> implements Supplier<T> {

    private final Callable<T> callable;
    private T result;
    private Exception e;

    public SubmittedSupplier(final Callable<T> callable) {
        this.callable = callable;
    }

    public SubmittedSupplier(final Runnable runnable) {
        this.callable = () -> {
            runnable.run();
            return null;
        };
    }

    void run() {
        try {
            result = callable.call();
        } catch (Exception e) {
            this.e = e;
        }
    }

    public T get() {
        if (e != null) {
            throw new RuntimeException(e);
        }
        return result;
    }
}
