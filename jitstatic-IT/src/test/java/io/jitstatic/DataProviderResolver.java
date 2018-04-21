package io.jitstatic;

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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

public class DataProviderResolver implements ParameterResolver {
    AtomicInteger i = new AtomicInteger();
    List<Object[]> asList = Arrays.asList(new Object[][] { { new String[] { "a" }, new String[] { LoadTesterTest.MASTER }, false },
            { new String[] { "a", "b", "c" }, new String[] { LoadTesterTest.MASTER, "develop", "something" }, false },
            { new String[] { "a" }, new String[] { LoadTesterTest.MASTER }, true },
            { new String[] { "a", "b", "c" }, new String[] { LoadTesterTest.MASTER, "develop", "something" }, true } });

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
        return parameterContext.getParameter().getType() == TestData.class;
    }

    @Override
    public TestData resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
        Object[] objects = asList.get(i.getAndIncrement());        
        return new TestData((String[]) objects[0], (String[]) objects[1], (boolean) objects[2]);
    }
}
