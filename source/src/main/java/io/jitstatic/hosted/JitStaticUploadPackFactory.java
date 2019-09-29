package io.jitstatic.hosted;

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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;

import javax.servlet.http.HttpServletRequest;

import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.pack.PackConfig;
import org.eclipse.jgit.transport.PreUploadHookChain;
import org.eclipse.jgit.transport.UploadPack;
import org.eclipse.jgit.transport.WantNotValidException;
import org.eclipse.jgit.transport.UploadPack.RequestPolicy;
import org.eclipse.jgit.transport.UploadPackInternalServerErrorException;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;
import org.eclipse.jgit.transport.resolver.ServiceNotEnabledException;
import org.eclipse.jgit.transport.resolver.UploadPackFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.jitstatic.utils.ShouldNeverHappenException;

public class JitStaticUploadPackFactory implements UploadPackFactory<HttpServletRequest> {

    private static final Logger LOG = LoggerFactory.getLogger(UploadPack.class);
    private final ExecutorService executorService;
    private final RefLockHolderManager refLockHolderManager;
    private final String defaultRef;

    public JitStaticUploadPackFactory(final ExecutorService executorService, final RefLockHolderManager refLockHolderManager, final String defaultRef) {
        this.executorService = Objects.requireNonNull(executorService);
        this.refLockHolderManager = Objects.requireNonNull(refLockHolderManager);
        this.defaultRef = Objects.requireNonNull(defaultRef);
    }

    static class ServiceConfig {
        final boolean enabled;

        ServiceConfig(final Config cfg) {
            enabled = cfg.getBoolean("http", "uploadpack", true);
        }
    }

    /** {@inheritDoc} */
    @Override
    public UploadPack create(final HttpServletRequest req, final Repository db) throws ServiceNotEnabledException, ServiceNotAuthorizedException {
        if (db.getConfig().get(ServiceConfig::new).enabled) {
            final PackConfig cfg = new PackConfig(db.getConfig());
            cfg.setExecutor(executorService);
            final UploadPack jitStaticUploadPack = new UploadPack(db) {
                @Override
                public void upload(final InputStream input, final OutputStream output, final OutputStream messages) throws IOException {
                    try {
                        refLockHolderManager.getRefHolder(defaultRef).enqueueAndReadBlock(() -> {
                            try {
                                super.upload(input, output, messages);
                            } catch (IOException e) {
                                throw new UnwrapException(e);
                            }
                            return "upload-pack OK";
                        }).join();
                    } catch (CompletionException ce) {
                        final Throwable cause = ce.getCause();
                        if (cause instanceof UnwrapException) {
                            final Throwable secondCause = cause.getCause();
                            if (secondCause instanceof UploadPackInternalServerErrorException) {
                                final Throwable wnve = secondCause.getCause();
                                if (wnve instanceof WantNotValidException) {
                                    LOG.info("{}, aborting...", wnve.getMessage());
                                } else {
                                    throw (IOException) secondCause;
                                }
                            } else {
                                throw (IOException) secondCause;
                            }
                        } else {
                            throw new ShouldNeverHappenException("Unknown exception happen while reading the repository", cause);
                        }
                    }
                }
            };
            jitStaticUploadPack.setRequestPolicy(RequestPolicy.ADVERTISED);
            jitStaticUploadPack.setPackConfig(cfg);
            jitStaticUploadPack.setPreUploadHook(PreUploadHookChain.newChain(List.of(new LogoPoster())));
            jitStaticUploadPack.setRefFilter(new JitStaticRefFilter(req));
            return jitStaticUploadPack;
        } else
            throw new ServiceNotEnabledException();
    }

    private static class UnwrapException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public UnwrapException(IOException e) {
            super(e);
        }
    }

}
