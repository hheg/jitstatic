package jitstatic;

import com.codahale.metrics.health.HealthCheck;

import jitstatic.source.Source;

public class SourceHealthChecker extends HealthCheck {
	
	private final Source source;

	public SourceHealthChecker(Source source) {
		this.source = source;
	}

	@Override
	protected Result check() throws Exception {
		try{
			source.checkHealth();
			return Result.healthy();
		}catch(final Exception e) {
			return Result.unhealthy(e);
		}
	}

}
