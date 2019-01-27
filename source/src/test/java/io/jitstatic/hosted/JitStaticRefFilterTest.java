package io.jitstatic.hosted;

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

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.jitstatic.JitStaticConstants;

class JitStaticRefFilterTest {

    @Test
    void testJitStaticRefFilter() {
        HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
        Mockito.when(req.isUserInRole(Mockito.eq(JitStaticConstants.SECRETS))).thenReturn(false);
        JitStaticRefFilter filter = new JitStaticRefFilter(req);
        final Map<String, Ref> refs = new HashMap<>();
        Ref HEAD = Mockito.mock(Ref.class);
        Ref secrets = Mockito.mock(Ref.class);
        Ref jiststaticRef = Mockito.mock(Ref.class);
        Ref lockref = Mockito.mock(Ref.class);
        
        ObjectId objectId = ObjectId.fromString("c8740c60b3e09d4dde3d7c6b9ec0203f9014f0ec");
        Mockito.when(secrets.getObjectId()).thenReturn(objectId);
        Mockito.when(HEAD.getObjectId()).thenReturn(objectId);
        refs.put("HEAD", HEAD);
        refs.put("refs/heads/secrets", secrets);
        refs.put("refs/jitstatic/blipp",jiststaticRef);
        refs.put("refs/.lock./secrets", lockref);
        Map<String, Ref> advertisedRefs = filter.filter(refs);
        assertFalse(advertisedRefs.containsKey("HEAD"));
        assertFalse(advertisedRefs.containsKey("refs/heads/secrets"));
        assertFalse(advertisedRefs.containsKey("refs.lock.secrets"));
        assertFalse(advertisedRefs.containsKey("refs/jitstatic/blipp"));
    }

}
