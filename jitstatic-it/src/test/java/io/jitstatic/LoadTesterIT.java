package io.jitstatic;

import java.io.File;

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

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.apache.http.client.ClientProtocolException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.TransportException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import io.dropwizard.testing.ConfigOverride;
import io.dropwizard.testing.ResourceHelpers;
import io.dropwizard.testing.junit5.DropwizardAppExtension;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.jitstatic.client.APIException;
import io.jitstatic.test.BaseTest;
import io.jitstatic.test.TemporaryFolder;
import io.jitstatic.test.TemporaryFolderExtension;

@ExtendWith({ TemporaryFolderExtension.class, DropwizardExtensionsSupport.class })
@Tag("slow")
public class LoadTesterIT extends BaseTest {

    private TemporaryFolder tmpfolder;
    private DropwizardAppExtension<JitstaticConfiguration> DW = new DropwizardAppExtension<>(JitstaticApplication.class,
            ResourceHelpers.resourceFilePath("simpleserver_silent.yaml"), ConfigOverride.config("hosted.basePath", getFolder()));

    @ParameterizedTest
    @ArgumentsSource(WriteArgumentsProvider.class)
    public void testWrite(WriteData data) throws URISyntaxException, ClientProtocolException, APIException, IOException,
            InvalidRemoteException, TransportException, GitAPIException, InterruptedException, ExecutionException, TimeoutException {
        LoadWriterRunner runner = new LoadWriterRunner(new DropwizardTestProcess(DW, tmpfolder));
        runner.testWrite(data);
        runner.after();
    }

    @ParameterizedTest
    @ArgumentsSource(DataArgumentProvider.class)
    public void testLoad(TestData data) throws Exception {
        LoadTesterRunner runner = new LoadTesterRunner(new DropwizardTestProcess(DW, tmpfolder));
        runner.testLoad(data);
        runner.after();
    }

    @Override
    protected File getFolderFile() throws IOException { return tmpfolder.createTemporaryDirectory(); }
}
