package jitstatic.remote;

/*-
 * #%L
 * jitstatic
 * %%
 * Copyright (C) 2017 HHegardt
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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import jitstatic.source.Source;
import jitstatic.source.SourceEventListener;

class RemoteRepostioryManager implements Source {

	private final URI remoteRepo;
	private final List<SourceEventListener> listeners;
	private final String userName;
	private final String password;
	private final RemoteContact remoteContact;

	public RemoteRepostioryManager(final URI remoteRepo, String userName, String password) {
		this.remoteRepo = Objects.requireNonNull(remoteRepo);
		this.listeners = new ArrayList<>();
		this.userName = userName;
		this.password = password;
		this.remoteContact = new RemoteContact();
	}

	@Override
	public void close() {
		// noop
	}

	@Override
	public void addListener(final SourceEventListener listener) {
		this.listeners.add(listener);		
	}

	@Override
	public Contact getContact() {
		return remoteContact;
	}
	
	private class RemoteContact implements Contact {

		@Override
		public URI repositoryURI() {
			return remoteRepo;
		}

		@Override
		public String getUserName() {
			return userName;
		}

		@Override
		public String getPassword() {
			return password;
		}
		
	}

}
