package io.jitstatic.injection.configuration;

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
