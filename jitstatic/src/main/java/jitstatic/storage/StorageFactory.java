package jitstatic.storage;

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




import java.nio.file.Path;
import java.nio.file.Paths;

import javax.validation.constraints.NotNull;

import org.glassfish.jersey.server.filter.RolesAllowedDynamicFeature;
import org.hibernate.validator.constraints.NotEmpty;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.dropwizard.auth.AuthDynamicFeature;
import io.dropwizard.auth.AuthValueFactoryProvider;
import io.dropwizard.auth.basic.BasicCredentialAuthFilter;
import io.dropwizard.setup.Environment;
import jitstatic.auth.ConfiguratedAuthenticator;
import jitstatic.auth.User;
import jitstatic.source.Source;

public class StorageFactory {

	@NotEmpty
	@NotNull
	@JsonProperty
	private String baseDirectory;

	@NotEmpty
	@NotNull
	@JsonProperty
	private String localFilePath;

	@NotEmpty
	@NotNull
	@JsonProperty
	@Deprecated
	private String user;

	@NotEmpty
	@NotNull
	@JsonProperty
	@Deprecated
	private String secret;

	public String getBaseDirectory() {
		return baseDirectory;
	}

	public void setBaseDirectory(String baseDirectory) {
		this.baseDirectory = baseDirectory;
	}

	public String getLocalFilePath() {
		return localFilePath;
	}

	public void setLocalFilePath(String localFilePath) {
		this.localFilePath = localFilePath;
	}

	public Storage build(final Source remote, Environment env) {
		final Path baseDirectoryPath = Paths.get(getBaseDirectory());
		env.jersey()
				.register(new AuthDynamicFeature(new BasicCredentialAuthFilter.Builder<User>()
						.setAuthenticator(new ConfiguratedAuthenticator()).setRealm("jitstatic")
						.buildAuthFilter()));
		env.jersey().register(RolesAllowedDynamicFeature.class);
		env.jersey().register(new AuthValueFactoryProvider.Binder<>(User.class));
		final GitWorkingRepositoryManager gwrm = new GitWorkingRepositoryManager(baseDirectoryPath, getLocalFilePath(),
				remote.getContact());
		try {			
			return new GitStorage(getLocalFilePath(), gwrm);
		} catch (IllegalArgumentException e) {
			StorageUtils.closeSilently(gwrm);
			throw e;
		}
	}
	@Deprecated
	public String getUser() {
		return user;
	}

	@Deprecated
	public void setUser(String user) {
		this.user = user;
	}
	@Deprecated
	public String getSecret() {
		return secret;
	}
	@Deprecated
	public void setSecret(String secret) {
		this.secret = secret;
	}
}
