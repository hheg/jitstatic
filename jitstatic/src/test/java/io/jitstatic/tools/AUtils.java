package io.jitstatic.tools;

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

import static org.hamcrest.MatcherAssert.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Objects;
import java.util.SortedMap;
import java.util.stream.Collectors;

import org.hamcrest.Matchers;

import com.codahale.metrics.health.HealthCheck.Result;

import io.dropwizard.testing.ResourceHelpers;
import io.dropwizard.testing.junit5.DropwizardAppExtension;
import io.jitstatic.JitstaticConfiguration;

public class AUtils {

    public static void checkContainerForErrors(DropwizardAppExtension<JitstaticConfiguration> dw) {
        SortedMap<String, Result> healthChecks = dw.getEnvironment().healthChecks().runHealthChecks();
        List<Throwable> errors = healthChecks.entrySet().stream().map(e -> e.getValue().getError()).filter(Objects::nonNull)
                .collect(Collectors.toList());
        assertThat(errors.stream().map(e -> {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            return new StringBuilder(sw.toString());
        }).map(sb -> sb.append(",")).map(sb -> sb.toString()).collect(Collectors.joining(",")), errors.isEmpty(), Matchers.is(true));
    }

    public static String getDropwizardConfigurationResource() {
        if (System.getenv("CI") != null) {
            return ResourceHelpers.resourceFilePath("simpleserver_ci.yaml");
        } else {
            return ResourceHelpers.resourceFilePath("simpleserver.yaml");
        }
    }

}
