package io.jitstatic.api;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

public class KeyDataTest {

    @Test
    public void testKeyData() {
        String key = "key";
        String type = "type";
        String tag = "tag";
        byte[] d = new byte[] { 1 };
        KeyData data = new KeyData(key, type, tag, d);
        assertFalse(data.equals(null));
        assertFalse(data.equals(new Object()));
        assertEquals(new KeyData(key, type, tag, d), data);
        assertEquals(data, data);
        assertFalse(data.equals(new KeyData("key1", type, tag, d)));
        assertFalse(data.equals(new KeyData(key, "typee", tag, d)));
        assertFalse(data.equals(new KeyData(key, type, "1", d)));
        assertFalse(data.equals(new KeyData(key, type, tag, new byte[] { 2 })));
        assertEquals(data.hashCode(), data.hashCode());
        assertEquals(data.toString(), data.toString());
    }
}
