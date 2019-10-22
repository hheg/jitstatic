package io.jitstatic.hosted;

import static io.jitstatic.source.ObjectStreamProvider.toProvider;

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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.jitstatic.MetaData;
import io.jitstatic.auth.User;
import io.jitstatic.source.ObjectStreamProvider;

public class StoreInfoTest {

    @Test
    public void testStoreInfo() throws IOException {
        MetaData sd = new MetaData("t", false, false, List.of(), Set.of(),Set.of());
        StoreInfo s1 = new StoreInfo(toProvider(new byte[] { 0 }), sd, "1", "1");
        StoreInfo s2 = new StoreInfo(toProvider(new byte[] { 0 }), sd, "1", "1");
        StoreInfo s3 = new StoreInfo(toProvider(new byte[] { 0 }), sd, "2", "1");
        assertEquals(s1, s1);
        assertEquals(s1, s2);
        assertNotEquals(s1, s3);
        assertNotEquals(null, s1);
        assertNotEquals(s1, new Object());
        assertEquals(s1.hashCode(), s2.hashCode());
        assertEquals("1", s1.getVersion());
        assertArrayEquals(new byte[] { 0 }, ObjectStreamProvider.toByte(s1.getStreamProvider()));
        assertTrue(s1.isNormalKey());
        assertFalse(s1.isMasterMetaData());
    }

    @Test
    public void testStoreInfoMasterMetaData() {
        MetaData sd = new MetaData("t", false, false, List.of(), Set.of(), Set.of());
        StoreInfo s1 = new StoreInfo(sd, "1");
        StoreInfo s2 = new StoreInfo(sd, "1");

        assertTrue(s1.isMasterMetaData());
        assertFalse(s1.isNormalKey());
        assertThrows(IllegalStateException.class, () -> s1.getStreamProvider());
        assertThrows(IllegalStateException.class, () -> s1.getVersion());
        assertEquals("1", s1.getMetaDataVersion());
        assertEquals(s1, s2);
    }

}
