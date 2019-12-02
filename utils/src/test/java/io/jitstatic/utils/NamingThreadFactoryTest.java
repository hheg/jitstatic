package io.jitstatic.utils;

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

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class NamingThreadFactoryTest {

    @Test
    void testThrowException() throws InterruptedException {
        var rt = new RuntimeException("Test exception");
        NamingThreadFactory ntf = new NamingThreadFactory("name");
        Thread t = ntf.newThread(() -> {
            throw rt;
        });
        t.start();
        t.join();
        assertSame(rt, ErrorReporter.INSTANCE.getFault());
    }

}
