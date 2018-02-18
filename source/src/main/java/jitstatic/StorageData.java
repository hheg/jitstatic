package jitstatic;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jitstatic.auth.User;

@SuppressFBWarnings(justification="Equals used here is not dodgy code",value = {"EQ_UNUSUAL"})
public class StorageData {

	private final Set<User> users;
	private final String contentType;
	
	@JsonCreator
	public StorageData(final @JsonProperty("users") Set<User> users, final @JsonProperty("contentType") String contentType) {
		this.users = Objects.requireNonNull(users);
		this.contentType = contentType == null ? "application/json" : contentType;
	}

	public Set<User> getUsers() {
		return users;
	}
	
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + users.hashCode();
		result = prime * result + getContentType().hashCode();
		return result;
	}

	@Override
	public boolean equals(final Object other) {
		return Optional.ofNullable(other)
				.filter(that -> that instanceof StorageData)
				.map(that -> (StorageData) that)
				.filter(that -> Objects.equals(this.users, that.users))
				.filter(that -> Objects.equals(this.getContentType(), that.getContentType()))
				.isPresent();
	}

	public String getContentType() {
		return contentType;
	}
}
