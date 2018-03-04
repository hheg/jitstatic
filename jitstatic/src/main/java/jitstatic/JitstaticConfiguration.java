package jitstatic;

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

import javax.validation.Valid;
import javax.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.dropwizard.Configuration;
import io.dropwizard.setup.Environment;
import jitstatic.auth.AddKeyAuthenticator;
import jitstatic.auth.User;
import jitstatic.hosted.HostedFactory;
import jitstatic.reporting.ReportingFactory;
import jitstatic.source.Source;
import jitstatic.storage.StorageFactory;

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

    public void setHostedFactory(HostedFactory hosted) {
        this.hosted = hosted;
    }

    public Source build(final Environment env) throws CorruptedSourceException, IOException {
        Objects.requireNonNull(env);
        final HostedFactory hostedFactory = getHostedFactory();
        getReportingFactory().build(env);
        return hostedFactory.build(env);
    }

    public AddKeyAuthenticator getAddKeyAuthenticator() {
        HostedFactory hf = getHostedFactory();
        final User addUser = new User(hf.getUserName(), hf.getSecret());
        return (user) -> addUser.equals(user);
    }
}
