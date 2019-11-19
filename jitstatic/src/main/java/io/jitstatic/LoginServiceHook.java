package io.jitstatic;

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

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jvnet.hk2.annotations.Service;

import io.dropwizard.setup.Environment;
import io.jitstatic.hosted.LoginService;
import io.jitstatic.storage.HashService;
import io.jitstatic.storage.Storage;
import zone.dragon.dropwizard.lifecycle.InjectableManaged;

@Service
@Singleton
public class LoginServiceHook implements InjectableManaged {

    private final Environment env;
    private final Storage storage;
    private final HashService hashService;

    @Inject
    public LoginServiceHook(final Environment env, final Storage storage, final HashService hashService) {
        this.env = env;
        this.storage = storage;
        this.hashService = hashService;
    }

    @Override
    public void start() throws Exception {
        final LoginService loginService = env.getApplicationContext().getBean(LoginService.class);
        loginService.setHashService(hashService);
        loginService.setUserStorage(storage);
    }

    @Override
    public void stop() throws Exception {
        // NOOP
    }

}
