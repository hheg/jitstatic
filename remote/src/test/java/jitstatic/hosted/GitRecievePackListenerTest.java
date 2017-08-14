package jitstatic.hosted;

/*-
 * #%L
 * jitstatic
 * %%
 * Copyright (C) 2017 HHegardt
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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import javax.servlet.ServletRequestEvent;
import javax.servlet.http.HttpServletRequest;

import org.junit.Test;

import jitstatic.source.SourceEventListener;

public class GitRecievePackListenerTest {

	private SourceEventListener sourceEventListener = mock(SourceEventListener.class);
	private ServletRequestEvent servletRequstEvent = mock(ServletRequestEvent.class);
	private HttpServletRequest httpServletRequest = mock(HttpServletRequest.class);
	
	@Test
	public void testShouldSignalOnGitRecievePack() {
		String url = "http://localhost:8080/application/git/git-receive-pack";
		when(servletRequstEvent.getServletRequest()).thenReturn(httpServletRequest);
		when(httpServletRequest.getMethod()).thenReturn("POST");
		when(httpServletRequest.getRequestURL()).thenReturn(new StringBuffer(url));
		
		GitRecievePackListener grpl = new GitRecievePackListener();
		grpl.addListener(sourceEventListener);
		grpl.requestDestroyed(servletRequstEvent);
		verify(sourceEventListener).onEvent();		
	}
	
	@Test
	public void testShouldNotSignalOnAnyOther() {
		String url = "http://localhost:8080/application/git/git-upload-pack";
		when(servletRequstEvent.getServletRequest()).thenReturn(httpServletRequest);
		when(httpServletRequest.getMethod()).thenReturn("POST");
		when(httpServletRequest.getRequestURL()).thenReturn(new StringBuffer(url));
		
		GitRecievePackListener grpl = new GitRecievePackListener();
		grpl.addListener(sourceEventListener);
		grpl.requestDestroyed(servletRequstEvent);
		verify(sourceEventListener,times(0)).onEvent();	
	}

}
