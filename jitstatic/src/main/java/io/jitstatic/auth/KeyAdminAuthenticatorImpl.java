package io.jitstatic.auth;

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

import static io.jitstatic.JitStaticConstants.JITSTATIC_KEYADMIN_REALM;

import java.util.Objects;

import org.eclipse.jgit.api.errors.RefNotFoundException;

import io.jitstatic.storage.HashService;
import io.jitstatic.storage.Storage;

public class KeyAdminAuthenticatorImpl implements KeyAdminAuthenticator {

    private final KeyAdminAuthenticator legacyKeyAuthenticator;
    private final Storage storage;
    private final String defaultRef;
    private final HashService hashService;

    public KeyAdminAuthenticatorImpl(final Storage userService, final KeyAdminAuthenticator rootUserAuthenticator, final String defaultRef,
            final HashService hashService) {
        this.storage = Objects.requireNonNull(userService);
        this.legacyKeyAuthenticator = Objects.requireNonNull(rootUserAuthenticator);
        this.defaultRef = Objects.requireNonNull(defaultRef);
        this.hashService = Objects.requireNonNull(hashService);
    }

    @Override
    public boolean authenticate(final User user, String ref) {
        if (ref == null) {
            ref = defaultRef;
        }
        if (legacyKeyAuthenticator.authenticate(user, ref)) {
            return true;
        }

        try {
            UserData userData = storage.getUser(user.getName(), ref, JITSTATIC_KEYADMIN_REALM);
            if (userData == null) {
                return false;
            }
            return hashService.hasSamePassword(userData, user.getPassword());
        } catch (final RefNotFoundException e) {
            return false;
        }
    }

}
