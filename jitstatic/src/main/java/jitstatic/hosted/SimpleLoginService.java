package jitstatic.hosted;

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


import java.util.Arrays;
import java.util.Objects;

import org.eclipse.jetty.security.AbstractLoginService;
import org.eclipse.jetty.util.security.Password;

class SimpleLoginService extends AbstractLoginService {

	private final String[] roles = new String[] { "gitrole" };
	private UserPrincipal principal;

	public SimpleLoginService(final String userName, final String secret, final String realm) {
		this._name = Objects.requireNonNull(realm);
		this.principal = new UserPrincipal(Objects.requireNonNull(userName), new Password(Objects.requireNonNull(secret)));
	}

	@Override
	protected String[] loadRoleInfo(final UserPrincipal user) {
		if (principal.getName().equals(user.getName())) {
			return Arrays.copyOf(roles, 1);
		}
		return new String[] {};

	}

	@Override
	protected UserPrincipal loadUserInfo(final String username) {
		if (principal.getName().equals(username)) {
			return principal;
		}
		return null;
	}
}
