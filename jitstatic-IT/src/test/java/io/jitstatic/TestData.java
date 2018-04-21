package io.jitstatic;

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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TestData {
    final String[] names;
    final String[] branches;
    final boolean cache;
    final Map<String, Map<String, String>> versions;

    public TestData(String[] names, String[] branches, boolean cache) {
        this.cache = cache;
        this.names = names;
        this.branches = branches;
        this.versions = new ConcurrentHashMap<>();
        for (String branch : branches) {
            for (String name : names) {
                Map<String, String> m = new ConcurrentHashMap<>();
                m.put(name, "nothing");
                versions.put(branch, m);
            }
        }
    }
}
