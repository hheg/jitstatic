package io.jitstatic.hosted;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.NoSuchElementException;

import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectStream;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class InputStreamHolderTest {

    @Test
    public void testInputStreamHolder() throws IOException {
        ObjectLoader ol = Mockito.mock(ObjectLoader.class);
        Mockito.when(ol.openStream()).thenReturn(Mockito.mock(ObjectStream.class));
        Mockito.when(ol.getSize()).thenReturn(2L);
        InputStreamHolder ish = new InputStreamHolder(() -> ol);
        assertTrue(ish.isPresent());
        assertNotNull(ish.inputStream());
        assertNotNull(ish.getInputStreamProvider());
        assertEquals(2L,ish.getSize());
        assertThrows(NoSuchElementException.class, () -> ish.exception());
    }

    @Test
    public void testInputStreamHolderException() throws IOException {
        InputStreamHolder ish = new InputStreamHolder(new Exception());
        assertFalse(ish.isPresent());
        assertNotNull(ish.exception());
        assertThrows(NoSuchElementException.class, () -> ish.inputStream());
        assertThrows(NoSuchElementException.class, () -> ish.getSize());
    }
    
}
