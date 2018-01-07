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

import static org.junit.Assert.assertEquals;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.Test;

import jitstatic.util.ErrorConsumingThreadFactory;

public class StorageThreadFactoryTest {

	@Test
	public void testCatchingException() throws InterruptedException {
		RuntimeException runtimeException = new RuntimeException();
		AtomicReference<Exception> ar = new AtomicReference<>();
		Consumer<Exception> test = t -> {
			ar.set(t);
		};
		ErrorConsumingThreadFactory storageThreadFactory = new ErrorConsumingThreadFactory("test", test);
		Thread newThread = storageThreadFactory.newThread(() -> {
			throw runtimeException;
		});
		newThread.start();
		newThread.join();
		assertEquals(runtimeException, ar.get());
	}
	
	@Test
	public void testCatchingError() throws InterruptedException {
		Error error = new Error("Ok test error");
		AtomicReference<Exception> ar = new AtomicReference<>();
		Consumer<Exception> test = t -> {
			ar.set(t);
		};
		ErrorConsumingThreadFactory storageThreadFactory = new ErrorConsumingThreadFactory("test", test);
		Thread newThread = storageThreadFactory.newThread(() -> {
			throw error;
		});
		newThread.start();
		newThread.join();
		assertEquals(null, ar.get());
	}
}
