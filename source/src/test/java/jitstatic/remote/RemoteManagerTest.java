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

import static org.hamcrest.Matchers.isA;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import jitstatic.CorruptedSourceException;
import jitstatic.source.SourceEventListener;

public class RemoteManagerTest {

	@Rule
	public ExpectedException ex = ExpectedException.none();
	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private RemoteRepositoryManager rmr = mock(RemoteRepositoryManager.class);

	@Test
	public void testRemoteManagerPublicConstructor()
			throws IllegalStateException, GitAPIException, IOException, CorruptedSourceException {
		String store = "storage";
		File base = folder.newFolder();
		try (Git git = setUpRepo(base, store);) {
			try (RemoteManager rrm = new RemoteManager(base.toURI(), null, null, 1, TimeUnit.SECONDS,
					folder.newFolder().toPath(), "refs/heads/master")) {

			}
		}
	}

	@Test
	public void testRemoteRepositoryManagerPolling() throws URISyntaxException {
		SourceEventListener mock = mock(SourceEventListener.class);
		Runnable r = () -> mock.onEvent(null);
		when(rmr.checkRemote()).thenReturn(r);
		try (RemoteManager rrm = new RemoteManager(rmr, new ScheduledThreadPoolExecutor(1), 1, TimeUnit.SECONDS,
				null);) {
			rrm.addListener(mock);
			rrm.start();
			// TODO make this more deterministic...
			verify(mock, timeout(100).times(1)).onEvent(null);
			verify(mock, timeout(1 * 1200).times(2)).onEvent(null);

		}
	}

	@Test
	public void testRemoteHealthCheck() {
		ex.expect(RuntimeException.class);
		ex.expectCause(isA(IllegalArgumentException.class));
		try (RemoteManager rrm = new RemoteManager(rmr, new ScheduledThreadPoolExecutor(1), 1, TimeUnit.SECONDS,
				null);) {
			when(rmr.getFault()).thenReturn(new IllegalArgumentException("Illegal"));
			rrm.checkHealth();
		}
	}

	@Test
	public void testRemoteManagerSchedulerDefaults() {
		ScheduledExecutorService exec = mock(ScheduledExecutorService.class);
		try (RemoteManager rrm = new RemoteManager(rmr, exec, 0, TimeUnit.SECONDS, null);) {
			rrm.start();
			ArgumentCaptor<Long> captor = ArgumentCaptor.forClass(Long.class);
			verify(exec).scheduleWithFixedDelay(eq(null), eq(0L), captor.capture(), eq(TimeUnit.SECONDS));
			assertEquals(Long.valueOf(5), captor.getValue());
		}
	}

	@Test
	public void testRemoteManagerSchedulerSetPollingValue() {
		ScheduledExecutorService exec = mock(ScheduledExecutorService.class);
		try (RemoteManager rrm = new RemoteManager(rmr, exec, 1, TimeUnit.SECONDS, null);) {
			rrm.start();
			ArgumentCaptor<Long> captor = ArgumentCaptor.forClass(Long.class);
			verify(exec).scheduleWithFixedDelay(eq(null), eq(0L), captor.capture(), eq(TimeUnit.SECONDS));
			assertEquals(Long.valueOf(1), captor.getValue());
		}
	}
	
	@Test
	public void testGetStorageInptuStream() throws Exception {
		ScheduledExecutorService exec = mock(ScheduledExecutorService.class);
		when(rmr.getStorageInputStream(Mockito.anyString(), Mockito.anyString())).thenReturn(null);
		try (RemoteManager rrm = new RemoteManager(rmr, exec, 1, TimeUnit.SECONDS, null);) {
			assertNull(rrm.getSourceStream("key", null));
		}
	}
	
	@Test
	public void testGetStorageInptuStreamGetException() throws Exception {
		ex.expect(RuntimeException.class);
		ex.expectCause(Matchers.isA(IOException.class));
		ScheduledExecutorService exec = mock(ScheduledExecutorService.class);
		when(rmr.getStorageInputStream(Mockito.anyString(), Mockito.anyString())).thenThrow(IOException.class);
		try (RemoteManager rrm = new RemoteManager(rmr, exec, 1, TimeUnit.SECONDS, null);) {
			assertNull(rrm.getSourceStream("key", null));
		}
	}

	private Git setUpRepo(File base, String store) throws IllegalStateException, GitAPIException, IOException {
		Git git = Git.init().setDirectory(base).call();
		Files.write(base.toPath().resolve(store), getData().getBytes("UTF-8"), StandardOpenOption.CREATE);
		git.add().addFilepattern(store).call();
		git.commit().setMessage("Init").call();
		return git;
	}

	private String getData() {
		return getData(1);
	}

	private String getData(int i) {
		return "{\"data\":{\"key" + i
				+ "\":{\"data\":\"value1\",\"users\":[{\"password\":\"1234\",\"user\":\"user1\"}]},\"key3\":{\"data\":\"value3\",\"users\":[{\"password\":\"1234\",\"user\":\"user1\"}]}},\"users\":[{\"password\":\"1234\",\"user\":\"user1\"}]}";
	}

}
