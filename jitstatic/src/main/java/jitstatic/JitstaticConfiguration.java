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



import javax.validation.Valid;
import javax.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.dropwizard.Configuration;
import jitstatic.hosted.HostedFactory;
import jitstatic.remote.RemoteFactory;
import jitstatic.storage.StorageFactory;

public class JitstaticConfiguration extends Configuration {

	@NotNull
	@Valid
	private StorageFactory storage;

	@Valid
	private RemoteFactory remote;

	@Valid
	private HostedFactory hosted;

	@JsonProperty("storage")
	public StorageFactory getStorageFactory() {
		return storage;
	}

	@JsonProperty("storage")
	public void setStorageFactory(final StorageFactory storage) {
		this.storage = storage;
	}

	@JsonProperty("remote")
	public RemoteFactory getRemoteFactory() {
		return remote;
	}

	@JsonProperty("remote")
	public void setRemoteFactory(RemoteFactory remote) {
		this.remote = remote;
	}

	@JsonProperty("hosted")
	public HostedFactory getHostedFactory() {
		return hosted;
	}

	@JsonProperty("hosted")
	public void setHostedFactory(HostedFactory hosted) {
		this.hosted = hosted;
	}

}
