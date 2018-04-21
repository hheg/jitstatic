package io.jitstatic.version;

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

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;

import io.jitstatic.version.ProjectVersion;
import org.junit.jupiter.api.Test;

public class ProjectVersionTest {
	
    @Test
	public void testProjectVersionTest() throws IOException {
		ProjectVersion pv = ProjectVersion.INSTANCE;
		System.out.println(pv.getBuildVersion());
		assertNotNull(pv.getBuildVersion());
		assertNotNull(pv.getCommitId());
		assertNotNull(pv.getCommitIdAbbrev());
	}
}
