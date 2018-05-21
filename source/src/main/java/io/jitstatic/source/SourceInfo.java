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

import io.jitstatic.MetaFileData;
import io.jitstatic.SourceFileData;

public class SourceInfo {

    private final MetaFileData metaFileData;
    private final SourceFileData sourceFileData;

    public SourceInfo(final MetaFileData metaFileData, final SourceFileData sourceFileData) {
        this.metaFileData = metaFileData;
        if (sourceFileData == null && !metaFileData.isMasterMetaData()) {
            throw new IllegalArgumentException(String
                    .format("sourceFileData cannot be null if metaFileData %s is not a masterMetaData file", metaFileData.getFileName()));
        }
        this.sourceFileData = sourceFileData;
    }

    public InputStream getSourceInputStream() throws IOException {
        if (sourceFileData == null) {
            return null;
        }
        return sourceFileData.getInputStream();
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
}
