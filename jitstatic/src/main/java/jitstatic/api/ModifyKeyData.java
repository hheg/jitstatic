package jitstatic.api;

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

import javax.validation.constraints.NotNull;

import org.hibernate.validator.constraints.NotEmpty;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

public class ModifyKeyData {
	
	@JsonProperty
	@NotNull
	@NotEmpty
	private String message;
	
	@JsonProperty
	@NotNull
	private String haveVersion;
	
	@JsonProperty
	@NotNull
	private JsonNode data;
	
	@JsonProperty
	private String userMail;

	public JsonNode getData() {
		return data;
	}

	public void setData(final JsonNode data) {
		this.data = data;
	}

	public String getMessage() {		
		return message;
	}
	
	public void setMessage(final String message) {
		this.message = message;
	}

	public String getHaveVersion() {
		return haveVersion;
	}

	public void setHaveVersion(String haveVersion) {
		this.haveVersion = haveVersion;
	}

	public String getUserMail() {
		return userMail;
	}

	public void setUserMail(String userMail) {
		this.userMail = userMail;
	}
}
