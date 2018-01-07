package jitstatic.api;

import javax.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

public class ModifyKeyData {
	
	@JsonProperty
	@NotNull
	private String message;
	
	@JsonProperty
	@NotNull
	private String version;
	
	@JsonProperty
	@NotNull
	private JsonNode data;
	

	public String getVersion() {
		return version;
	}

	public void setVersion(final String version) {
		this.version = version;
	}

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
}
