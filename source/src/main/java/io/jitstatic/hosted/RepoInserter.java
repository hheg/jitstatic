package io.jitstatic.hosted;

/*-
 * #%L
 * jitstatic
 * %%
 * Copyright (C) 2017 - 2019 H.Hegardt
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
import java.io.OutputStream;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.zip.Deflater;

import org.eclipse.jgit.internal.storage.pack.PackWriter;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.RefUpdate.Result;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.DepthWalk;
import org.eclipse.jgit.revwalk.ObjectWalk;
import org.eclipse.jgit.storage.pack.PackConfig;
import org.eclipse.jgit.storage.pack.PackStatistics.Accumulator;
import org.eclipse.jgit.transport.PackParser;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

// TODO Remove this SpotBugs error
@SuppressFBWarnings(value = "RCN_REDUNDANT_NULLCHECK_WOULD_HAVE_BEEN_A_NPE", justification = "This is a false positive in Java 11, should be removed")
public class RepoInserter {

    private final Repository repository;
    private final PackConfig pc;

    public RepoInserter(final Repository repository) {
        this.repository = repository;
        pc = new PackConfig(repository);
        ForkJoinPool commonPool = ForkJoinPool.commonPool();
        pc.setExecutor(commonPool);
        pc.setThreads(commonPool.getParallelism());
        pc.setCompressionLevel(Deflater.BEST_SPEED);
    }

    public PackParser parse(final InputStream is) throws IOException {
        try (ObjectInserter inserter = repository.getObjectDatabase().newInserter();) {
            PackParser packParser = inserter.newPackParser(is);
            packParser.setNeedNewObjectIds(true);
            packParser.setNeedBaseObjectIds(true);
            packParser.parse(NullProgressMonitor.INSTANCE);
            inserter.flush(); // This is a NOOP but declared for consistency
            return packParser;
        }
    }

    public CheckedConsumer<OutputStream,IOException> packData(final Set<ObjectId> tags, final Set<ObjectId> tips, final Set<ObjectId> uninterestingObjects)
            throws IOException {
        return os -> {
            final Accumulator accumulator = new Accumulator(); // TODO Use the statistics
            try (ObjectWalk walk = new DepthWalk.ObjectWalk(repository, Integer.MAX_VALUE);
                    PackWriter pw = new PackWriter(getPackConfig(), repository.newObjectReader(), accumulator);) {
                pw.setTagTargets(tags);
                pw.setUseBitmaps(true);
                pw.setUseCachedPacks(true);
                pw.setIndexDisabled(true);
                NullProgressMonitor npm = NullProgressMonitor.INSTANCE;
                pw.preparePack(npm, walk, tips, uninterestingObjects, Set.of());
                pw.writePack(npm, npm, os);
            }
        };
    }

    public Result moveRef(final ObjectId last, final ObjectId tip, final String ref) throws IOException {
        final RefUpdate newUpdate = repository.getRefDatabase().newUpdate(ref, false);
        newUpdate.setExpectedOldObjectId(last);
        newUpdate.setNewObjectId(tip);
        newUpdate.setForceUpdate(true);
        return newUpdate.update();
    }

    private PackConfig getPackConfig() {
        return pc;
    }

}
