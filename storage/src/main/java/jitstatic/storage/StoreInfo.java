package jitstatic.storage;

import java.util.Objects;

public class StoreInfo {
	private final StorageData data;
	private final String version;
	
	public StoreInfo(final StorageData data, final String version) {
		this.data = Objects.requireNonNull(data);
		this.version = Objects.requireNonNull(version);
	}

	public StorageData getStorageData() {
		return data;
	}

	public String getVersion() {
		return version;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((data == null) ? 0 : data.hashCode());
		result = prime * result + ((version == null) ? 0 : version.hashCode());
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
		StoreInfo other = (StoreInfo) obj;
		if (data == null) {
			if (other.data != null)
				return false;
		} else if (!data.equals(other.data))
			return false;
		if (version == null) {
			if (other.version != null)
				return false;
		} else if (!version.equals(other.version))
			return false;
		return true;
	}
}
