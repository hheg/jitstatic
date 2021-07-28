package io.jitstatic.injection.configuration;

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

import java.util.function.BiPredicate;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.dropwizard.Configuration;
import io.jitstatic.injection.configuration.hosted.HostedFactory;
import io.jitstatic.injection.configuration.reporting.ReportingFactory;

public class JitstaticConfiguration extends Configuration {

    @Valid
    @NotNull
    @JsonProperty
    private HostedFactory hosted;

    @Valid
    @JsonProperty
    private ReportingFactory reporting = new ReportingFactory();

    public ReportingFactory getReportingFactory() { return reporting; }

    public void setReportingFactory(final ReportingFactory reporting) { this.reporting = reporting; }

    public HostedFactory getHostedFactory() { return hosted; }

    public void setHostedFactory(final HostedFactory hosted) { this.hosted = hosted; }

    public BiPredicate<String, String> getRootAuthenticator() {
        final HostedFactory hf = getHostedFactory();
        final String userName = hf.getUserName();
        final String password = hf.getSecret();
        return (u, p) -> u.equals(userName) && p.equals(password);
    }
}
