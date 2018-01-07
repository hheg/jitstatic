package jitstatic.hosted;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;

import org.junit.Test;
import org.mockito.Mockito;

import jitstatic.source.SourceEventListener;

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
