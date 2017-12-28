package jitstatic.hosted;

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
			final List<String> collected = commands.stream().map(r -> r.getRefName()).collect(Collectors.toList());
			this.sourceEventListeners.forEach(s -> {
				try {
					s.onEvent(collected);
				} catch (final Exception e) {
					final Exception unregistered = fault.getAndSet(e);
					if (unregistered != null) {
						log.error("Unregistered error ", e);
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
