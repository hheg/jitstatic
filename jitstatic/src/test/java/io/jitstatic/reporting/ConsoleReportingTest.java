package io.jitstatic.reporting;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.stream.Stream;

import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import io.dropwizard.lifecycle.Managed;
import io.dropwizard.lifecycle.setup.LifecycleEnvironment;
import io.dropwizard.setup.Environment;

public class ConsoleReportingTest {

    public static Stream<Arguments> data() {
        return Stream.of(Arguments.of("s", (Exception) null), Arguments.of("m", (Exception) null), Arguments.of("h", (Exception) null),
                Arguments.of("k", new IllegalArgumentException("k")));
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testConsoleReportingTest(String c, Exception error) throws Throwable {
        ConsoleReporting cr = new ConsoleReporting();
        cr.setRates(c);
        cr.setDurations(cr.getDurations());
        cr.setReportPeriods(cr.getReportPeriods());
        Executable e = () -> {
            Environment environment = Mockito.mock(Environment.class);
            LifecycleEnvironment lenv = Mockito.mock(LifecycleEnvironment.class);
            Mockito.when(environment.lifecycle()).thenReturn(lenv);
            cr.build(environment);
            ArgumentCaptor<Managed> manageCaptor = ArgumentCaptor.forClass(Managed.class);
            Mockito.verify(lenv).manage(manageCaptor.capture());
            Mockito.verify(environment).metrics();
            Managed managed = manageCaptor.getValue();
            managed.start();
            managed.stop();
        };

        if (error != null) {
            assertEquals(assertThrows(error.getClass(), e).getLocalizedMessage(), error.getLocalizedMessage());
        } else {
            e.execute();
        }
    }
}
