package io.jitstatic.injection.executors;

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

import org.glassfish.hk2.api.AnnotationLiteral;

public final class DefaultExecutorAnnotation extends AnnotationLiteral<DefaultExecutor> implements DefaultExecutor {
    private static final long serialVersionUID = 1L;
    public static final DefaultExecutor INSTANCE = new DefaultExecutorAnnotation();
    
    private DefaultExecutorAnnotation() {
    }
}
