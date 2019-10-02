package io.jitstatic;

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

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.dropwizard.Configuration;
import io.dropwizard.setup.Environment;
import io.jitstatic.auth.KeyAdminAuthenticator;
import io.jitstatic.auth.KeyAdminAuthenticatorImpl;
import io.jitstatic.auth.User;
import io.jitstatic.check.CorruptedSourceException;
import io.jitstatic.hosted.HostedFactory;
import io.jitstatic.reporting.ReportingFactory;
import io.jitstatic.source.Source;
import io.jitstatic.storage.HashService;
import io.jitstatic.storage.Storage;
import io.jitstatic.storage.StorageFactory;

public class JitstaticConfiguration extends Configuration {

    private StorageFactory storage = new StorageFactory();

    @Valid
    @NotNull
    @JsonProperty
    private HostedFactory hosted;

    @Valid
    @JsonProperty
    private ReportingFactory reporting = new ReportingFactory();

    public ReportingFactory getReportingFactory() {
        return reporting;
    }

    public void setReportingFactory(final ReportingFactory reporting) {
        this.reporting = reporting;
    }

    public StorageFactory getStorageFactory() {
        return storage;
    }

    public void setStorageFactory(final StorageFactory storage) {
        this.storage = storage;
    }

    public HostedFactory getHostedFactory() {
        return hosted;
    }

    public void setHostedFactory(final HostedFactory hosted) {
        this.hosted = hosted;
    }

    public Source build(final Environment env, final String gitRealm, ExecutorService repoWriter) throws CorruptedSourceException, IOException {
        Objects.requireNonNull(repoWriter);
        final HostedFactory hostedFactory = getHostedFactory();
        getReportingFactory().build(Objects.requireNonNull(env));
        return hostedFactory.build(env, Objects.requireNonNull(gitRealm), repoWriter);
    }

    public KeyAdminAuthenticator getKeyAdminAuthenticator(final Storage storage, HashService hashService) {
        final HostedFactory hf = getHostedFactory();
        final User addUser = new User(hf.getUserName(), hf.getSecret());
        return new KeyAdminAuthenticatorImpl(storage, (user, ref) -> addUser.equals(user), hf.getBranch(), hashService);
    }
}
