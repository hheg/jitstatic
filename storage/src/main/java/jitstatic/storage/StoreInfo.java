package jitstatic.storage;

public class StoreInfo {
	private final StorageData data;
	private final String version;
	
	public StoreInfo(final StorageData data, final String version) {
		this.data = data;
		this.version = version;
	}

	public StorageData getData() {
		return data;
	}

	public String getVersion() {
		return version;
	}
}
