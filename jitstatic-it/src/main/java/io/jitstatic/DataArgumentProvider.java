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

import java.util.stream.Stream;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;

public class DataArgumentProvider implements ArgumentsProvider {
    static final String MASTER = "master";
    @Override
    public Stream<? extends Arguments> provideArguments(ExtensionContext context) throws Exception {
        return Stream.of(
                new TestData(new String[] { "a" }, new String[] { MASTER }, false, 10, 10),
                new TestData(new String[] { "a", "b", "c" }, new String[] { MASTER, "develop", "something" }, false, 10, 10),
                new TestData(new String[] { "a" }, new String[] { MASTER }, true, 10, 10),
                new TestData(new String[] { "a", "b", "c" }, new String[] { MASTER, "develop", "something" }, true, 10, 10),
                new TestData(new String[] { "a" }, new String[] { MASTER }, false, 50, 1),
                new TestData(new String[] { "a", "b", "c" }, new String[] { MASTER, "develop", "something" }, false, 50, 1),
                new TestData(new String[] { "a" }, new String[] { MASTER }, true, 50, 1),
                new TestData(new String[] { "a", "b", "c" }, new String[] { MASTER, "develop", "something" }, true, 50, 1),
                new TestData(new String[] { "a", "b", "c", "d", "e", "f", "g" },
                        new String[] { MASTER, "develop", "something", "a1", "a2", "a3", "a4", "a5" }, true, 50, 1),
                new TestData(new String[] { "a" }, new String[] { MASTER }, false, 0, 30),
                new TestData(new String[] { "a" }, new String[] { MASTER }, false, 2, 0),
                new FatTestData(new String[] { "a" }, new String[] { MASTER }, false, 10, 0),
                new FatTestData(new String[] { "a" }, new String[] { MASTER }, false, 50, 0),
                new FatTestData(new String[] { "a", "b", "c", "d", "e", "f", "g" },
                        new String[] { MASTER, "develop", "something", "a1", "a2", "a3", "a4", "a5" }, false, 50, 0))
                .map(Arguments::of);
    }

}
