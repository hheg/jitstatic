package io.jitstatic.api;

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

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import com.codahale.metrics.annotation.ExceptionMetered;
import com.codahale.metrics.annotation.Metered;
import com.codahale.metrics.annotation.Timed;

import io.jitstatic.version.ProjectVersion;

@Path("info")
@Produces({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML })
public class JitstaticInfoResource {

	@GET
	@Timed(name = "commitid_time")
	@Metered(name = "commitid_counter")
	@ExceptionMetered(name = "commitid_exception")
	@Path("commitid")
	public String commitid() {
		return ProjectVersion.INSTANCE.getCommitId();
	}
	
	@GET
	@Timed(name = "version_time")
	@Metered(name = "version_counter")
	@ExceptionMetered(name = "version_exception")
	@Path("version")
	public String version() {
		return ProjectVersion.INSTANCE.getBuildVersion();
	}
}
