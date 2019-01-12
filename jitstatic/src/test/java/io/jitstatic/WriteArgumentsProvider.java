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

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;

public class WriteArgumentsProvider implements ArgumentsProvider {

    @Override
    public Stream<? extends Arguments> provideArguments(ExtensionContext arg0) throws Exception {
        return Stream.of(
                new WriteData(List.of("master"), List.of("a")), new WriteData(List.of("master"), List.of("a", "b", "c")),
                new WriteData(List.of("master", "develop", "other"), List.of("a")),
                new WriteData(List.of("master", "develop", "other"), List.of("a", "b", "c")),
                new WriteData(List.of("master", "develop", "other", "next", "more", "evenmore"), List.of("a")),
                new WriteData(List.of("master", "develop", "other", "next", "more", "evenmore"), List.of("a", "b", "c", "d", "e", "f")),
                new WriteData(List.of("master", "develop", "other", "next", "more", "evenmore"), List.of("a")),
                new WriteData(List.of("master", "develop", "other", "next", "more", "evenmore"),
                        List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m", "n", "o", "p", "q", "r", "s", "t", "u", "v", "x", "y")),
                new WriteData(List.of("master", "develop", "other", "next", "more", "evenmore", "a1", "a2", "a3", "a4", "a5", "a6", "a7", "a8", "a9", "a10"),
                        List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m", "n", "o", "p", "q", "r", "s", "t", "u", "v", "x", "y")),
                new FatWriteData(List.of("master"), List.of("a")), 
                new FatWriteData(List.of("master"), List.of("a", "b", "c")),
                new FatWriteData(List.of("master", "develop", "other"), List.of("a")),
                new FatWriteData(List.of("master", "develop", "other"), List.of("a", "b", "c")),                
                new FatWriteData(List.of("master", "develop", "other", "next", "more", "evenmore"),
                        List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m", "n", "o", "p", "q", "r", "s", "t", "u", "v", "x", "y"))
                )
                .map(Arguments::of);
    }

}
