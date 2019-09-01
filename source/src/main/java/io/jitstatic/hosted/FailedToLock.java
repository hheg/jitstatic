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

public class FailedToLock extends Exception {

    private static final long serialVersionUID = 2393164769037426630L;

    public FailedToLock(final String ref) {
        super(ref);
    }

    public FailedToLock(final String ref, final String key) {
        super(ref + ":" + key);
    }

    @Override
    public Throwable fillInStackTrace() {
        return this;
    }

    public static FailedToLock create(final Throwable t, final String ref) {
        FailedToLock ftl = new FailedToLock(ref);
        ftl.addSuppressed(t);
        return ftl;
    }
}
