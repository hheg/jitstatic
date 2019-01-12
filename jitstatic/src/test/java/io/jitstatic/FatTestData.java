package io.jitstatic;

import java.util.Arrays;

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

import org.apache.commons.lang3.RandomStringUtils;

public class FatTestData extends TestData {

    private final String data;

    public FatTestData(String[] names, String[] branches, boolean cache, int clients, int updaters) {
        super(names, branches, cache, clients, updaters);
        data = RandomStringUtils.random(1_000_000, true, true);
    }

    public String fill() {
        return data + super.fill();
    }
    @Override
    public String toString() {
        return "FatTestData [names=" + Arrays.toString(names) + ", branches=" + Arrays.toString(branches) + ", cache=" + cache + ", clients=" + clients + ", updaters=" + updaters + "]";
    }

}
