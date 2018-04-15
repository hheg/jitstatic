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

import org.eclipse.jgit.transport.PreReceiveHook;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceivePack;

public class LogoPoster implements PreReceiveHook {
	
	private static final String line1 =	"   __  _ _   __ _        _   _      "; 
	private static final String line2 =	"   \\ \\(_) |_/ _\\ |_ __ _| |_(_) ___ "; 
	private static final String line3 =	"    \\ \\ | __\\ \\| __/ _` | __| |/ __|"; 
	private static final String line4 =	" /\\_/ / | |__\\ \\ || (_| | |_| | (__ "; 
	private static final String line5 =	" \\___/|_|\\__\\__/\\__\\__,_|\\__|_|\\___|"; 
	private static final String line6 = "                                    ";

	@Override
	public void onPreReceive(final ReceivePack rp, final Collection<ReceiveCommand> commands) {
		rp.sendMessage(line1);
		rp.sendMessage(line2);
		rp.sendMessage(line3);
		rp.sendMessage(line4);
		rp.sendMessage(line5);
		rp.sendMessage(line6);
	}

}
