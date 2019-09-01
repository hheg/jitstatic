package io.jitstatic.storage;

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

import java.util.Optional;
import java.util.concurrent.ExecutorService;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.jitstatic.CommitMetaData;
import io.jitstatic.MetaData;
import io.jitstatic.auth.UserData;
import io.jitstatic.source.ObjectStreamProvider;
import io.jitstatic.source.Source;
import io.jitstatic.utils.WrappingAPIException;

class ReadOnlyRefHolderTest {

    @Test
    void testReadOnlyRefHolder() {
        ObjectStreamProvider osp = Mockito.mock(ObjectStreamProvider.class);
        CommitMetaData cmd = Mockito.mock(CommitMetaData.class);
        MetaData metaData = Mockito.mock(MetaData.class);
        UserData userData = Mockito.mock(UserData.class);
        
        try (ReadOnlyRefHolder ref = new ReadOnlyRefHolder("ref", Mockito.mock(Source.class), Mockito.mock(HashService.class),
                Mockito.mock(ExecutorService.class),
                Mockito.mock(RefLockService.class));) {
            assertThrows(WrappingAPIException.class, () -> ref.addKey("key", osp, metaData, cmd));
            assertThrows(WrappingAPIException.class, () -> ref.addUser("user", "user", userData));
            assertThrows(WrappingAPIException.class, () -> ref.modifyKey("key", osp, "ref", cmd));
            assertThrows(WrappingAPIException.class, () -> ref.modifyMetadata("key", metaData, "ref", cmd));
            assertThrows(WrappingAPIException.class, () -> ref.deleteKey("key",cmd));
            assertThrows(WrappingAPIException.class, () -> ref.deleteUser("user", "user"));
            assertThrows(WrappingAPIException.class, () -> ref.putKey("key", Optional.empty()));
            assertThrows(WrappingAPIException.class, () -> ref.modifyUser("user", "ref", userData, "version"));
        }
    }

}
