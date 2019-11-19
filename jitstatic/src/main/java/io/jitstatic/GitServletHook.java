package io.jitstatic;

/*-
 * #%L
 * jitstatic
 * %%
 * Copyright (C) 2017 - 2019 H.Hegardt
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

import javax.inject.Inject;
import javax.inject.Singleton;

import org.eclipse.jgit.http.server.GitServlet;
import org.jvnet.hk2.annotations.Service;

import io.dropwizard.setup.Environment;
import io.jitstatic.source.Source;
import zone.dragon.dropwizard.lifecycle.InjectableManaged;
@Service
@Singleton
public class GitServletHook implements InjectableManaged {

    private final Source source;
    private final Environment env;

    @Inject
    public GitServletHook(final Source source, final Environment env) {
        this.source = source;
        this.env = env;
    }
    
    @Override
    public void start() throws Exception {
        final GitServlet gs = env.getApplicationContext().getBean(GitServlet.class);
        gs.setReceivePackFactory(source.getReceivePackFactory());
        gs.setRepositoryResolver(source.getRepositoryResolver());
        gs.setUploadPackFactory(source.getUploadPackFactory());
    }

    @Override
    public void stop() throws Exception {
        // NOOP
    }

}
