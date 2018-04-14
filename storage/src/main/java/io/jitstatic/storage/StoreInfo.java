package io.jitstatic.storage;

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

import java.util.Objects;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.jitstatic.StorageData;

@SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "It's exposed when exposed to client and it's serialized")
public class StoreInfo {
	private final StorageData metaData;
	private final String version;
	private final byte[] data;
    private final String metaDataVersion;

	public StoreInfo(final byte[] data, final StorageData metaData, final String version, final String metaDataVersion) {
		this.data = Objects.requireNonNull(data);
		this.metaData = Objects.requireNonNull(metaData);
		this.version = Objects.requireNonNull(version);
		this.metaDataVersion = Objects.requireNonNull(metaDataVersion);
	}

	public StorageData getStorageData() {
		return metaData;
	}

	public String getVersion() {
		return version;
	}

	public byte[] getData() {
		return data;
	}

	@Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((metaDataVersion == null) ? 0 : metaDataVersion.hashCode());
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
        if (metaDataVersion == null) {
            if (other.metaDataVersion != null)
                return false;
        } else if (!metaDataVersion.equals(other.metaDataVersion))
            return false;
        if (version == null) {
            if (other.version != null)
                return false;
        } else if (!version.equals(other.version))
            return false;
        return true;
    }

    public String getMetaDataVersion() {
        return metaDataVersion;
    }

}
