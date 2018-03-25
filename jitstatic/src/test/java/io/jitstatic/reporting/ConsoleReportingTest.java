package io.jitstatic.reporting;

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

import java.util.Arrays;
import java.util.Collection;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import io.dropwizard.lifecycle.Managed;
import io.dropwizard.lifecycle.setup.LifecycleEnvironment;
import io.dropwizard.setup.Environment;
import io.jitstatic.reporting.ConsoleReporting;

@RunWith(Parameterized.class)
public class ConsoleReportingTest {

	private ConsoleReporting cr;
	private Exception error;

	public ConsoleReportingTest(String c, Exception e) {
		this.error = e;
		cr = new ConsoleReporting();
		cr.setRates(c);
		cr.setDurations(cr.getDurations());
		cr.setReportPeriods(cr.getReportPeriods());
	}

	@Parameterized.Parameters
	public static Collection<Object[]> data() {
		return Arrays.asList(new Object[][] { { "s", null }, { "m", null }, { "h", null },
				{ "k", new IllegalArgumentException("k") }, });
	}

	@Rule
	public ExpectedException ex = ExpectedException.none();

	@Test
	public void testConsoleReportingTest() throws Exception {
		if (error != null) {
			ex.expect(error.getClass());
			ex.expectMessage(error.getMessage());
		}		
		Environment environment = Mockito.mock(Environment.class);
		LifecycleEnvironment lenv = Mockito.mock(LifecycleEnvironment.class);
		Mockito.when(environment.lifecycle()).thenReturn(lenv);
		cr.build(environment);
		ArgumentCaptor<Managed> manageCaptor = ArgumentCaptor.forClass(Managed.class);
		Mockito.verify(lenv).manage(manageCaptor.capture());
		Mockito.verify(environment).metrics();
		Managed managed = manageCaptor.getValue();
		managed.start();
		managed.stop();
	}
}
