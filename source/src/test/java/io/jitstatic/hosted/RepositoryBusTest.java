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
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.jitstatic.hosted.ErrorReporter;
import io.jitstatic.hosted.RepositoryBus;
import io.jitstatic.source.SourceEventListener;

public class RepositoryBusTest {

	@Test
	public void testRepositoryBus() {
		ErrorReporter errorReporter = new ErrorReporter();
		RepositoryBus bus = new RepositoryBus(errorReporter);
		SourceEventListener sel = Mockito.mock(SourceEventListener.class);
		bus.addListener(sel);
		bus.process(Arrays.asList("1"));
		Mockito.verify(sel).onEvent(Mockito.any());
		assertEquals(null, errorReporter.getFault());
	}
	
	@Test
	public void testRepositoryBusSelThrowsError() {
		ErrorReporter errorReporter = new ErrorReporter();
		RuntimeException e = new RuntimeException("Test Triggered");
		RepositoryBus bus = new RepositoryBus(errorReporter);
		SourceEventListener sel = Mockito.mock(SourceEventListener.class);
		bus.addListener(sel);
		Mockito.doThrow(e).when(sel).onEvent(Mockito.any());
		bus.process(Arrays.asList("refs/heads/someref"));
		assertEquals(e,errorReporter.getFault());
	}
}
