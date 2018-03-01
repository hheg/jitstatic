package jitstatic;

import static org.junit.Assert.assertEquals;

import org.eclipse.jgit.lib.RefUpdate.Result;
import org.junit.Test;

public class ExceptionTest {

	@Test
	public void testCoverageTests() {
		String file = "file";
		MetaDataFileIsMissingSourceFile md = new MetaDataFileIsMissingSourceFile(file);
		assertEquals(file + " is missing matching source file", md.getMessage());
		UpdateFailedException up = new UpdateFailedException(Result.REJECTED);
		assertEquals(Result.REJECTED.name(), up.getMessage());
	}

}
