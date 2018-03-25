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

import java.util.Arrays;
import java.util.Objects;
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

public class JitStaticReceivePackFactory implements ReceivePackFactory<HttpServletRequest> {

	private final String defaultRef;
	private final ExecutorService repoExecutor;
	private final ErrorReporter errorReporter;
	private final RepositoryBus bus;

	public JitStaticReceivePackFactory(final ExecutorService repoExecutor, final ErrorReporter reporter,
			final String defaultRef, final RepositoryBus bus) {
		this.defaultRef = Objects.requireNonNull(defaultRef);
		this.errorReporter = Objects.requireNonNull(reporter);
		this.repoExecutor = Objects.requireNonNull(repoExecutor);
		this.bus = Objects.requireNonNull(bus);
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
		final ReceivePack rp = new JitStaticReceivePack(db, defaultRef, repoExecutor, errorReporter, bus);
		rp.setRefLogIdent(toPersonIdent(req, user));
		rp.setAtomic(true);
		rp.setPreReceiveHook(PreReceiveHookChain.newChain(Arrays.asList(new LogoPoster())));
		return rp;
	}

	private static PersonIdent toPersonIdent(final HttpServletRequest req, final String user) {
		return new PersonIdent(user, user + "@" + req.getRemoteHost());
	}
}
