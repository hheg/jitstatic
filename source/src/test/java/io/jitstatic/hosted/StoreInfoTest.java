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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.jitstatic.MetaData;
import io.jitstatic.auth.User;

public class StoreInfoTest {
    
    @Test
    public void testStoreInfo() {
        MetaData sd = new MetaData(Set.of(new User("u", "p")), "t", false, false, List.of());
        StoreInfo s1 = new StoreInfo(new byte[] {0}, sd, "1", "1");
        StoreInfo s2 = new StoreInfo(new byte[] {0}, sd, "1", "1");
        StoreInfo s3 = new StoreInfo(new byte[] {0}, sd, "2", "1");
        assertEquals(s1,s1);
        assertEquals(s1,s2);
        assertNotEquals(s1,s3);
        assertNotEquals(s1,null);
        assertNotEquals(s1,new Object());
        s1.hashCode();
    }

}
