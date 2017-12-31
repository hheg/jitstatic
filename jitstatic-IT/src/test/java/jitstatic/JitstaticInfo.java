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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.IOException;
import java.util.function.Supplier;

import javax.ws.rs.client.Client;

import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.TemporaryFolder;

import io.dropwizard.client.HttpClientBuilder;
import io.dropwizard.client.HttpClientConfiguration;
import io.dropwizard.client.JerseyClientBuilder;
import io.dropwizard.setup.Environment;
import io.dropwizard.testing.ConfigOverride;
import io.dropwizard.testing.ResourceHelpers;
import io.dropwizard.testing.junit.DropwizardAppRule;

public class JitstaticInfo {

	private static final TemporaryFolder TMP_FOLDER = new TemporaryFolder();
	private static final HttpClientConfiguration HCC = new HttpClientConfiguration();

	private static final DropwizardAppRule<JitstaticConfiguration> DW;

	@ClassRule
	public static final RuleChain chain = RuleChain.outerRule(TMP_FOLDER)
			.around((DW = new DropwizardAppRule<>(JitstaticApplication.class,
					ResourceHelpers.resourceFilePath("simpleserver.yaml"),
					ConfigOverride.config("hosted.basePath", getFolder()))));

	private static String adress;

	@BeforeClass
	public static void setupClass() {
		adress = String.format("http://localhost:%d/application/info", DW.getLocalPort());
	}

	@Test
	public void testGetCommitId() {
		Client client = buildClient("test client");
		try {
			String response = client.target(String.format("%s/commitid", adress)).request().get(String.class);
			assertNotNull(response);
		} finally {
			client.close();
		}
	}

	@Test
	public void testGetVersion() {
		Client client = buildClient("test client2");
		try {
			String response = client.target(String.format("%s/version", adress)).request().get(String.class);
			assertNotNull(response);
		} finally {
			client.close();
		}
	}
	
	private Client buildClient(final String name) {
		Environment env = DW.getEnvironment();
		JerseyClientBuilder jerseyClientBuilder = new JerseyClientBuilder(env);
		jerseyClientBuilder.setApacheHttpClientBuilder(new HttpClientBuilder(env).using(HCC));
		return jerseyClientBuilder.build(name);
	}

	private static Supplier<String> getFolder() {
		return () -> {
			try {
				return TMP_FOLDER.newFolder().toString();
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		};
	}

}
