package jitstatic.hosted;

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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.eclipse.jgit.transport.PostReceiveHook;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceivePack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jitstatic.source.SourceEventListener;

class JitStaticPostReceiveHook implements PostReceiveHook {

	private static final Logger log = LoggerFactory.getLogger(JitStaticPostReceiveHook.class);

	private List<SourceEventListener> sourceEventListeners = Collections.synchronizedList(new ArrayList<>());
	private AtomicReference<Exception> fault = new AtomicReference<Exception>();

	public JitStaticPostReceiveHook() {
		this.sourceEventListeners = new ArrayList<>();
	}

	@Override
	public void onPostReceive(final ReceivePack rp, final Collection<ReceiveCommand> commands) {
		final List<ReceiveCommand> allCommands = rp.getAllCommands();
		if (allCommands.size() == commands.size()) {
			final List<String> collected = commands.stream().map(receiveCommand -> receiveCommand.getRefName()).collect(Collectors.toList());
			this.sourceEventListeners.forEach(sourceEventListener -> {
				try {
					sourceEventListener.onEvent(collected);
				} catch (final Exception ex) {
					final Exception unregistered = fault.getAndSet(ex);
					if (unregistered != null) {
						log.error("Unregistered error ", ex);
					}
				}
			});
		}
	}

	void addListener(final SourceEventListener listener) {
		this.sourceEventListeners.add(listener);
	}

	public Exception getFault() {
		return fault.getAndSet(null);
	}

}
