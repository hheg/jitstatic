package io.jitstatic.check;

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
import java.util.ArrayList;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;

import io.jitstatic.StorageParseException;
import io.jitstatic.utils.Pair;

public class CorruptedSourceException extends Exception {

    private static final long serialVersionUID = 5606961605803953513L;
    private final List<String> messages;

    public CorruptedSourceException(final List<String> messages) {
        this.messages = messages;
    }

    @Override
    public Throwable fillInStackTrace() {
        return this;
    }

    public static Pair<List<String>, List<String>> interpreteMessages(final List<Pair<Set<Ref>, List<Pair<FileObjectIdStore, Exception>>>> allBranchErrors) {
        return interpreteMessages(allBranchErrors, null, null);
    }

    public static Pair<List<String>, List<String>> interpreteMessages(final List<Pair<Set<Ref>, List<Pair<FileObjectIdStore, Exception>>>> allBranchErrors,
            final String testBranchName, final String branchName) {
        final List<String> warnings = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        for (final Pair<Set<Ref>, List<Pair<FileObjectIdStore, Exception>>> branchErrors : allBranchErrors) {
            final StringBuilder sb = new StringBuilder(" in branch");
            final Set<Ref> branchFileErrors = branchErrors.getLeft();
            if (branchFileErrors.size() > 1) {
                sb.append("es");
            }
            sb.append(" ");
            sb.append(branchFileErrors.stream()
                    .map(Ref::getName)
                    .map(n -> n.equals(testBranchName) ? branchName : n)
                    .collect(Collectors.joining(", ")));
            final String rf = sb.toString();

            for (Pair<FileObjectIdStore, Exception> fileError : branchErrors.getRight()) {
                final FileObjectIdStore fileInfo = fileError.getLeft();
                final Exception error = fileError.getRight();
                if (error instanceof StorageParseException) {
                    final StorageParseException spe = (StorageParseException) error;
                    for (String msg : spe.getErrors()) {
                        errors.add(buildMessage("Error", rf, fileError, fileInfo, msg));
                    }

                    for (String msg : spe.getWarnings()) {
                        warnings.add(buildMessage("Warning", rf, fileError, fileInfo, msg));
                    }
                } else {
                    errors.add(buildMessage("Error", rf, fileError, fileInfo, error.getLocalizedMessage()));
                }
            }
        }
        return Pair.of(errors, warnings);
    }

    private static String buildMessage(String type, String ref, Pair<FileObjectIdStore, Exception> fileError, final FileObjectIdStore fileInfo, String msg) {
        return new StringBuilder(type)
                .append(ref)
                .append(" ID: ")
                .append(fileInfo == null ? "null" : ObjectId.toString(fileInfo.getObjectId()))
                .append(" Name: ")
                .append((fileInfo == null ? "FILE_NAME_MISSING" : fileInfo.getFileName()))
                .append(" ")
                .append(" Reason: ")
                .append((fileError.getRight() == null ? "null" : msg)).toString();
    }

    @Override
    public String getMessage() {
        return messages.stream().collect(Collectors.joining(", "));
    }

}
