package jitstatic;

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



import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.Test;

public class AutoCloseableLifeCycleManagerTest {

	AutoCloseable ac = mock(AutoCloseable.class);

	@Test
	public void testAutoCloseableLifeCycleManager() throws Exception {
		AutoCloseableLifeCycleManager<AutoCloseable> unit = new AutoCloseableLifeCycleManager<AutoCloseable>(ac);
		unit.stop();
		verify(ac).close();
	}

}
