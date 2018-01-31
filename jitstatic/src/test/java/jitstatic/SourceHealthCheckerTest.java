package jitstatic;

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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import org.junit.Test;

import com.codahale.metrics.health.HealthCheck.Result;

import jitstatic.source.Source;

public class SourceHealthCheckerTest {
	
	private final Source source = mock(Source.class);

	@Test
	public void testSourceHealthCheckerHealthy() throws Exception {		
		HealthChecker shc = new HealthChecker(source);
		assertTrue(shc.check().isHealthy());
	}

	@Test
	public void testSourceHealthCheckerNotHealthy() throws Exception {
		RuntimeException runtimeException = new RuntimeException("error");
		doThrow(runtimeException).when(source).checkHealth();
		HealthChecker shc = new HealthChecker(source);
		Result check = shc.check();
		assertFalse(check.isHealthy());
		assertEquals(runtimeException, check.getError());
	}
}
