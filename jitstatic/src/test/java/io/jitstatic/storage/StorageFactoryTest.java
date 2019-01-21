package io.jitstatic.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import org.glassfish.jersey.server.filter.RolesAllowedDynamicFeature;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import io.dropwizard.auth.AuthDynamicFeature;
import io.dropwizard.auth.AuthValueFactoryProvider;
import io.dropwizard.jersey.setup.JerseyEnvironment;
import io.dropwizard.setup.Environment;
import io.jitstatic.JitStaticConstants;
import io.jitstatic.hosted.events.ReloadRefEventListener;
import io.jitstatic.source.Source;

public class StorageFactoryTest {

    private Environment env = mock(Environment.class);
    private JerseyEnvironment jersey = mock(JerseyEnvironment.class);
    private Source source = mock(Source.class);

    private StorageFactory sf = new StorageFactory();

    @Test
    public void testBuild() throws InterruptedException, ExecutionException, IOException {
        when(env.jersey()).thenReturn(jersey);
        try (Storage storage = sf.build(source, env, JitStaticConstants.JITSTATIC_KEYADMIN_REALM);) {
            assertEquals(Optional.empty(), storage.getKey("key", null));
        }
        verify(jersey).register(isA(AuthDynamicFeature.class));
        verify(jersey).register(RolesAllowedDynamicFeature.class);
        verify(jersey).register(isA(AuthValueFactoryProvider.Binder.class));
    }

    @Test
    public void testEmptyStoragePath() {
        when(env.jersey()).thenReturn(jersey);
        assertEquals(assertThrows(NullPointerException.class, () -> {
            try (Storage storage = sf.build(null, env, JitStaticConstants.JITSTATIC_KEYADMIN_REALM);) {
            }
        }).getLocalizedMessage(), "Source cannot be null");
    }

    @Test
    public void testListener() {
        when(env.jersey()).thenReturn(jersey);
        try (Storage build = sf.build(source, env, JitStaticConstants.JITSTATIC_KEYADMIN_REALM);) {
            ArgumentCaptor<ReloadRefEventListener> c = ArgumentCaptor.forClass(ReloadRefEventListener.class);
            verify(source).addListener(c.capture(), Mockito.eq(ReloadRefEventListener.class));
            c.getValue().onReload("refs/heads/master");
        }
    }

    @Test
    public void testListenerWithNullArgument() {
        when(env.jersey()).thenReturn(jersey);
        try (Storage build = sf.build(source, env, JitStaticConstants.JITSTATIC_KEYADMIN_REALM);) {
            ArgumentCaptor<ReloadRefEventListener> c = ArgumentCaptor.forClass(ReloadRefEventListener.class);
            verify(source).addListener(c.capture(), Mockito.eq(ReloadRefEventListener.class));
            assertThrows(NullPointerException.class, () -> c.getValue().onReload(null));
        }
    }
}
