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
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.concurrent.Executor;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.UploadPack;
import org.eclipse.jgit.transport.UploadPackInternalServerErrorException;
import org.eclipse.jgit.transport.WantNotValidException;

public class JitStaticUploadPack extends UploadPack {
    private static final Logger LOG = LogManager.getLogger(JitStaticUploadPack.class);

    private final SubmittingExecutor service;
    private final ErrorReporter errorReporter;

    public JitStaticUploadPack(final Repository copyFrom, final Executor service, final ErrorReporter errorReporter) {
        super(copyFrom);
        this.service = new SubmittingExecutor(Objects.requireNonNull(service));
        this.errorReporter = Objects.requireNonNull(errorReporter);
    }

    @Override
    public void upload(final InputStream input, final OutputStream output, final OutputStream messages) throws IOException {
        unwrap(service.submit((Runnable & ReadOperation)() -> {
            try {
                super.upload(input, output, messages);
            } catch (final IOException e) {               
                if (!("org.eclipse.jetty.io.EofException".equals(e.getClass().getCanonicalName()) || (e instanceof WantNotValidException)
                        || (e instanceof UploadPackInternalServerErrorException))) {
                    errorReporter.setFault(e);
                    LOG.error("Upload resulted in error ", e);
                }
                throw new UncheckedIOException(e);
            } catch (final Exception e) {
                errorReporter.setFault(e);
                LOG.error("Upload resulted in error ", e);
            }
        }));
    }

    private void unwrap(final SubmittedSupplier<Void> f) throws IOException {
        try {
            f.get();
        } catch (final RuntimeException re) {
            final Throwable cause = re.getCause();
            if (cause instanceof UncheckedIOException) {
                throw (IOException) cause.getCause();
            }
            throw re;
        }
    }
}
