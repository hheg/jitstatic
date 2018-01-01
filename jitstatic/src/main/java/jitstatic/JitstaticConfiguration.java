package jitstatic;

import java.io.IOException;
import java.util.Objects;

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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.dropwizard.Configuration;
import io.dropwizard.setup.Environment;
import jitstatic.hosted.HostedFactory;
import jitstatic.remote.RemoteFactory;
import jitstatic.reporting.ReportingFactory;
import jitstatic.source.Source;
import jitstatic.storage.StorageFactory;

public class JitstaticConfiguration extends Configuration {

	private static final Logger LOG = LoggerFactory.getLogger(JitstaticConfiguration.class);

	private StorageFactory storage = new StorageFactory();

	@Valid
	@JsonProperty
	private RemoteFactory remote;

	@Valid
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

	public RemoteFactory getRemoteFactory() {
		return remote;
	}

	public void setRemoteFactory(RemoteFactory remote) {
		this.remote = remote;
	}
	
	public HostedFactory getHostedFactory() {
		return hosted;
	}

	public void setHostedFactory(HostedFactory hosted) {
		this.hosted = hosted;
	}

	public Source build(final Environment env) throws CorruptedSourceException, IOException {
		Objects.requireNonNull(env);
		Source source;
		final HostedFactory hostedFactory = getHostedFactory();
		final RemoteFactory remoteFactory = getRemoteFactory();
		if (hostedFactory != null) {
			source = hostedFactory.build(env);
			if (remoteFactory != null) {
				LOG.warn("When in a hosted configuration, any settings for a remote configuration is ignored");
			}
		} else {			
			if (remoteFactory == null) {
				throw new IllegalStateException("Either hosted or remote must be chosen");
			}
			source = remoteFactory.build(env);
		}
		getReportingFactory().build(env);		
		return source;
	}
}
