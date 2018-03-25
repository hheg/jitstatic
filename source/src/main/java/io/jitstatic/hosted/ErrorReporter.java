package io.jitstatic.hosted;

/*-
 * #%L
 * jitstatic
 * %%
 * Copyright (C) 2017 - 2018 H.Hegardt
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
