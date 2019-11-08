package io.jitstatic.storage.ref;

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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import java.util.concurrent.ExecutorService;

import org.junit.jupiter.api.Test;

import io.jitstatic.CommitMetaData;
import io.jitstatic.MetaData;
import io.jitstatic.auth.UserData;
import io.jitstatic.source.ObjectStreamProvider;
import io.jitstatic.source.Source;
import io.jitstatic.storage.HashService;
import io.jitstatic.utils.WrappingAPIException;

class ReadOnlyRefHolderTest {

    @Test
    void testReadOnlyRefHolder() {
        ObjectStreamProvider osp = mock(ObjectStreamProvider.class);
        ExecutorService workStealer = mock(ExecutorService.class);
        CommitMetaData cmd = mock(CommitMetaData.class);
        MetaData metaData = mock(MetaData.class);
        UserData userData = mock(UserData.class);
        
        try (ReadOnlyRefHolder ref = new ReadOnlyRefHolder("ref", mock(Source.class), mock(HashService.class),
                mock(LocalRefLockService.class), workStealer);) {
            assertThrows(WrappingAPIException.class, () -> ref.addKey("key", osp, metaData, cmd));
            assertThrows(WrappingAPIException.class, () -> ref.addUser("user", "user", userData));
            assertThrows(WrappingAPIException.class, () -> ref.modifyKey("key", osp, "ref", cmd));
            assertThrows(WrappingAPIException.class, () -> ref.modifyMetadata("key", metaData, "ref", cmd));
            assertThrows(WrappingAPIException.class, () -> ref.deleteKey("key",cmd));
            assertThrows(WrappingAPIException.class, () -> ref.deleteUser("user", "user"));
            assertThrows(WrappingAPIException.class, () -> ref.modifyUser("user", "ref", userData, "version"));
        }
    }

}
