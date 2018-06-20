package io.jitstatic.check;

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

import io.jitstatic.JitStaticConstants;
import io.jitstatic.hosted.InputStreamHolder;
import io.jitstatic.utils.Path;

public class FileData {

    private final FileObjectIdStore fileInfo;
    private final InputStreamHolder inputStreamHolder;

    public FileData(final FileObjectIdStore fileInfo, final InputStreamHolder inputStreamHolder) {
        this.fileInfo = Objects.requireNonNull(fileInfo);
        this.inputStreamHolder = Objects.requireNonNull(inputStreamHolder);
    }

    public InputStream getInputStream() throws IOException {
        if (getInputStreamHolder().isPresent()) {
            try {
                return getInputStreamHolder().inputStream();
            } catch (final IOException e) {
                throw new IOException("Error reading " + getFileInfo().getFileName(), e);
            }
        } else {
            throw new RuntimeException(getInputStreamHolder().exception());
        }
    }

    public String getVersion() {
        return getFileInfo().getObjectId().name();
    }

    public InputStreamHolder getInputStreamHolder() {
        return inputStreamHolder;
    }

    protected FileObjectIdStore getFileInfo() {
        return fileInfo;
    }
    
    public String getFileName() {
        return fileInfo.getFileName();
    }

    public boolean isMasterMetaData() {
        final Path meta = Path.of(fileInfo.getFileName());
        return meta.getLastElement().equals(JitStaticConstants.METADATA);
    }

    @Override
    public String toString() {
        return "FileData [fileInfo=" + fileInfo + "]";
    }

}
