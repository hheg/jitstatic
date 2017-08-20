package jitstatic.remote;

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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LsRemoteCommand;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import jitstatic.source.Source.Contact;
import jitstatic.source.SourceEventListener;

class RemoteRepositoryManager implements Contact {

	private static final String REFS_HEADS_MASTER = "refs/heads/master";
	private final URI remoteRepo;
	private final List<SourceEventListener> listeners = new ArrayList<>();

	private final String userName;
	private final String password;

	volatile String latestSHA = null;
	volatile Exception fault = null;

	public RemoteRepositoryManager(final URI remoteRepo, String userName, String password) {
		this.remoteRepo = Objects.requireNonNull(remoteRepo, "Remote endpoint cannot be null");
		this.userName = userName;
		this.password = password == null ? "" : password;
	}

	public void addListeners(SourceEventListener listener) {
		this.listeners.add(listener);
	}

	public Runnable checkRemote() {
		return () -> {
			try {
				final LsRemoteCommand lsRemoteRepository = Git.lsRemoteRepository();
				if (userName != null) {
					lsRemoteRepository
							.setCredentialsProvider(new UsernamePasswordCredentialsProvider(userName, password));
				}
				Collection<Ref> refs = lsRemoteRepository.setHeads(true).setTags(true)
						.setRemote(this.remoteRepo.toString()).call();
				Iterator<Ref> iterator = refs.iterator();
				boolean triggered = false;
				while (iterator.hasNext()) {
					Ref next = iterator.next();
					if (REFS_HEADS_MASTER.equals(next.getName())) {
						latestSHA = next.getObjectId().getName();
						listeners.forEach(SourceEventListener::onEvent);
						triggered |= true;
					}
				}
				if (!triggered) {
					throw new RepositoryIsMissingIntendedBranch(
							"Repository doesn't have a " + REFS_HEADS_MASTER + " branch");
				}
			} catch (final Exception e) {
				fault = e;
			}
		};
	}

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

	public Contact getContact() {
		return this;
	}
}