package jitstatic.source;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.io.InputStream;

import org.eclipse.jgit.lib.ObjectId;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.Mockito;

import jitstatic.FileObjectIdStore;
import jitstatic.hosted.InputStreamHolder;

public class SourceInfoTest {

	@Rule
	public ExpectedException ex = ExpectedException.none();

	private static final String SHA_1 = "5f12e3846fef8c259efede1a55e12667effcc461";

	@Test
	public void testSourceInfo() throws IOException {
		InputStream is = Mockito.mock(InputStream.class);
		FileObjectIdStore fois = Mockito.mock(FileObjectIdStore.class);
		InputStreamHolder ish = Mockito.mock(InputStreamHolder.class);
		Mockito.when(ish.inputStream()).thenReturn(is);
		Mockito.when(fois.getObjectId()).thenReturn(ObjectId.fromString(SHA_1));
		Mockito.when(ish.isPresent()).thenReturn(true);
		SourceInfo si = new SourceInfo(fois, ish);
		assertEquals(is, si.getInputStream());
		assertEquals(SHA_1, si.getVersion());
	}

	@Test
	public void testSourceInfoWithNoInputStream() throws IOException {
		ex.expect(RuntimeException.class);
		ex.expectCause(Matchers.isA(IOException.class));
		FileObjectIdStore fois = Mockito.mock(FileObjectIdStore.class);
		InputStreamHolder ish = Mockito.mock(InputStreamHolder.class);
		Mockito.when(ish.exception()).thenReturn(new IOException("Fake IO"));
		Mockito.when(ish.isPresent()).thenReturn(false);
		SourceInfo si = new SourceInfo(fois, ish);
		si.getInputStream();
	}
	
	@Test
	public void testSourceInfoWithFailingReadingInputstream() throws IOException {
		ex.expect(IOException.class);
		ex.expectMessage("Error reading null");
		FileObjectIdStore fois = Mockito.mock(FileObjectIdStore.class);
		InputStreamHolder ish = Mockito.mock(InputStreamHolder.class);
		Mockito.when(ish.inputStream()).thenThrow(new IOException("Fake IO"));
		Mockito.when(ish.isPresent()).thenReturn(true);
		SourceInfo si = new SourceInfo(fois, ish);
		si.getInputStream();
	}
}
