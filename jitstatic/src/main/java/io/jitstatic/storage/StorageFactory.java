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

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.function.BiPredicate;

import io.dropwizard.auth.AuthDynamicFeature;
import io.dropwizard.auth.AuthValueFactoryProvider;
import io.dropwizard.setup.Environment;
import io.jitstatic.auth.UrlAwareBasicCredentialAuthFilter;
import io.jitstatic.auth.User;
import io.jitstatic.hosted.events.AddRefEventListener;
import io.jitstatic.hosted.events.DeleteRefEventListener;
import io.jitstatic.hosted.events.ReloadRefEventListener;
import io.jitstatic.hosted.events.StorageAddRefEventListener;
import io.jitstatic.source.Source;
import io.jitstatic.storage.ref.RefLockService;

public class StorageFactory {

    public Storage build(final Source source, final Environment env, final String defaultBranch, final HashService hashService, final String rootUser,
            final RefLockService clusterService, final ExecutorService executor, final ExecutorService workStealingExecutor, BiPredicate<String,String> rootAuthenticator) {
        Objects.requireNonNull(source, "Source cannot be null");
        Objects.requireNonNull(rootUser);

        final KeyStorage keyStorage = new KeyStorage(source, defaultBranch, hashService, clusterService, rootUser, executor, workStealingExecutor, env
                .metrics());
        source.addListener(new ReloadRefEventListener(keyStorage), ReloadRefEventListener.class);
        source.addListener(new DeleteRefEventListener(keyStorage), DeleteRefEventListener.class);
        source.addListener(new StorageAddRefEventListener(keyStorage), AddRefEventListener.class);
        source.addRefHolderFactory(keyStorage::getRefHolderLock);

        env.jersey().register(new AuthDynamicFeature(new UrlAwareBasicCredentialAuthFilter(keyStorage, hashService, rootAuthenticator)));
        env.jersey().register(new AuthValueFactoryProvider.Binder<>(User.class));

        return keyStorage;
    }
}
