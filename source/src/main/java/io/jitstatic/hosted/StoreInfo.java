
package io.jitstatic.hosted;

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
import io.jitstatic.MetaData;
import io.jitstatic.source.ObjectStreamProvider;

@SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "It's exposed when exposed to client and it's serialized")
public class StoreInfo {
    private final MetaData metaData;
    private final String version;
    private final ObjectStreamProvider source;
    private final String metaDataVersion;

    public StoreInfo(final MetaData metaData, final String metaDataVersion) {
        this(null, null, metaData, null, metaDataVersion);
    }

    private StoreInfo(final byte[] data, final ObjectStreamProvider source, final MetaData metaData, final String sourceVersion, final String metaDataVersion) {
        this.source = source;        
        this.metaData = Objects.requireNonNull(metaData);
        this.version = sourceVersion;
        this.metaDataVersion = Objects.requireNonNull(metaDataVersion);
    }

    public StoreInfo(final ObjectStreamProvider source, final MetaData metaData, final String sourceVersion, final String metaDataVersion) {
        this(null, source, metaData, sourceVersion, metaDataVersion);
    }

    public MetaData getMetaData() {
        return metaData;
    }

    public String getVersion() {
        if (version == null) {
            throw new IllegalStateException("This is a metaData storeInfo");
        }
        return version;
    }

    public ObjectStreamProvider getStreamProvider() {
        if (source == null) {
            throw new IllegalStateException("This is a metaData storeInfo");
        }
        return source;
    }

    public boolean isMasterMetaData() {
        return source == null && version == null;
    }

    public boolean isNormalKey() {
        return !isMasterMetaData() && version != null && source != null;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + metaDataVersion.hashCode();
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
