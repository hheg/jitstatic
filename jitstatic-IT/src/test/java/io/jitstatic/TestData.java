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

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TestData {
    final String[] names;
    final String[] branches;
    final boolean cache;
    final Map<String, Map<String, String>> versions;
    final int clients;
    final int updaters;

    public TestData(String[] names, String[] branches, boolean cache, int clients, int updaters) {
        this.cache = cache;
        this.names = Arrays.copyOf(names, names.length);
        this.branches = Arrays.copyOf(branches, branches.length);
        this.clients = clients;
        this.updaters = updaters;
        this.versions = new ConcurrentHashMap<>();
        for (String branch : branches) {
            Map<String, String> m = new ConcurrentHashMap<>();
            for (String name : names) {                
                m.put(name, "nothing");                
            }
            versions.put(branch, m);
        }
    }

    @Override
    public String toString() {
        return "TestData [names=" + Arrays.toString(names) + ", branches=" + Arrays.toString(branches) + ", cache=" + cache + ", versions="
                + versions + ", clients=" + clients + ", updaters=" + updaters + "]";
    }
}
