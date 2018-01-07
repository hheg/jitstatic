package jitstatic.hosted;

import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ErrorReporter {
	private static final Logger LOG = LoggerFactory.getLogger(ErrorReporter.class);
	private final AtomicReference<Exception> fault = new AtomicReference<Exception>();

	public Exception getFault() {
		return fault.getAndSet(null);
	}

	public void setFault(final Exception e) {
		final Exception unregistered = fault.getAndSet(e);
		if (unregistered != null) {
			LOG.error("Unregistered error ", unregistered);
		}
	}
}
