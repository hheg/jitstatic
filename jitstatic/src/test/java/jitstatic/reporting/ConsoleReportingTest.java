package jitstatic.reporting;

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
