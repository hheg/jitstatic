package io.jitstatic.hosted;

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

import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import javax.annotation.Nullable;
import javax.servlet.http.HttpServletRequest;

import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.transport.RefFilter;

import io.jitstatic.JitStaticConstants;

public class JitStaticRefFilter implements RefFilter {
    
    private final HttpServletRequest req;

    public JitStaticRefFilter(final HttpServletRequest req) {
        this.req = Objects.requireNonNull(req);
    }

    @Override
    @Nullable
    public Map<String, Ref> filter(final Map<String, Ref> refs) {
        if (refs != null) {
            if (!req.isUserInRole(JitStaticConstants.SECRETS)) {
                Ref secrets = refs.remove("refs/heads/" + JitStaticConstants.SECRETS);
                Ref head = refs.get("HEAD");
                if (secrets != null && head != null && secrets.getObjectId().equals(head.getObjectId())) {
                    refs.remove("HEAD");
                }
            }
            return refs.entrySet().stream()
                    .filter(e -> !e.getKey().startsWith(JitStaticConstants.REFS_JITSTATIC))
                    .filter(e -> !e.getKey().contains(".lock."))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        }
        return refs;
    }

}
