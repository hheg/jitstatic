package io.jitstatic.injection.configuration;

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

import static org.junit.jupiter.api.Assertions.*;

import java.util.function.BiPredicate;

import org.junit.jupiter.api.Test;

import io.jitstatic.injection.configuration.hosted.HostedFactory;

class JitstaticConfigurationTest {

    @Test
    void testGetRootAuthenticator() {
        JitstaticConfiguration conf = new JitstaticConfiguration();
        conf.setHostedFactory(new HostedFactory());
        HostedFactory hostedFactory = conf.getHostedFactory();
        hostedFactory.setUserName("user");
        hostedFactory.setSecret("secret");
        BiPredicate<String, String> rootAuthenticator = conf.getRootAuthenticator();
        assertTrue(rootAuthenticator.test("user", "secret"));
        assertFalse(rootAuthenticator.test("blah", "secret"));
    }
}
