package io.jitstatic;

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

import java.io.File;
import java.io.IOException;

import io.dropwizard.testing.junit5.DropwizardAppExtension;
import io.jitstatic.hosted.HostedFactory;
import io.jitstatic.test.TemporaryFolder;
import io.jitstatic.tools.ContainerUtils;

class DropwizardTestProcess implements DropwizardProcess {
    
    private final DropwizardAppExtension<JitstaticConfiguration> DW;
    private final TemporaryFolder tmpFolder;

    public DropwizardTestProcess(DropwizardAppExtension<JitstaticConfiguration> DW, TemporaryFolder tmpFolder) {
        this.DW = DW;
        this.tmpFolder = tmpFolder;
    }
    @Override
    public String getUser() {
        return DW.getConfiguration().getHostedFactory().getUserName();
    }

    @Override
    public String getPassword() {
        return DW.getConfiguration().getHostedFactory().getSecret();
    }

    @Override
    public String getMetrics() {
        return getAdminAddress() + "/metrics";
    }

    @Override
    public int getLocalPort() {
        return DW.getLocalPort();
    }

    @Override
    public String getGitAddress() {
        HostedFactory hf = DW.getConfiguration().getHostedFactory();
        return String.format("http://localhost:%d/application/%s/%s", getLocalPort(), hf.getServletName(), hf.getHostedEndpoint());
    }

    @Override
    public File getFolderFile() throws IOException {
        return tmpFolder.createTemporaryDirectory();
    }

    @Override
    public String getAdminAddress() {
        return String.format("http://localhost:%d/admin", DW.getAdminPort());
    }

    @Override
    public void checkContainerForErrors() {
        ContainerUtils.checkContainerForErrors(DW);
    }
    @Override
    public void close() throws Exception {
        // NOOP
    }
}
