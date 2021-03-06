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

import java.nio.charset.StandardCharsets;
import java.util.List;

public class WriteData {

    final List<String> branches;
    final List<String> names;

    WriteData(final List<String> branches, final List<String> names) {
        this.branches = branches;
        this.names = names;
    }

    @Override
    public String toString() {
        return "WriteData [branches=" + branches + ", names=" + names + "]";
    }

    byte[] getData(int c) {
        return new StringBuilder("{\"data\":").append(c).append(",").append("\"fill\":").append("\"").append(fill()).append("\"").append("}").toString().getBytes(StandardCharsets.UTF_8);
    }

    String fill() {
        return "";
    }

}
