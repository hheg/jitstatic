package jitstatic;

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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;

import jitstatic.utils.Pair;

class BranchData {

	private final Map<String, MetaFileData> metaFiles;
	private final Map<String, SourceFileData> sourceFiles;
	private final RepositoryDataError error;

	public BranchData(final Map<String, MetaFileData> metaFiles, final Map<String, SourceFileData> sourceFiles,
			final RepositoryDataError error) {
		this(error, Objects.requireNonNull(metaFiles), Objects.requireNonNull(sourceFiles));
	}

	public BranchData(final RepositoryDataError fileDataError) {
		this(fileDataError, null, null);
	}

	private BranchData(final RepositoryDataError error, final Map<String, MetaFileData> metaFiles,
			final Map<String, SourceFileData> sourceFiles) {
		this.metaFiles = metaFiles;
		this.sourceFiles = sourceFiles;
		this.error = error;
	}

	public RepositoryDataError getFileDataError() {
		return error;
	}
	
	public List<Pair<MetaFileData, SourceFileData>> pair() {
		Objects.requireNonNull(sourceFiles);
		Objects.requireNonNull(metaFiles);
		final List<Pair<MetaFileData, SourceFileData>> filePairs = new ArrayList<>(sourceFiles.size());
		final Set<String> keys = new HashSet<>();
		final Iterator<Entry<String, MetaFileData>> metaDataIterator = metaFiles.entrySet().iterator();
		while (metaDataIterator.hasNext()) {
			final Entry<String, MetaFileData> metaData = metaDataIterator.next();
			final String key = metaData.getKey();
			final MetaFileData value = metaData.getValue();
			filePairs.add(Pair.of(value, sourceFiles.get(key)));
			keys.add(key);
		}
		final Iterator<Entry<String, SourceFileData>> sourceDataIterator = sourceFiles.entrySet().iterator();
		while (sourceDataIterator.hasNext()) {
			final Entry<String, SourceFileData> sourceDataEntries = sourceDataIterator.next();
			if(!keys.contains(sourceDataEntries.getKey())) {
				filePairs.add(Pair.of(null, sourceDataEntries.getValue()));
			}			
		}
		return filePairs;
	}

	public Pair<MetaFileData, SourceFileData> getFirstPair() {
		final List<Pair<MetaFileData, SourceFileData>> data = pair();
		if(data.size() == 1) {
			return data.get(0);
		}
		return Pair.ofNothing();
	}

}
