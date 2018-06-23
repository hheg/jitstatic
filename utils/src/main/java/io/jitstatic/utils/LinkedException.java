package io.jitstatic.utils;

import java.io.PrintWriter;
import java.io.StringWriter;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class LinkedException extends RuntimeException {

    private static final long serialVersionUID = 1L;
    private final List<Exception> exceptions;

    public LinkedException(final List<Exception> errors) {
        super("");
        exceptions = new ArrayList<>(errors);
    }

    public LinkedException() {
        super("");
        exceptions = new ArrayList<>();
    }

    public void add(final Exception e) {
        if (e != null) {
            exceptions.add(e);
        }
    }

    @Override
    public Throwable fillInStackTrace() {
        return this;
    }

    @Override
    public String getMessage() {
        return exceptions.stream().map(e -> {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            return sw.toString();
        }).collect(Collectors.joining("and" + System.lineSeparator()));
    }

    public void addAll(List<Exception> errors) {
        exceptions.addAll(Objects.requireNonNull(errors));
    }

    public boolean isEmpty() {
        return exceptions.isEmpty();
    }
}
