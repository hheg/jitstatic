package jitstatic;

import io.dropwizard.lifecycle.Managed;
import jitstatic.source.Source;

public class ManagedObject<T extends AutoCloseable & Source>  implements Managed {

	private final T object;
	
	public ManagedObject(T object) {
		this.object = object;
	}
	
	
	@Override
	public void start() throws Exception {
		this.object.start();
	}

	@Override
	public void stop() throws Exception {
		this.object.close();
	}

}
