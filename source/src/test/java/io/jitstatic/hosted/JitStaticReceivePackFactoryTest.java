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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;
import org.eclipse.jgit.transport.resolver.ServiceNotEnabledException;
import org.hamcrest.CoreMatchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.jitstatic.test.TemporaryFolder;
import io.jitstatic.test.TemporaryFolderExtension;

@ExtendWith(TemporaryFolderExtension.class)
public class JitStaticReceivePackFactoryTest {

    private Git git;
    private TemporaryFolder tmpFolder;
    private final ErrorReporter reporter = new ErrorReporter();
    private static final String defaultRef = "refs/heads/master";
    private HttpServletRequest req;

    @BeforeEach
    public void setup() throws IllegalStateException, GitAPIException, IOException {
        git = Git.init().setDirectory(getFolder().toFile()).call();
        req = mock(HttpServletRequest.class);
    }

    @AfterEach
    public void tearDown() {
        git.close();
    }

    @Test
    public void testJitStaticReceivePackFactory() throws ServiceNotEnabledException, ServiceNotAuthorizedException {
        String user = "user", host = "remotehost";
        JitStaticReceivePackFactory jsrpf = new JitStaticReceivePackFactory(reporter, defaultRef, new RefLockHolderManager(),
                new UserExtractor(git.getRepository()));
        when(req.getRemoteUser()).thenReturn(user);
        when(req.getRemoteHost()).thenReturn(host);
        ReceivePack create = jsrpf.create(req, git.getRepository());
        assertNotNull(create);
        PersonIdent refLogIdent = create.getRefLogIdent();
        assertNotNull(refLogIdent);
        assertEquals(user, refLogIdent.getName());
        assertEquals(user + "@" + host, refLogIdent.getEmailAddress());
        assertTrue(create.isAtomic());
    }

    @Test
    public void testJitStaticReceivePackFactoryNoUser() throws ServiceNotEnabledException, ServiceNotAuthorizedException {
        String host = "remotehost";
        assertThat(assertThrows(ServiceNotAuthorizedException.class, () -> {
            JitStaticReceivePackFactory jsrpf = new JitStaticReceivePackFactory(reporter, defaultRef, new RefLockHolderManager(),
                    new UserExtractor(git.getRepository()));
            when(req.getRemoteHost()).thenReturn(host);
            jsrpf.create(req, git.getRepository());
        }).getLocalizedMessage(), CoreMatchers.containsString("Unauthorized"));
    }

    @Test
    public void testJitStaticReceivePackFactoryReceivePackTurnedOff() throws ServiceNotEnabledException, ServiceNotAuthorizedException {
        String user = "user", host = "remotehost";
        assertThat(assertThrows(ServiceNotEnabledException.class, () -> {
            JitStaticReceivePackFactory jsrpf = new JitStaticReceivePackFactory(reporter, defaultRef, new RefLockHolderManager(),
                    new UserExtractor(git.getRepository()));
            git.getRepository().getConfig().setString("http", null, "receivepack", "false");
            when(req.getRemoteUser()).thenReturn(user);
            when(req.getRemoteHost()).thenReturn(host);
            jsrpf.create(req, git.getRepository());
        }).getLocalizedMessage(), CoreMatchers.containsString("Service not enabled"));
    }

    @Test
    public void testJitStaticReceivePackFactoryReceivePackTurnedOn() throws ServiceNotEnabledException, ServiceNotAuthorizedException {
        String user = "user", host = "remotehost";
        JitStaticReceivePackFactory jsrpf = new JitStaticReceivePackFactory(reporter, defaultRef, new RefLockHolderManager(),
                new UserExtractor(git.getRepository()));
        git.getRepository().getConfig().setString("http", null, "receivepack", "true");
        when(req.getRemoteUser()).thenReturn(user);
        when(req.getRemoteHost()).thenReturn(host);
        ReceivePack create = jsrpf.create(req, git.getRepository());
        assertNotNull(create);
        PersonIdent refLogIdent = create.getRefLogIdent();
        assertNotNull(refLogIdent);
        assertEquals(user, refLogIdent.getName());
        assertEquals(user + "@" + host, refLogIdent.getEmailAddress());
        assertTrue(create.isAtomic());
    }

    @Test
    public void testSecretsFilterFunction() throws ServiceNotEnabledException, ServiceNotAuthorizedException {
        String user = "user";
        Ref secrets = mock(Ref.class);
        Ref head = mock(Ref.class);
        ObjectId oid = ObjectId.fromString("5f12e3846fef8c259efede1a55e12667effcc461");
        when(secrets.getObjectId()).thenReturn(oid);
        when(head.getObjectId()).thenReturn(oid);
        when(req.getRemoteUser()).thenReturn(user);

        JitStaticReceivePackFactory jsrpf = new JitStaticReceivePackFactory(reporter, defaultRef, new RefLockHolderManager(),
                new UserExtractor(git.getRepository()));

        ReceivePack up = jsrpf.create(req, git.getRepository());
        Map<String, Ref> map = new HashMap<>();
        map.put("refs/heads/secrets", secrets);
        map.put("HEAD", head);
        Map<String, Ref> filtered = up.getRefFilter().filter(map);
        assertTrue(filtered.isEmpty());
    }

    Path getFolder() throws IOException {
        return tmpFolder.createTemporaryDirectory().toPath();
    }
}
