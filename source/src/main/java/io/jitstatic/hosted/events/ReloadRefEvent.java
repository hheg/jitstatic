package io.jitstatic.hosted.events;

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

import java.util.Objects;

import org.eclipse.jgit.events.RepositoryEvent;
import org.slf4j.LoggerFactory;

public class ReloadRefEvent extends RepositoryEvent<ReloadRefEventListener> {

    private final String ref;

    public ReloadRefEvent(final String ref) {
        this.ref = Objects.requireNonNull(ref);
    }

    @Override
    public Class<ReloadRefEventListener> getListenerType() {
        return ReloadRefEventListener.class;
    }

    @Override
    public void dispatch(final ReloadRefEventListener listener) {
        try {
            listener.onReload(ref);
        } catch (Exception e) {
            LoggerFactory.getLogger(getClass()).error("Error while loading storage", e);
        }
    }

}
