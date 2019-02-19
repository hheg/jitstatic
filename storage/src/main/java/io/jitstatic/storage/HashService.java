package io.jitstatic.storage;

/*-
 * #%L
 * jitstatic
 * %%
 * Copyright (C) 2017 - 2019 H.Hegardt
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

import java.io.Serializable;
import java.util.Set;

import org.apache.shiro.codec.Hex;
import org.apache.shiro.crypto.hash.DefaultHashService;
import org.apache.shiro.crypto.hash.Hash;
import org.apache.shiro.crypto.hash.HashRequest;
import org.apache.shiro.util.ByteSource;

import io.jitstatic.Role;
import io.jitstatic.auth.UserData;

public class HashService implements Serializable {

    private final String privateSalt;
    private final int iterations;

    public HashService() {
        this(null, 5);
    }

    public HashService(final String privateSalt, final int iterations) {
        this.privateSalt = privateSalt;
        this.iterations = iterations;
    }

    private static final long serialVersionUID = 7794693770611224576L;

    public boolean hasSamePassword(final UserData data, final String password) {
        if (data.getBasicPassword() == null || (data.getHash() != null && data.getSalt() != null)) {
            final DefaultHashService hasher = new DefaultHashService();
            if (privateSalt != null) {
                hasher.setPrivateSalt(ByteSource.Util.bytes(privateSalt));
            }
            hasher.setHashIterations(iterations);
            final HashRequest request = new HashRequest.Builder()
                    .setSource(ByteSource.Util.bytes(password))
                    .setSalt(ByteSource.Util.bytes(Hex.decode(data.getSalt()))).build();
            final Hash computedHash = hasher.computeHash(request);
            return computedHash.toHex().equals(data.getHash());
        } else {
            return data.getBasicPassword().equals(password);
        }
    }

    public UserData constructUserData(final Set<Role> roles, final String password) {
        final DefaultHashService hasher = new DefaultHashService();
        if (privateSalt != null) {
            hasher.setPrivateSalt(ByteSource.Util.bytes(privateSalt));
        }
        hasher.setHashIterations(iterations);
        final ByteSource salt = hasher.getRandomNumberGenerator().nextBytes(64);
        final HashRequest request = new HashRequest.Builder()
                .setSource(ByteSource.Util.bytes(password))
                .setSalt(salt).build();
        final Hash computedHash = hasher.computeHash(request);
        return new UserData(roles, null, salt.toHex(), computedHash.toHex());
    }

}
