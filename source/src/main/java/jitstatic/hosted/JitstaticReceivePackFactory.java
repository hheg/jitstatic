package jitstatic.hosted;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;

import javax.servlet.http.HttpServletRequest;

import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.PreReceiveHookChain;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.resolver.ReceivePackFactory;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;
import org.eclipse.jgit.transport.resolver.ServiceNotEnabledException;

public class JitstaticReceivePackFactory implements ReceivePackFactory<HttpServletRequest> {

	private final String defaultRef;
	private final ExecutorService repoExecutor;
	private final ErrorReporter errorReporter;

	public JitstaticReceivePackFactory(final ExecutorService repoExecutor, final ErrorReporter reporter,
			final String defaultRef) {
		this.defaultRef = defaultRef;
		this.errorReporter = reporter;
		this.repoExecutor = repoExecutor;
	}

	static class ServiceConfig {
		final boolean set;

		final boolean enabled;

		ServiceConfig(final Config cfg) {
			set = cfg.getString("http", null, "receivepack") != null;
			enabled = cfg.getBoolean("http", "receivepack", false);
		}
	}

	@Override
	public ReceivePack create(HttpServletRequest req, Repository db)
			throws ServiceNotEnabledException, ServiceNotAuthorizedException {
		final ServiceConfig cfg = db.getConfig().get(ServiceConfig::new);
		String user = req.getRemoteUser();

		if (cfg.set) {
			if (cfg.enabled) {
				if (user == null || "".equals(user))
					user = "anonymous";
				return createFor(req, db, user);
			}
			throw new ServiceNotEnabledException();
		}

		if (user != null && !"".equals(user))
			return createFor(req, db, user);
		throw new ServiceNotAuthorizedException();
	}

	protected ReceivePack createFor(final HttpServletRequest req, final Repository db, final String user) {
		final ReceivePack rp = new JitstaticReceivePack(db, defaultRef, getRepoExecutor(), errorReporter);
		rp.setRefLogIdent(toPersonIdent(req, user));
		rp.setAtomic(true);
		rp.setPreReceiveHook(PreReceiveHookChain.newChain(Arrays.asList(new LogoPoster())));
		return rp;
	}

	private static PersonIdent toPersonIdent(final HttpServletRequest req, final String user) {
		return new PersonIdent(user, user + "@" + req.getRemoteHost());
	}

	public ExecutorService getRepoExecutor() {
		return repoExecutor;
	}

}
