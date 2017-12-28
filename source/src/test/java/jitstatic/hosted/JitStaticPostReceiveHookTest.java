package jitstatic.hosted;

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

import static org.junit.Assert.assertNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceivePack;
import org.junit.Test;
import org.mockito.Mockito;

import jitstatic.source.SourceEventListener;

public class JitStaticPostReceiveHookTest {

	private static final String REFS_HEADS_MASTER = "refs/heads/master";

	@Test
	public void testPostReceiveHookTriggerListerners() {
		JitStaticPostReceiveHook hook = new JitStaticPostReceiveHook();
		SourceEventListener sel = Mockito.mock(SourceEventListener.class);
		ReceivePack rp = Mockito.mock(ReceivePack.class);
		
		hook.addListener(sel);
		List<ReceiveCommand> commands = Arrays.asList(new ReceiveCommand(ObjectId.zeroId(), ObjectId.zeroId(), REFS_HEADS_MASTER));
		Mockito.when(rp.getAllCommands()).thenReturn(commands);
		hook.onPostReceive(rp,commands);
		Mockito.verify(sel).onEvent(Arrays.asList(REFS_HEADS_MASTER));
		assertNull(hook.getFault());
	}
	
	@Test
	public void testFailedReceiveHook() {
		JitStaticPostReceiveHook hook = new JitStaticPostReceiveHook();
		SourceEventListener sel = Mockito.mock(SourceEventListener.class);
		ReceivePack rp = Mockito.mock(ReceivePack.class);
		
		hook.addListener(sel);
		List<ReceiveCommand> commands = Arrays.asList(new ReceiveCommand(ObjectId.zeroId(), ObjectId.zeroId(), REFS_HEADS_MASTER));
		Mockito.when(rp.getAllCommands()).thenReturn(Collections.emptyList());
		hook.onPostReceive(rp,commands);
		Mockito.verify(sel,Mockito.times(0)).onEvent(Arrays.asList(REFS_HEADS_MASTER));
		assertNull(hook.getFault());
	}
	
}
