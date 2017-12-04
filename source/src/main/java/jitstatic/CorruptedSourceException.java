package jitstatic;

/*-
 * #%L
 * jitstatic
 * %%
 * Copyright (C) 2017 H.Hegardt
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

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;

import jitstatic.hosted.FileObjectIdStore;
import jitstatic.util.Pair;

public class CorruptedSourceException extends Exception {

	private static final long serialVersionUID = 5606961605803953513L;

	public CorruptedSourceException(final List<Pair<Set<Ref>, List<Pair<FileObjectIdStore, Exception>>>> errors) {
		super(compileMessage(errors));
	}

	public static String compileMessage(final List<Pair<Set<Ref>, List<Pair<FileObjectIdStore, Exception>>>> errors) {
		final StringBuilder sb = new StringBuilder();
		for (final Pair<Set<Ref>, List<Pair<FileObjectIdStore, Exception>>> p : errors) {
			sb.append("Error in branch");
			if (p.getLeft().size() > 1) {
				sb.append("es");
			}
			sb.append(" ");
			sb.append(p.getLeft().stream().map(r -> r.getName()).collect(Collectors.joining(", ")))
					.append(System.lineSeparator());
			for (final Pair<FileObjectIdStore, Exception> pe : p.getRight()) {
				final FileObjectIdStore fileInfo = pe.getLeft();
				sb.append("ID: ").append(fileInfo == null ? "null" : ObjectId.toString(fileInfo.getObjectId())).append(" Name: ")
						.append((fileInfo == null ? "FILE_NAME_MISSING" : fileInfo.getFileName())).append(" ")
						.append(" Reason: ")
						.append((pe.getRight() == null ? "null" : pe.getRight().getMessage())).append(System.lineSeparator());
			}
		}
		return sb.toString();
	}

	@Override
	public Throwable fillInStackTrace() {
		return this;
	}
}
