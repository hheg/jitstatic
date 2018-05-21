package io.jitstatic.hosted;

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

import java.util.Collection;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.PreReceiveHook;
import org.eclipse.jgit.transport.PreUploadHook;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.ServiceMayNotContinueException;
import org.eclipse.jgit.transport.UploadPack;

import io.jitstatic.version.ProjectVersion;

public class LogoPoster implements PreReceiveHook, PreUploadHook {

    private static final String line1 = "   __  _ _   __ _        _   _      ";
    private static final String line2 = "   \\ \\(_) |_/ _\\ |_ __ _| |_(_) ___ ";
    private static final String line3 = "    \\ \\ | __\\ \\| __/ _` | __| |/ __|";
    private static final String line4 = " /\\_/ / | |__\\ \\ || (_| | |_| | (__ ";
    private static final String line5 = " \\___/|_|\\__\\__/\\__\\__,_|\\__|_|\\___|";
    private static final String line6 = "                                    " + ProjectVersion.INSTANCE.getBuildVersion();

    @Override
    public void onPreReceive(final ReceivePack rp, final Collection<ReceiveCommand> commands) {
        rp.sendMessage(line1);
        rp.sendMessage(line2);
        rp.sendMessage(line3);
        rp.sendMessage(line4);
        rp.sendMessage(line5);
        rp.sendMessage(line6);
    }

    @Override
    public void onBeginNegotiateRound(final UploadPack up, final Collection<? extends ObjectId> wants, final int cntOffered)
            throws ServiceMayNotContinueException {
        // NOOP
    }

    @Override
    public void onEndNegotiateRound(final UploadPack up, Collection<? extends ObjectId> wants, final int cntCommon, final int cntNotFound,final  boolean ready)
            throws ServiceMayNotContinueException {
        // NOOP
    }

    @Override
    public void onSendPack(final UploadPack up, final Collection<? extends ObjectId> wants,final  Collection<? extends ObjectId> haves)
            throws ServiceMayNotContinueException {
        // NOOP
        up.sendMessage(line1);
        up.sendMessage(line2);
        up.sendMessage(line3);
        up.sendMessage(line4);
        up.sendMessage(line5);
        up.sendMessage(line6);
    }

}
