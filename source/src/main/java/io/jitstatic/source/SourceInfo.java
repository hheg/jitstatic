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
import java.util.Objects;

import io.jitstatic.MetaFileData;
import io.jitstatic.RepositoryDataError;
import io.jitstatic.SourceFileData;

public class SourceInfo {

    private final MetaFileData metaFileData;
    private final SourceFileData sourceFileData;
    private final RepositoryDataError fileDataError;

    public SourceInfo(final MetaFileData metaFileData, final SourceFileData sourceFileData, final RepositoryDataError fileDataError) {
        this.metaFileData = Objects.requireNonNull(metaFileData);
        this.sourceFileData = Objects.requireNonNull(sourceFileData);
        this.fileDataError = fileDataError;
    }

    public boolean hasFailed() {
        if (fileDataError == null) {
            return false;
        }
        return !fileDataError.getInputStreamHolder().isPresent();
    }

    public Exception getFailiure() {
        if (fileDataError != null) {
            return fileDataError.getInputStreamHolder().exception();
        }
        return null;
    }

    public InputStream getSourceInputStream() throws IOException {
        return sourceFileData.getInputStream();
    }

    public String getSourceVersion() {
        return sourceFileData.getVersion();
    }

    public String getMetaDataVersion() {
        return metaFileData.getVersion();
    }

    public InputStream getMetadataInputStream() throws IOException {
        return metaFileData.getInputStream();
    }

}
