package io.jitstatic.hosted;

import java.util.List;

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

import javax.servlet.http.HttpServletRequest;

import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.PreUploadHookChain;
import org.eclipse.jgit.transport.UploadPack;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;
import org.eclipse.jgit.transport.resolver.ServiceNotEnabledException;
import org.eclipse.jgit.transport.resolver.UploadPackFactory;

public class JitStaticUploadPackFactory implements UploadPackFactory<HttpServletRequest> {

    private final ErrorReporter errorReporter;

    public JitStaticUploadPackFactory(final ErrorReporter errorReporter) {
        this.errorReporter = errorReporter;
    }

    static class ServiceConfig {
        final boolean enabled;

        ServiceConfig(final Config cfg) {
            enabled = cfg.getBoolean("http", "uploadpack", true);
        }
    }

    /** {@inheritDoc} */
    @Override
    public UploadPack create(final HttpServletRequest req, final Repository db)
            throws ServiceNotEnabledException, ServiceNotAuthorizedException {
        if (db.getConfig().get(ServiceConfig::new).enabled) {
            final JitStaticUploadPack jitStaticUploadPack = new JitStaticUploadPack(db, errorReporter);
            jitStaticUploadPack.setPreUploadHook(PreUploadHookChain.newChain(List.of(new LogoPoster())));
            return jitStaticUploadPack;
        } else
            throw new ServiceNotEnabledException();
    }

}
