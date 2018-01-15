package jitstatic.remote;

import java.io.IOException;

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

import java.net.URI;
import java.nio.file.Path;

import javax.validation.constraints.NotNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.dropwizard.setup.Environment;
import io.dropwizard.util.Duration;
import jitstatic.CorruptedSourceException;
import jitstatic.remote.RemoteManager;
import jitstatic.source.Source;
import jitstatic.storage.StorageInfo;

public class RemoteFactory extends StorageInfo {
	private static final Logger LOG = LoggerFactory.getLogger(RemoteFactory.class);
	@NotNull
	@JsonProperty
	private URI remoteRepo;

	@JsonProperty
	private String userName;

	@JsonProperty
	private String remotePassword = "";

	@JsonProperty
	private Duration pollingPeriod = Duration.seconds(5);

	@JsonProperty
	private Path basePath;

	public URI getRemoteRepo() {
		return remoteRepo;
	}

	public void setRemoteRepo(URI remoteRepo) {
		this.remoteRepo = remoteRepo;
	}

	public String getUserName() {
		return userName;
	}

	public void setUserName(String userName) {
		this.userName = userName;
	}

	public String getRemotePassword() {
		return remotePassword;
	}

	public void setRemotePassword(String remotePassword) {
		this.remotePassword = remotePassword;
	}

	public Source build(final Environment env) throws CorruptedSourceException, IOException {
		if (!getRemoteRepo().isAbsolute())
			throw new IllegalArgumentException(String.format("parameter remoteRepo, %s, must be absolute", getRemoteRepo()));
		LOG.info("Loading a remote repo at " + getRemoteRepo());
		return new RemoteManager(getRemoteRepo(), getUserName(), getRemotePassword(), getPollingPeriod().getQuantity(),
				getPollingPeriod().getUnit(), getBasePath(), getBranch());
	}

	public Duration getPollingPeriod() {
		return pollingPeriod;
	}

	public void setPollingPeriod(Duration pollingPeriod) {
		this.pollingPeriod = pollingPeriod;
	}

	public Path getBasePath() {
		return basePath;
	}

	public void setBasePath(Path basePath) {
		this.basePath = basePath;
	}

}
