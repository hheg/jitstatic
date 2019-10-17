package io.jitstatic.storage;

import java.util.Objects;
import java.util.concurrent.ExecutorService;

/*-
 * #%L
 * jitstatic
 * %%
 * Copyright (C) 2017 H.Hegardt
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

import org.glassfish.jersey.server.filter.RolesAllowedDynamicFeature;

import io.dropwizard.auth.AuthDynamicFeature;
import io.dropwizard.auth.AuthValueFactoryProvider;
import io.dropwizard.auth.basic.BasicCredentialAuthFilter;
import io.dropwizard.setup.Environment;
import io.jitstatic.auth.ConfiguratedAuthenticator;
import io.jitstatic.auth.User;
import io.jitstatic.hosted.events.AddRefEventListener;
import io.jitstatic.hosted.events.DeleteRefEventListener;
import io.jitstatic.hosted.events.ReloadRefEventListener;
import io.jitstatic.hosted.events.StorageAddRefEventListener;
import io.jitstatic.source.Source;

public class StorageFactory {

    public Storage build(final Source source, final Environment env, final String storageRealm, final HashService hashService, final String rootUser,
            final RefLockService clusterService, final ExecutorService executor, final ExecutorService workStealingExecutor) {
        Objects.requireNonNull(source, "Source cannot be null");
        Objects.requireNonNull(rootUser);
        env.jersey().register(new AuthDynamicFeature(new BasicCredentialAuthFilter.Builder<User>()
                .setAuthenticator(new ConfiguratedAuthenticator())
                .setAuthorizer((user, role) -> true)
                .setRealm(Objects.requireNonNull(storageRealm, "realm cannot be null"))
                .buildAuthFilter()));

        env.jersey().register(RolesAllowedDynamicFeature.class);
        env.jersey().register(new AuthValueFactoryProvider.Binder<>(User.class));

        final KeyStorage keyStorage = new KeyStorage(source, source.getDefaultRef(), hashService, clusterService, rootUser, executor, workStealingExecutor, env.metrics());
        source.addListener(new ReloadRefEventListener(keyStorage), ReloadRefEventListener.class);
        source.addListener(new DeleteRefEventListener(keyStorage), DeleteRefEventListener.class);
        source.addListener(new StorageAddRefEventListener(keyStorage), AddRefEventListener.class);
        source.addRefHolderFactory(keyStorage::getRefHolderLock);
        return keyStorage;
    }
}
