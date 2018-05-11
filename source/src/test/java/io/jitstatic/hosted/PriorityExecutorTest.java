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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

public class PriorityExecutorTest {
    
    @Test
    public void testPriorityExecutorTest() {
        PriorityExecutor pes = new PriorityExecutor();        
        AtomicBoolean b = new AtomicBoolean(false);
        Runnable r = (Runnable & ReadOperation)()-> {
            b.set(true);
        };
        pes.execute(r);
        assertTrue(b.get());
        b.set(false);
        Runnable w = (Runnable & WriteOperation)()-> {
            b.set(true);
        };        
        pes.execute(w);
        assertTrue(b.get());
        assertThrows(IllegalArgumentException.class, () -> pes.execute(() -> System.out.println("run")));
    }
}
