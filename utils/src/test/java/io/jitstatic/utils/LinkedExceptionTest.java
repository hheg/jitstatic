package io.jitstatic.utils;

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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

public class LinkedExceptionTest {

    @Test
    public void testLinkedException() {
        LinkedException le = new LinkedException(Arrays.asList(new RuntimeException("re"), new Exception("e1")));
        le.add(new Exception("e2"));
        le.addAll(Arrays.asList(new Exception("e3")));        
        assertFalse(le.isEmpty());
        assertTrue(le.getMessage().startsWith("java.lang.RuntimeException"));
    }

    @Test
    public void testNotAddNullExceptions() {
        LinkedException le = new LinkedException();
        le.add(null);
        assertTrue(le.isEmpty());
    }
}
