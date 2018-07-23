package io.jitstatic;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

import org.eclipse.jgit.lib.RefUpdate.Result;
import org.junit.jupiter.api.Test;

import io.jitstatic.UpdateFailedException;
import io.jitstatic.check.MetaDataFileIsMissingSourceFile;
import io.jitstatic.hosted.FailedToLock;

public class ExceptionTest {

    @Test
    public void testCoverageTests() {
        String file = "file";
        String branch = "refs/heads/master";
        MetaDataFileIsMissingSourceFile md = new MetaDataFileIsMissingSourceFile(file);
        assertEquals(file + " is missing matching source file", md.getMessage());
        UpdateFailedException up = new UpdateFailedException(Result.REJECTED, branch);
        assertEquals(String.format("Got error %s when updating %s", Result.REJECTED.name(), branch), up.getLocalizedMessage());
        FailedToLock ftl = new FailedToLock("ref");
        ftl.fillInStackTrace();
    }

}
