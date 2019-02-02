package io.jitstatic.source;

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

import java.io.IOException;
import java.io.InputStream;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.jitstatic.MetaData;
import io.jitstatic.check.MetaFileData;
import io.jitstatic.check.SourceFileData;
import io.jitstatic.hosted.InputStreamHolder;
import io.jitstatic.hosted.SourceHandler;
//TODO Remove this SpotBugs Error
@SuppressFBWarnings(value="RCN_REDUNDANT_NULLCHECK_WOULD_HAVE_BEEN_A_NPE",justification="This is a false positive in Java 11, should be removed")
public class SourceInfo {

    private final MetaFileData metaFileData;
    private final SourceFileData sourceFileData;
    private final int threshold;

    public SourceInfo(final MetaFileData metaFileData, final SourceFileData sourceFileData) {
        this.metaFileData = metaFileData;
        if (sourceFileData == null && !metaFileData.isMasterMetaData()) {
            throw new IllegalArgumentException(String
                    .format("sourceFileData cannot be null if metaFileData %s is not a masterMetaData file", metaFileData.getFileName()));
        }
        this.sourceFileData = sourceFileData;
        threshold = 1_000_000;
    }

    public String getSourceVersion() {
        if (sourceFileData == null) {
            return null;
        }
        return sourceFileData.getVersion();
    }

    public String getMetaDataVersion() {
        return metaFileData.getVersion();
    }

    public InputStream getMetadataInputStream() throws IOException {
        return metaFileData.getInputStream();
    }

    public boolean isMetaDataSource() {
        return sourceFileData == null && metaFileData != null;
    }

    public boolean hasKeyMetaData() {
        return metaFileData.isKeyMetaFile();
    }

    public ObjectStreamProvider getSourceProvider() throws IOException {
        if(sourceFileData == null) {
            return null;
        }
        InputStreamHolder inputStreamHolder = sourceFileData.getInputStreamHolder();
        final long size = inputStreamHolder.getSize();        
        if (size < threshold) {
            try (InputStream storageStream = inputStreamHolder.getInputStreamProvider().get()) {
                return new SmallObjectStreamProvider(SourceHandler.readStorageData(storageStream));
            }
        } else {
            return new LargeObjectStreamProvider(inputStreamHolder.getInputStreamProvider(), size);
        }

    }

    public MetaData readMetaData() throws IOException {
        try (final InputStream metaDataStream = getMetadataInputStream()) {
            return SourceHandler.readMetaData(metaDataStream);
        }
    }
}
