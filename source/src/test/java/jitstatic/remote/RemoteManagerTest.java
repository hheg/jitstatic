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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.any;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertEquals;

import java.net.URISyntaxException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.ArgumentCaptor;

import jitstatic.source.SourceEventListener;

public class RemoteManagerTest {

	@Rule
	public ExpectedException ex = ExpectedException.none();
	private RemoteRepositoryManager rmr = mock(RemoteRepositoryManager.class);

	@Test
	public void testRemoteRepositoryManagerPolling() throws URISyntaxException {
		SourceEventListener mock = mock(SourceEventListener.class);
		try (RemoteManager rrm = new RemoteManager(rmr, new ScheduledThreadPoolExecutor(1), 1, TimeUnit.SECONDS);) {
			when(rmr.checkRemote()).thenReturn(() -> mock.onEvent());
			rrm.start();
			// TODO make this more deterministic...
			verify(mock, timeout(100).times(1)).onEvent();
			verify(mock, timeout(1 * 1200).times(2)).onEvent();
		}
	}

	@Test
	public void testRemoteHealthCheck() {
		ex.expect(RuntimeException.class);
		ex.expectCause(isA(IllegalArgumentException.class));
		try (RemoteManager rrm = new RemoteManager(rmr, new ScheduledThreadPoolExecutor(1), 1, TimeUnit.SECONDS);) {
			when(rmr.getFault()).thenReturn(new IllegalArgumentException("Illegal"));
			rrm.checkHealth();
		}
	}

	@Test
	public void testRemoteManagerSchedulerDefaults() {
		ScheduledExecutorService exec = mock(ScheduledExecutorService.class);
		try (RemoteManager rrm = new RemoteManager(rmr, exec, 1, TimeUnit.SECONDS);) {
			rrm.start();
			ArgumentCaptor<Long> captor = ArgumentCaptor.forClass(Long.class);
			verify(exec).scheduleAtFixedRate(any(Runnable.class), any(Long.class), captor.capture(), any());
			assertEquals(Long.valueOf(5), captor.getValue());
		}
	}

}
