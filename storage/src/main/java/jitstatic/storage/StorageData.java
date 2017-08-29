package jitstatic.storage;

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

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import jitstatic.auth.User;

public class StorageData {

	private final Set<User> users;

	private final JsonNode data;

	@JsonCreator
	public StorageData(final @JsonDeserialize(as=LinkedHashSet.class) @JsonProperty("users") Set<User> users, final @JsonProperty("data") JsonNode data) {
		this.users = Objects.requireNonNull(users);
		this.data = Objects.requireNonNull(data);
	}

	public Set<User> getUsers() {
		return users;
	}

	public JsonNode getData() {
		return data;
	}
	
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + data.hashCode();
		result = prime * result + users.hashCode();
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		StorageData other = (StorageData) obj;
		if (!data.equals(other.data))
			return false;
		if (!users.equals(other.users))
			return false;
		return true;
	}
}
