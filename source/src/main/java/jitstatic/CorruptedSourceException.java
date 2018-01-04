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

import jitstatic.util.Pair;

public class CorruptedSourceException extends Exception {

	private static final long serialVersionUID = 5606961605803953513L;

	public CorruptedSourceException(final List<Pair<Set<Ref>, List<Pair<FileObjectIdStore, Exception>>>> errors) {
		super(compileMessage(errors));
	}

	public static String compileMessage(final List<Pair<Set<Ref>, List<Pair<FileObjectIdStore, Exception>>>> allBranchErrors) {
		final StringBuilder sb = new StringBuilder();
		for (final Pair<Set<Ref>, List<Pair<FileObjectIdStore, Exception>>> branchErrors : allBranchErrors) {
			sb.append("Error in branch");
			final Set<Ref> branchFileErrors = branchErrors.getLeft();
			if (branchFileErrors.size() > 1) {
				sb.append("es");
			}
			sb.append(" ");
			sb.append(branchFileErrors.stream().map(r -> r.getName()).collect(Collectors.joining(", ")))
					.append(System.lineSeparator());
			for (final Pair<FileObjectIdStore, Exception> fileError : branchErrors.getRight()) {
				final FileObjectIdStore fileInfo = fileError.getLeft();
				sb.append("ID: ").append(fileInfo == null ? "null" : ObjectId.toString(fileInfo.getObjectId())).append(" Name: ")
						.append((fileInfo == null ? "FILE_NAME_MISSING" : fileInfo.getFileName())).append(" ")
						.append(" Reason: ")
						.append((fileError.getRight() == null ? "null" : fileError.getRight().getMessage())).append(System.lineSeparator());
			}
		}
		return sb.toString();
	}

	@Override
	public Throwable fillInStackTrace() {
		return this;
	}
}
