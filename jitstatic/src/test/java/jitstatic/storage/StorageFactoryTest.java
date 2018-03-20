package jitstatic.storage;

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

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import org.eclipse.jgit.lib.Constants;
import org.glassfish.jersey.server.filter.RolesAllowedDynamicFeature;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;

import io.dropwizard.auth.AuthDynamicFeature;
import io.dropwizard.auth.AuthValueFactoryProvider;
import io.dropwizard.jersey.setup.JerseyEnvironment;
import io.dropwizard.setup.Environment;
import jitstatic.source.Source;
import jitstatic.source.SourceEventListener;

public class StorageFactoryTest {

    private Environment env = mock(Environment.class);
    private JerseyEnvironment jersey = mock(JerseyEnvironment.class);
    private Source source = mock(Source.class);

    @Rule
    public final ExpectedException ex = ExpectedException.none();

    @Rule
    public final TemporaryFolder tempFolder = new TemporaryFolder();

    private StorageFactory sf = new StorageFactory();

    @Test
    public void testBuild() throws InterruptedException, ExecutionException {
        when(env.jersey()).thenReturn(jersey);
        try (Storage storage = sf.build(source, env);) {
            storage.reload(Arrays.asList(Constants.R_HEADS + Constants.MASTER));
            assertEquals(Optional.empty(), storage.get("key", null).get());
        }
        verify(jersey).register(isA(AuthDynamicFeature.class));
        verify(jersey).register(RolesAllowedDynamicFeature.class);
        verify(jersey).register(isA(AuthValueFactoryProvider.Binder.class));
    }

    @Test
    public void testEmptyStoragePath() {
        when(env.jersey()).thenReturn(jersey);
        ex.expect(NullPointerException.class);
        ex.expectMessage("Source cannot be null");
        try (Storage storage = sf.build(null, env);) {
        }
    }

    @Test
    public void testListener() {
        when(env.jersey()).thenReturn(jersey);
        try (Storage build = sf.build(source, env);) {
            ArgumentCaptor<SourceEventListener> c = ArgumentCaptor.forClass(SourceEventListener.class);
            verify(source).addListener(c.capture());
            c.getValue().onEvent(Collections.emptyList());
        }
    }

    @Test
    public void testListenerWithNullArgument() {
        when(env.jersey()).thenReturn(jersey);
        try (Storage build = sf.build(source, env);) {
            ArgumentCaptor<SourceEventListener> c = ArgumentCaptor.forClass(SourceEventListener.class);
            verify(source).addListener(c.capture());
            c.getValue().onEvent(null);
        }
    }
}
