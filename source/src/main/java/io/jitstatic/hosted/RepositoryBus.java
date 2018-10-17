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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import io.jitstatic.source.SourceEventListener;

public class RepositoryBus {

    private final List<SourceEventListener> sourceEventListeners = Collections.synchronizedList(new ArrayList<>());
    private volatile Function<String, RefHolderLock> refSource;
    private final ErrorReporter reporter;

    public RepositoryBus(final ErrorReporter reporter) {
        this.reporter = reporter;
    }

    public void setRefHolderFactory(final Function<String, RefHolderLock> factory) {
        refSource = factory;
    }

    public RefHolderLock getRefHolder(final String ref) {
        return refSource.apply(ref);
    }

    public void process(final List<String> refsToUpdate) {
        this.sourceEventListeners.forEach(sourceEventListener -> {
            try {
                sourceEventListener.onEvent(refsToUpdate);
            } catch (final Exception ex) {
                reporter.setFault(ex);
            }
        });
    }

    void addListener(final SourceEventListener listener) {
        this.sourceEventListeners.add(listener);
    }

}
