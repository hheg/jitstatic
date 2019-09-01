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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.jitstatic.source.ObjectStreamProvider;

public class SearchResultTest {

    @Test
    public void testSearchResult() {
        byte[] content = new byte[] { 1 };
        ObjectStreamProvider ddd = ObjectStreamProvider.toProvider(content);
        SearchResult sr1 = new SearchResult("key", "tag", "type", "ref", ddd);
        SearchResult sr2 = new SearchResult("key", "tag", "type", "ref", ddd);
        SearchResult sr3 = new SearchResult("other", "tag", "type", "ref", ddd);
        SearchResult sr4 = new SearchResult("key", "other", "type", "ref", ddd);
        SearchResult sr5 = new SearchResult("key", "tag", "other", "ref", ddd);
        SearchResult sr6 = new SearchResult("key", "tag", "type", "other", ddd);

        assertTrue(sr1.equals(sr2));
        assertTrue(sr1.hashCode() == sr2.hashCode());
        assertFalse(sr1.equals(sr3));
        assertFalse(sr1.equals(sr4));
        assertTrue(sr1.equals(sr5));
        assertFalse(sr1.equals(sr6));
        assertFalse(sr1.equals(null));
        assertTrue(sr1.equals(sr1));
    }

}
