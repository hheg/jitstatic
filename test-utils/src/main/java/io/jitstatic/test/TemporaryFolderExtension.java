package io.jitstatic.test;

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

import java.util.stream.Stream;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestInstancePostProcessor;

public class TemporaryFolderExtension implements TestInstancePostProcessor, AfterEachCallback, AfterAllCallback {

    private final TemporaryFolder folder;

    public TemporaryFolderExtension() {
        folder = new TemporaryFolder();
    }

    @Override
    public void postProcessTestInstance(final Object testInstance, final ExtensionContext context) throws Exception {
        Stream.of(testInstance.getClass().getDeclaredFields()).filter(f -> f.getType() == TemporaryFolder.class).forEach(f -> {
            f.setAccessible(true);
            try {
                f.set(testInstance, folder);
            } catch (IllegalArgumentException | IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        folder.cleanup();
    }

    @Override
    public void afterAll(ExtensionContext context) throws Exception {
        folder.destroy();
    }

}
