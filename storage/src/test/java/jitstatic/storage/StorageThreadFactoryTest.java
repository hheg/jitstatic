package jitstatic.storage;

import static org.junit.Assert.assertEquals;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.Test;

public class StorageThreadFactoryTest {

	@Test
	public void testCatchingException() throws InterruptedException {
		RuntimeException runtimeException = new RuntimeException();
		Consumer<Exception> test = t -> assertEquals(runtimeException, t);
		ExecutorService service = Executors.newSingleThreadExecutor(new StorageThreadFactory("test", test));
		service.submit(() -> {
			throw runtimeException;
		});
		service.awaitTermination(1, TimeUnit.SECONDS);
	}
}
