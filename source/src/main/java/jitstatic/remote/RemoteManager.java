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
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import jitstatic.source.Source;
import jitstatic.source.SourceEventListener;

class RemoteManager implements Source {

	private static final int _5 = 5;
	private final ScheduledThreadPoolExecutor poller;
	private final RemoteRepositoryManager remoteRepoManager;

	private volatile ScheduledFuture<?> job;

	public RemoteManager(final URI remoteRepoManager, final String userName, final String password) {
		this(new RemoteRepositoryManager(remoteRepoManager, userName, password), new ScheduledThreadPoolExecutor(1));
	}

	RemoteManager(final RemoteRepositoryManager remoteRepoManager, final ScheduledThreadPoolExecutor scheduler) {
		this.remoteRepoManager = Objects.requireNonNull(remoteRepoManager);
		this.poller = Objects.requireNonNull(scheduler);
	}

	@Override
	public void close() {
		if (job != null) {
			job.cancel(false);
		}
		this.poller.shutdown();
	}

	@Override
	public void addListener(final SourceEventListener listener) {
		this.remoteRepoManager.addListeners(listener);
	}

	@Override
	public Contact getContact() {
		return remoteRepoManager.getContact();
	}

	@Override
	public void start() {
		this.job = this.poller.scheduleWithFixedDelay(this.remoteRepoManager.checkRemote(), 0, _5, TimeUnit.SECONDS);
	}

	@Override
	public void checkHealth() {
		Exception fault = remoteRepoManager.getFault();
		if (fault != null) {
			remoteRepoManager.removeFault(fault);
			throw new RuntimeException(fault);
		}
	}

}
