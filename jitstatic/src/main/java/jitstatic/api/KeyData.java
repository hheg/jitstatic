package jitstatic.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

public class KeyData {
	
	private final String version;
	private final JsonNode data;
	
	@JsonCreator
	public KeyData(final @JsonProperty String version, final @JsonProperty JsonNode data)  {
		this.version = version;
		this.data = data;
	}

	public String getVersion() {
		return version;
	}

	public JsonNode getData() {
		return data;
	}
}
