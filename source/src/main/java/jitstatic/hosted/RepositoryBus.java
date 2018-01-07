package jitstatic.hosted;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import jitstatic.source.SourceEventListener;

public class RepositoryBus {

	private final List<SourceEventListener> sourceEventListeners = Collections.synchronizedList(new ArrayList<>());
	private final ErrorReporter reporter;

	public RepositoryBus(final ErrorReporter reporter) {
		this.reporter = reporter;
	}

	public void process(final List<String> refsToUpdate) {
		this.sourceEventListeners.forEach(sourceEventListener -> {
			try {
				sourceEventListener.onEvent(refsToUpdate);
			} catch (final Exception ex) {
				reporter.setFault(ex);
			}
		});
	}

	void addListener(final SourceEventListener listener) {
		this.sourceEventListeners.add(listener);
	}

}
