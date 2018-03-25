package io.jitstatic.source;

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

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.io.InputStream;

import org.eclipse.jgit.lib.ObjectId;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.Mockito;

import io.jitstatic.FileObjectIdStore;
import io.jitstatic.MetaFileData;
import io.jitstatic.SourceFileData;
import io.jitstatic.hosted.InputStreamHolder;
import io.jitstatic.source.SourceInfo;

public class SourceInfoTest {

	@Rule
	public ExpectedException ex = ExpectedException.none();

	private static final String SHA_1 = "5f12e3846fef8c259efede1a55e12667effcc461";

	@Test
	public void testSourceInfo() throws IOException {
		InputStream is = Mockito.mock(InputStream.class);
		
		FileObjectIdStore fois = Mockito.mock(FileObjectIdStore.class);
		InputStreamHolder ish = Mockito.mock(InputStreamHolder.class);
		FileObjectIdStore fois2 = Mockito.mock(FileObjectIdStore.class);
		InputStreamHolder ish2 = Mockito.mock(InputStreamHolder.class);
		
		Mockito.when(ish.inputStream()).thenReturn(is);
		Mockito.when(fois.getObjectId()).thenReturn(ObjectId.fromString(SHA_1));
		Mockito.when(ish.isPresent()).thenReturn(true);
		SourceFileData sdf = new SourceFileData(fois, ish);
		MetaFileData mfd = new MetaFileData(fois2, ish2);
		SourceInfo si = new SourceInfo(mfd,sdf,null);
		assertEquals(is, si.getSourceInputStream());
		assertEquals(SHA_1, si.getSourceVersion());
	}

	@Test
	public void testSourceInfoWithNoInputStream() throws IOException {
		ex.expect(RuntimeException.class);
		ex.expectCause(Matchers.isA(IOException.class));
		FileObjectIdStore fois = Mockito.mock(FileObjectIdStore.class);
		InputStreamHolder ish = Mockito.mock(InputStreamHolder.class);
		FileObjectIdStore fois2 = Mockito.mock(FileObjectIdStore.class);
		InputStreamHolder ish2 = Mockito.mock(InputStreamHolder.class);
		Mockito.when(ish.exception()).thenReturn(new IOException("Fake IO"));
		Mockito.when(ish.isPresent()).thenReturn(false);
		SourceFileData sdf = new SourceFileData(fois, ish);
		MetaFileData mfd = new MetaFileData(fois2, ish2);
		SourceInfo si = new SourceInfo(mfd,sdf,null);		
		si.getSourceInputStream();
	}
	
	@Test
	public void testSourceInfoWithFailingReadingInputstream() throws IOException {
		ex.expect(IOException.class);
		ex.expectMessage("Error reading null");
		FileObjectIdStore fois = Mockito.mock(FileObjectIdStore.class);
		InputStreamHolder ish = Mockito.mock(InputStreamHolder.class);
		FileObjectIdStore fois2 = Mockito.mock(FileObjectIdStore.class);
		InputStreamHolder ish2 = Mockito.mock(InputStreamHolder.class);

		Mockito.when(ish.inputStream()).thenThrow(new IOException("Fake IO"));
		Mockito.when(ish.isPresent()).thenReturn(true);
		SourceFileData sdf = new SourceFileData(fois, ish);
		MetaFileData mfd = new MetaFileData(fois2, ish2);
		SourceInfo si = new SourceInfo(mfd,sdf,null);		
		si.getSourceInputStream();
	}
}
