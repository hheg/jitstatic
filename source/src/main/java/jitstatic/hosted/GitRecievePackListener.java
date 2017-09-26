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


import java.util.ArrayList;
import java.util.List;

import javax.servlet.ServletRequestEvent;
import javax.servlet.ServletRequestListener;
import javax.servlet.http.HttpServletRequest;

import jitstatic.source.SourceEventListener;

public class GitRecievePackListener implements ServletRequestListener {

	private final List<SourceEventListener> listeners;

	public GitRecievePackListener() {
		listeners = new ArrayList<>();
	}

	@Override
	public void requestDestroyed(final ServletRequestEvent sre) {
		final HttpServletRequest req = (HttpServletRequest) sre.getServletRequest();
		if ("POST".equals(req.getMethod())) {
			String[] split = req.getRequestURL().toString().split("/");
			if ("git-receive-pack".equals(split[split.length - 1])) {
				listeners.forEach(SourceEventListener::onEvent);
			}
		}
	}

	@Override
	public void requestInitialized(final ServletRequestEvent sre) {
		// noop
	}

	public void addListener(final SourceEventListener listener) {
		this.listeners.add(listener);
	}
}
