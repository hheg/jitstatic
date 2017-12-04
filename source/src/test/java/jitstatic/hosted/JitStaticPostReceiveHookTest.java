package jitstatic.hosted;

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
