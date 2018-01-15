package jitstatic.source;

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

import jitstatic.FileObjectIdStore;
import jitstatic.hosted.InputStreamHolder;

public class SourceInfo {

	private final FileObjectIdStore fileInfo;
	private final InputStreamHolder inputStreamHolder;

	public SourceInfo(final FileObjectIdStore fileInfo, final InputStreamHolder inputStreamHolder) {
		this.fileInfo = Objects.requireNonNull(fileInfo);
		this.inputStreamHolder = Objects.requireNonNull(inputStreamHolder);
	}

	public InputStream getInputStream() throws IOException {
		if (inputStreamHolder.isPresent()) {
			try {
				return inputStreamHolder.inputStream();
			} catch (final IOException e) {
				throw new IOException("Error reading " + fileInfo.getFileName(), e);
			}
		} else {
			throw new RuntimeException(inputStreamHolder.exception());
		}
	}

	public String getVersion() {
		return fileInfo.getObjectId().name();
	}

}
