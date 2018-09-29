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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Properties;

@SuppressWarnings("unused")
public class ProjectVersion {

	public static final ProjectVersion INSTANCE = new ProjectVersion();

	private final String tags;
	private final String branch;
	private final String dirty;
	private final String remoteOriginUrl;
	private final String commitId;
	private final String commitIdAbbrev;
	private final String describe;
	private final String describeShort;
	private final String commitUserName;
	private final String commitMessageFull;
	private final String commitMessageShort;
	private final String commitTime;
	private final String closestTagName;
	private final String closestTagCommitCount;
	private final String buildUserName;
	private final String buildTime;
	private final String buildHost;
	private final String buildVersion;

	private ProjectVersion() {
		try {
			final Properties properties = new Properties();
			properties.load(getClass().getClassLoader().getResourceAsStream("git.properties"));
			this.tags = String.valueOf(properties.get("git.tags"));
			this.branch = String.valueOf(properties.get("git.branch"));
			this.dirty = String.valueOf(properties.get("git.dirty"));
			this.remoteOriginUrl = String.valueOf(properties.get("git.remote.origin.url"));
			this.commitId = String.valueOf(properties.get("git.commit.id"));
			this.commitIdAbbrev = String.valueOf(properties.get("git.commit.id.abbrev"));
			this.describe = String.valueOf(properties.get("git.commit.id.describe"));
			this.describeShort = String.valueOf(properties.get("git.commit.id.describe-short"));
			this.commitUserName = String.valueOf(properties.get("git.commit.user.name"));
			this.commitMessageFull = String.valueOf(properties.get("git.commit.message.full"));
			this.commitMessageShort = String.valueOf(properties.get("git.commit.message.short"));
			this.commitTime = String.valueOf(properties.get("git.commit.time"));
			this.closestTagName = String.valueOf(properties.get("git.closest.tag.name"));
			this.closestTagCommitCount = String.valueOf(properties.get("git.closest.tag.commit.count"));
			this.buildUserName = String.valueOf(properties.get("git.build.user.name"));
			this.buildTime = String.valueOf(properties.get("git.build.time"));
			this.buildHost = String.valueOf(properties.get("git.build.host"));
			this.buildVersion = String.valueOf(properties.get("git.build.version"));
		} catch (final IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public String getCommitIdAbbrev() {
		return commitIdAbbrev;
	}

	public String getBuildVersion() {
		return buildVersion;
	}

	public String getCommitId() {
		return commitId;
	}
}
