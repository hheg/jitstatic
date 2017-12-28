package jitstatic.storage;

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

import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

class StorageThreadFactory implements ThreadFactory {

	private static final Logger LOG = LogManager.getLogger(StorageThreadFactory.class);
	private final java.util.concurrent.ThreadFactory defaultThreadFactory;
	private final UncaughtExceptionHandler uncaughtExceptionHandler;
	private final String name;
	private final Consumer<Exception> errorConsumer;

	public StorageThreadFactory(final String name, final Consumer<Exception> errorConsumer) {
		defaultThreadFactory = Executors.defaultThreadFactory();
		uncaughtExceptionHandler = new UncaughtExceptionHandler();
		this.name = name;
		this.errorConsumer = errorConsumer;
	}

	@Override
	public Thread newThread(final Runnable r) {
		final Thread newThread = defaultThreadFactory.newThread(r);
		newThread.setName(name + "-" + newThread.getName());
		newThread.setUncaughtExceptionHandler(uncaughtExceptionHandler);
		return newThread;
	}

	private class UncaughtExceptionHandler implements Thread.UncaughtExceptionHandler {

		@Override
		public void uncaughtException(final Thread t, final Throwable e) {
			if (e instanceof Exception) {
				errorConsumer.accept((Exception) e);
				return;
			}
			LOG.error("Caught unconsumable error ", e);
		}
	}
}
