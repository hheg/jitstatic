package io.jitstatic.api;

import static io.jitstatic.JitStaticConstants.JITSTATIC_GIT_REALM;
import static io.jitstatic.JitStaticConstants.JITSTATIC_KEYADMIN_REALM;
import static io.jitstatic.JitStaticConstants.JITSTATIC_KEYUSER_REALM;
import static io.jitstatic.JitStaticConstants.REFS_HEADS_SECRETS;

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

import static io.jitstatic.source.ObjectStreamProvider.toProvider;
import static org.junit.Assert.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import javax.ws.rs.client.Entity;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.glassfish.jersey.test.grizzly.GrizzlyWebTestContainerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.dropwizard.auth.AuthDynamicFeature;
import io.dropwizard.auth.AuthValueFactoryProvider;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.dropwizard.testing.junit5.ResourceExtension;
import io.jitstatic.MetaData;
import io.jitstatic.Role;
import io.jitstatic.auth.UrlAwareBasicCredentialAuthFilter;
import io.jitstatic.auth.User;
import io.jitstatic.hosted.StoreInfo;
import io.jitstatic.storage.HashService;
import io.jitstatic.storage.Storage;
import io.jitstatic.test.TemporaryFolderExtension;
import io.jitstatic.utils.Pair;

@ExtendWith({ DropwizardExtensionsSupport.class, TemporaryFolderExtension.class })
public class BulkResourceTest {
    private static final String REFS_HEADS_MASTER = "refs/heads/master";
    private static final Charset UTF_8 = StandardCharsets.UTF_8;
    private static final String USER = "user";
    private static final String SECRET = "secret";
    private static final String BASIC_AUTH_CRED = createCreds(USER, SECRET);

    private Storage storage = mock(Storage.class);
    private HashService hashService = new HashService();

    public ResourceExtension RESOURCES = ResourceExtension.builder().setTestContainerFactory(new GrizzlyWebTestContainerFactory())
            .addProvider(new AuthDynamicFeature(new UrlAwareBasicCredentialAuthFilter(storage, hashService, (u, p) -> u.equals(USER) && p.equals(SECRET))))
            .addProvider(new AuthValueFactoryProvider.Binder<>(User.class))
            .addResource(new BulkResource(storage, REFS_HEADS_MASTER))
            .build();

    @Test
    public void testFetch() {
        StoreInfo storeInfoMock = mock(StoreInfo.class);
        MetaData storageData = mock(MetaData.class);
        when(storeInfoMock.getStreamProvider()).thenReturn(toProvider(new byte[] { 1 }));
        when(storeInfoMock.getVersion()).thenReturn("1");
        when(storeInfoMock.getMetaData()).thenReturn(storageData);
        when(storageData.getContentType()).thenReturn("application/something");
        when(storage.getList(Mockito.any()))
        .thenReturn(CompletableFuture.completedFuture(List.of(Pair.of(List.of(Pair.of("key1", storeInfoMock)), REFS_HEADS_MASTER))));
        Response response = RESOURCES.target("/bulk/fetch").request().header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED)
                .buildPost(Entity.entity(
                    // @formatter:off
                        List.of(
                                new BulkSearch(REFS_HEADS_MASTER,
                                List.of(new SearchPath("key1", false),
                                        new SearchPath("decoy/key1", false),
                                        new SearchPath("data/data/key1", false),
                                        new SearchPath("dir/dir/key1", false)))),
                        MediaType.APPLICATION_JSON))
                    // @formatter:on
                .invoke();
        SearchResultWrapper entity = response.readEntity(SearchResultWrapper.class);
        assertNotNull(entity);
        assertFalse(entity.getResult().isEmpty());
        Set<String> collect = entity.getResult().stream().map(sr -> sr.getKey()).collect(Collectors.toSet());
        assertTrue(collect.contains("key1"));
    }

    @Test
    public void testFetchPublicKey() throws RefNotFoundException {
        StoreInfo storeInfoMock = mock(StoreInfo.class);
        MetaData storageData = mock(MetaData.class);
        when(storeInfoMock.getStreamProvider()).thenReturn(toProvider(new byte[] { 1 }));
        when(storeInfoMock.getVersion()).thenReturn("1");
        when(storeInfoMock.getMetaData()).thenReturn(storageData);
        when(storageData.getContentType()).thenReturn("application/something");
        when(storage.getUser(Mockito.eq(USER), eq(REFS_HEADS_SECRETS), eq(JITSTATIC_GIT_REALM))).thenReturn(CompletableFuture.completedFuture(null));
        when(storage.getUser(eq(USER), eq(REFS_HEADS_MASTER), eq(JITSTATIC_KEYADMIN_REALM))).thenReturn(CompletableFuture.completedFuture(null));
        when(storage.getUser(eq(USER), eq(REFS_HEADS_MASTER), eq(JITSTATIC_KEYUSER_REALM)))
                .thenReturn(CompletableFuture.completedFuture(new io.jitstatic.auth.UserData(Set.of(new Role("read")), SECRET, null, null)));
        when(storage.getList(Mockito.any()))
                .thenReturn(CompletableFuture.completedFuture(List.of(Pair.of(List.of(Pair.of("key1", storeInfoMock)), REFS_HEADS_MASTER))));
        Response response = RESOURCES.target("/bulk/fetch").request().header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED)
                .buildPost(Entity.entity(
                        // @formatter:off
                        List.of(
                                new BulkSearch(REFS_HEADS_MASTER,
                                        List.of(
                                                new SearchPath("key1", false),
                                                new SearchPath("decoy/key1", false),
                                                new SearchPath("data/data/key1", false),
                                                new SearchPath("dir/dir/key1", false)))),
                        MediaType.APPLICATION_JSON))
                // @formatter:on
                .invoke();
        SearchResultWrapper entity = response.readEntity(SearchResultWrapper.class);
        when(storeInfoMock.getStreamProvider()).thenReturn(toProvider(new byte[] { 1 }));
        when(storeInfoMock.getVersion()).thenReturn("1");
        when(storeInfoMock.getMetaData()).thenReturn(storageData);
        when(storageData.getContentType()).thenReturn("application/something");
        when(storage.getUser(eq(USER), eq(REFS_HEADS_SECRETS), eq(JITSTATIC_GIT_REALM))).thenReturn(CompletableFuture.completedFuture(null));
        when(storage.getUser(eq(USER), eq(REFS_HEADS_MASTER), eq(JITSTATIC_KEYADMIN_REALM))).thenReturn(CompletableFuture.completedFuture(null));
        when(storage.getUser(eq(USER), eq(REFS_HEADS_MASTER), eq(JITSTATIC_KEYUSER_REALM)))
                .thenReturn(CompletableFuture.completedFuture(new io.jitstatic.auth.UserData(Set.of(new Role("read")), SECRET, null, null)));
        when(storage.getList(Mockito.any()))
                .thenReturn(CompletableFuture.completedFuture(List.of(Pair.of(List.of(Pair.of("key1", storeInfoMock)), REFS_HEADS_MASTER))));
        response = RESOURCES.target("/bulk/fetch").request().header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED)
                .buildPost(Entity.entity(
                        // @formatter:off
                        List.of(
                                new BulkSearch(REFS_HEADS_MASTER,
                                        List.of(
                                                new SearchPath("key1", false),
                                                new SearchPath("decoy/key1", false),
                                                new SearchPath("data/data/key1", false),
                                                new SearchPath("dir/dir/key1", false)))),
                        MediaType.APPLICATION_JSON))
                // @formatter:on
                .invoke();
        entity = response.readEntity(SearchResultWrapper.class);
        assertNotNull(entity);
        assertFalse(entity.getResult().isEmpty());
        Set<String> collect = entity.getResult().stream().map(sr -> sr.getKey()).collect(Collectors.toSet());
        assertTrue(collect.contains("key1"));
    }

    @Test
    public void testFetchProtectedKeyDenied() throws RefNotFoundException {
        StoreInfo storeInfoMock = mock(StoreInfo.class);
        MetaData storageData = mock(MetaData.class);
        when(storeInfoMock.getStreamProvider()).thenReturn(toProvider(new byte[] { 1 }));
        when(storeInfoMock.getVersion()).thenReturn("1");
        when(storeInfoMock.getMetaData()).thenReturn(storageData);
        when(storageData.getContentType()).thenReturn("application/something");
        when(storageData.getRead()).thenReturn(Set.of(new Role("noread")));
        when(storage.getUser(eq(USER), eq(REFS_HEADS_SECRETS), eq(JITSTATIC_GIT_REALM))).thenReturn(CompletableFuture.completedFuture(null));
        when(storage.getUser(eq(USER), eq(REFS_HEADS_MASTER), eq(JITSTATIC_KEYADMIN_REALM))).thenReturn(CompletableFuture.completedFuture(null));
        when(storage.getUser(eq(USER), eq(REFS_HEADS_MASTER), eq(JITSTATIC_KEYUSER_REALM)))
                .thenReturn(CompletableFuture.completedFuture(new io.jitstatic.auth.UserData(Set.of(new Role("read")), SECRET, null, null)));
        when(storage.getList(Mockito.any()))
                .thenReturn(CompletableFuture.completedFuture(List.of(Pair.of(List.of(Pair.of("key1", storeInfoMock)), REFS_HEADS_MASTER))));
        Response response = RESOURCES.target("/bulk/fetch").request().header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED)
                .buildPost(Entity.entity(
                        // @formatter:off
                        List.of(
                                new BulkSearch(REFS_HEADS_MASTER,
                                        List.of(
                                                new SearchPath("key1", false),
                                                new SearchPath("decoy/key1", false),
                                                new SearchPath("data/data/key1", false),
                                                new SearchPath("dir/dir/key1", false)))),
                        MediaType.APPLICATION_JSON))
                // @formatter:on
                .invoke();
        SearchResultWrapper entity = response.readEntity(SearchResultWrapper.class);
        assertNotNull(entity);
        assertTrue(entity.getResult().isEmpty());
    }

    @Test
    public void testFetchProtectedWrongPassword() throws RefNotFoundException {
        StoreInfo storeInfoMock = mock(StoreInfo.class);
        MetaData storageData = mock(MetaData.class);
        when(storeInfoMock.getStreamProvider()).thenReturn(toProvider(new byte[] { 1 }));
        when(storeInfoMock.getVersion()).thenReturn("1");
        when(storeInfoMock.getMetaData()).thenReturn(storageData);
        when(storageData.getContentType()).thenReturn("application/something");
        when(storageData.getRead()).thenReturn(Set.of(new Role("noread")));
        when(storage.getUser(eq(USER), eq(REFS_HEADS_SECRETS), eq(JITSTATIC_GIT_REALM))).thenReturn(CompletableFuture.completedFuture(null));
        when(storage.getUser(eq(USER), eq(REFS_HEADS_MASTER), eq(JITSTATIC_KEYADMIN_REALM))).thenReturn(CompletableFuture.completedFuture(null));
        when(storage.getUser(eq(USER), eq(REFS_HEADS_MASTER), eq(JITSTATIC_KEYUSER_REALM)))
                .thenReturn(CompletableFuture.completedFuture(new io.jitstatic.auth.UserData(Set.of(new Role("read")), "other", null, null)));
        when(storage.getList(Mockito.any()))
                .thenReturn(CompletableFuture.completedFuture(List.of(Pair.of(List.of(Pair.of("key1", storeInfoMock)), REFS_HEADS_MASTER))));
        Response response = RESOURCES.target("/bulk/fetch").request().header(HttpHeaders.AUTHORIZATION, createCreds(USER, "blah"))
                .buildPost(Entity.entity(
                        // @formatter:off
                        List.of(
                                new BulkSearch(REFS_HEADS_MASTER,
                                        List.of(
                                                new SearchPath("key1", false),
                                                new SearchPath("decoy/key1", false),
                                                new SearchPath("data/data/key1", false),
                                                new SearchPath("dir/dir/key1", false)))),
                        MediaType.APPLICATION_JSON))
                // @formatter:on
                .invoke();
        SearchResultWrapper entity = response.readEntity(SearchResultWrapper.class);
        assertNotNull(entity);
        assertTrue(entity.getResult().isEmpty());
    }

    @Test
    public void testFetchProtectedAllowed() throws RefNotFoundException {
        StoreInfo storeInfoMock = mock(StoreInfo.class);
        MetaData storageData = mock(MetaData.class);
        when(storeInfoMock.getStreamProvider()).thenReturn(toProvider(new byte[] { 1 }));
        when(storeInfoMock.getVersion()).thenReturn("1");
        when(storeInfoMock.getMetaData()).thenReturn(storageData);
        when(storageData.getContentType()).thenReturn("application/something");
        when(storageData.getRead()).thenReturn(Set.of(new Role("read")));
        when(storage.getUser(eq(USER), eq(REFS_HEADS_SECRETS), eq(JITSTATIC_GIT_REALM))).thenReturn(CompletableFuture.completedFuture(null));
        when(storage.getUser(eq(USER), eq(REFS_HEADS_MASTER), eq(JITSTATIC_KEYADMIN_REALM))).thenReturn(CompletableFuture.completedFuture(null));
        when(storage.getUser(eq(USER), eq(REFS_HEADS_MASTER), eq(JITSTATIC_KEYUSER_REALM)))
        .thenReturn(CompletableFuture.completedFuture(new io.jitstatic.auth.UserData(Set.of(new Role("read")), SECRET, null, null)));
        when(storage.getList(Mockito.any()))
        .thenReturn(CompletableFuture.completedFuture(List.of(Pair.of(List.of(Pair.of("key1", storeInfoMock)), REFS_HEADS_MASTER))));
        Response response = RESOURCES.target("/bulk/fetch").request().header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED)
                .buildPost(Entity.entity(
                        // @formatter:off
                        List.of(
                                new BulkSearch(REFS_HEADS_MASTER,
                                        List.of(
                                                new SearchPath("key1", false),
                                                new SearchPath("decoy/key1", false),
                                                new SearchPath("data/data/key1", false),
                                                new SearchPath("dir/dir/key1", false)))),
                        MediaType.APPLICATION_JSON))
                // @formatter:on
                .invoke();
        SearchResultWrapper entity = response.readEntity(SearchResultWrapper.class);
        assertNotNull(entity);
        assertFalse(entity.getResult().isEmpty());
        Set<String> collect = entity.getResult().stream().map(sr -> sr.getKey()).collect(Collectors.toSet());
        assertTrue(collect.contains("key1"));
    }

    @Test
    public void testFetchProtectedGitAllowed() throws RefNotFoundException {
        StoreInfo storeInfoMock = mock(StoreInfo.class);
        MetaData storageData = mock(MetaData.class);
        when(storeInfoMock.getStreamProvider()).thenReturn(toProvider(new byte[] { 1 }));
        when(storeInfoMock.getVersion()).thenReturn("1");
        when(storeInfoMock.getMetaData()).thenReturn(storageData);
        when(storageData.getContentType()).thenReturn("application/something");
        when(storageData.getRead()).thenReturn(Set.of(new Role("read")));
        when(storage.getUser(eq(USER), eq(REFS_HEADS_SECRETS), eq(JITSTATIC_GIT_REALM)))
        .thenReturn(CompletableFuture.completedFuture(new io.jitstatic.auth.UserData(Set.of(new Role("read")), SECRET, null, null)));
        when(storage.getUser(eq(USER), eq(REFS_HEADS_MASTER), eq(JITSTATIC_KEYADMIN_REALM))).thenReturn(CompletableFuture.completedFuture(null));
        when(storage.getUser(eq(USER), eq(REFS_HEADS_MASTER), eq(JITSTATIC_KEYUSER_REALM)))
        .thenReturn(CompletableFuture.completedFuture(null));
        when(storage.getList(Mockito.any()))
        .thenReturn(CompletableFuture.completedFuture(List.of(Pair.of(List.of(Pair.of("key1", storeInfoMock)), REFS_HEADS_MASTER))));
        Response response = RESOURCES.target("/bulk/fetch").request().header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED)
                .buildPost(Entity.entity(
                        // @formatter:off
                        List.of(
                                new BulkSearch(REFS_HEADS_MASTER,
                                        List.of(
                                                new SearchPath("key1", false),
                                                new SearchPath("decoy/key1", false),
                                                new SearchPath("data/data/key1", false),
                                                new SearchPath("dir/dir/key1", false)))),
                        MediaType.APPLICATION_JSON))
                // @formatter:on
                .invoke();
        SearchResultWrapper entity = response.readEntity(SearchResultWrapper.class);
        assertNotNull(entity);
    }

    @Test
    public void testFetchDoubleUsers() throws RefNotFoundException {
        StoreInfo storeInfoMock = mock(StoreInfo.class);
        MetaData storageData = mock(MetaData.class);
        when(storeInfoMock.getStreamProvider()).thenReturn(toProvider(new byte[] { 1 }));
        when(storeInfoMock.getVersion()).thenReturn("1");
        when(storeInfoMock.getMetaData()).thenReturn(storageData);
        when(storageData.getContentType()).thenReturn("application/something");
        when(storageData.getRead()).thenReturn(Set.of(new Role("read")));
        when(storage.getUser(eq(USER), eq(REFS_HEADS_SECRETS), eq(JITSTATIC_GIT_REALM))).thenReturn(CompletableFuture.completedFuture(null));
        when(storage.getUser(eq(USER), eq(REFS_HEADS_MASTER), eq(JITSTATIC_KEYADMIN_REALM)))
        .thenReturn(CompletableFuture.completedFuture(new io.jitstatic.auth.UserData(Set.of(new Role("read")), SECRET, null, null)));
        when(storage.getUser(eq(USER), eq(REFS_HEADS_MASTER), eq(JITSTATIC_KEYUSER_REALM)))
        .thenReturn(CompletableFuture.completedFuture(new io.jitstatic.auth.UserData(Set.of(new Role("read")), SECRET, null, null)));
        when(storage.getList(Mockito.any()))
        .thenReturn(CompletableFuture.completedFuture(List.of(Pair.of(List.of(Pair.of("key1", storeInfoMock)), REFS_HEADS_MASTER))));
        Response response = RESOURCES.target("/bulk/fetch").request().header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED)
                .buildPost(Entity.entity(
                        // @formatter:off
                        List.of(
                                new BulkSearch(REFS_HEADS_MASTER,
                                        List.of(
                                                new SearchPath("key1", false),
                                                new SearchPath("decoy/key1", false),
                                                new SearchPath("data/data/key1", false),
                                                new SearchPath("dir/dir/key1", false)))),
                        MediaType.APPLICATION_JSON))
                // @formatter:on
                .invoke();
        SearchResultWrapper entity = response.readEntity(SearchResultWrapper.class);
        assertNotNull(entity);
        assertFalse(entity.getResult().isEmpty());
        Set<String> collect = entity.getResult().stream().map(sr -> sr.getKey()).collect(Collectors.toSet());
        assertTrue(collect.contains("key1"));
    }

    @Test
    public void testFetchInWrongRefWithRoot() throws RefNotFoundException {
        StoreInfo storeInfoMock = mock(StoreInfo.class);
        MetaData storageData = mock(MetaData.class);
        when(storeInfoMock.getStreamProvider()).thenReturn(toProvider(new byte[] { 1 }));
        when(storeInfoMock.getVersion()).thenReturn("1");
        when(storeInfoMock.getMetaData()).thenReturn(storageData);
        when(storageData.getContentType()).thenReturn("application/something");
        when(storageData.getRead()).thenReturn(Set.of(new Role("read")));
        when(storage.getUser(eq(USER), eq(REFS_HEADS_MASTER), eq(JITSTATIC_KEYADMIN_REALM))).thenReturn(CompletableFuture.completedFuture(null));
        when(storage.getUser(eq(USER), eq(REFS_HEADS_MASTER), eq(JITSTATIC_KEYUSER_REALM))).thenReturn(CompletableFuture.completedFuture(null));
        when(storage.getUser(eq(USER), eq("refs/heads/other"), eq(JITSTATIC_KEYADMIN_REALM))).thenThrow(new RefNotFoundException("refs/heads/other"));
        when(storage.getUser(eq(USER), eq("refs/heads/other"), eq(JITSTATIC_KEYUSER_REALM))).thenThrow(new RefNotFoundException("refs/heads/other"));
        when(storage.getUser(eq(USER), eq(REFS_HEADS_SECRETS), eq(JITSTATIC_GIT_REALM))).thenReturn(CompletableFuture.completedFuture(null));
        when(storage.getList(Mockito.any()))
        .thenReturn(CompletableFuture.completedFuture(List.of(Pair.of(List.of(Pair.of("key1", storeInfoMock)), REFS_HEADS_MASTER))));
        Response response = RESOURCES.target("/bulk/fetch").request().header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_CRED)
                .buildPost(Entity.entity(
                        // @formatter:off
                        List.of(
                                new BulkSearch("refs/heads/other",
                                        List.of(
                                                new SearchPath("key1", false),
                                                new SearchPath("decoy/key1", false),
                                                new SearchPath("data/data/key1", false),
                                                new SearchPath("dir/dir/key1", false))),
                                new BulkSearch(REFS_HEADS_MASTER,
                                        List.of(
                                                new SearchPath("key1", true)))), MediaType.APPLICATION_JSON))
                // @formatter:on
                .invoke();
        SearchResultWrapper entity = response.readEntity(SearchResultWrapper.class);
        assertNotNull(entity);
        assertFalse(entity.getResult().isEmpty());
        assertEquals(1, entity.getResult().size());
        Mockito.verify(storage, Mockito.times(3)).getUser(Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    public void testFetchInWrongRef() throws RefNotFoundException {
        StoreInfo storeInfoMock = mock(StoreInfo.class);
        MetaData storageData = mock(MetaData.class);
        when(storeInfoMock.getStreamProvider()).thenReturn(toProvider(new byte[] { 1 }));
        when(storeInfoMock.getVersion()).thenReturn("1");
        when(storeInfoMock.getMetaData()).thenReturn(storageData);
        when(storageData.getContentType()).thenReturn("application/something");
        when(storageData.getRead()).thenReturn(Set.of(new Role("read")));
        when(storage.getUser(eq("keyuser"), eq("refs/heads/other"), eq(JITSTATIC_KEYUSER_REALM))).thenThrow(new RefNotFoundException("refs/heads/other"));
        when(storage.getUser(eq("keyuser"), eq("refs/heads/other"), eq(JITSTATIC_KEYADMIN_REALM))).thenThrow(new RefNotFoundException("refs/heads/other"));
        when(storage.getUser(eq("keyuser"), eq(REFS_HEADS_MASTER), eq(JITSTATIC_KEYUSER_REALM)))
        .thenReturn(CompletableFuture.completedFuture(new io.jitstatic.auth.UserData(Set.of(new Role("read")), "blah", null, null)));
        when(storage.getUser(eq("keyuser"), eq(REFS_HEADS_MASTER), eq(JITSTATIC_KEYADMIN_REALM))).thenReturn(CompletableFuture.completedFuture(null));
        when(storage.getUser(eq("keyuser"), eq(REFS_HEADS_SECRETS), eq(JITSTATIC_GIT_REALM))).thenReturn(CompletableFuture.completedFuture(null));
        when(storage.getList(Mockito.any()))
        .thenReturn(CompletableFuture.completedFuture(List.of(Pair.of(List.of(Pair.of("key1", storeInfoMock)), "refs/heads/master"))));
        Response response = RESOURCES.target("/bulk/fetch").request().header(HttpHeaders.AUTHORIZATION, createCreds("keyuser", "blah"))
                .buildPost(Entity.entity(
                        // @formatter:off
                        List.of(
                                new BulkSearch("refs/heads/other",
                                        List.of(
                                                new SearchPath("key1", false),
                                                new SearchPath("decoy/key1", false),
                                                new SearchPath("data/data/key1", false),
                                                new SearchPath("dir/dir/key1", false))),
                                new BulkSearch(REFS_HEADS_MASTER,
                                        List.of(
                                                new SearchPath("key1", true)))), MediaType.APPLICATION_JSON))
                // @formatter:on
                .invoke();
        SearchResultWrapper entity = response.readEntity(SearchResultWrapper.class);
        assertNotNull(entity);
        assertFalse(entity.getResult().isEmpty());
        assertEquals(1, entity.getResult().size());
    }

    @Test
    public void testFetchInWrongRefInvalidUser() throws RefNotFoundException {
        StoreInfo storeInfoMock = mock(StoreInfo.class);
        MetaData storageData = mock(MetaData.class);
        when(storeInfoMock.getStreamProvider()).thenReturn(toProvider(new byte[] { 1 }));
        when(storeInfoMock.getVersion()).thenReturn("1");
        when(storeInfoMock.getMetaData()).thenReturn(storageData);
        when(storageData.getContentType()).thenReturn("application/something");
        when(storageData.getRead()).thenReturn(Set.of(new Role("read")));
        when(storage.getUser(eq("keyuser"), eq("refs/heads/other"), eq(JITSTATIC_KEYUSER_REALM))).thenThrow(new RefNotFoundException("refs/heads/other"));
        when(storage.getUser(eq("keyuser"), eq("refs/heads/other"), eq(JITSTATIC_KEYADMIN_REALM))).thenThrow(new RefNotFoundException("refs/heads/other"));
        when(storage.getUser(eq("keyuser"), eq(REFS_HEADS_MASTER), eq(JITSTATIC_KEYUSER_REALM))).thenReturn(CompletableFuture.completedFuture(null));
        when(storage.getUser(eq("keyuser"), eq(REFS_HEADS_MASTER), eq(JITSTATIC_KEYADMIN_REALM))).thenReturn(CompletableFuture.completedFuture(null));
        when(storage.getUser(eq("keyuser"), eq(REFS_HEADS_SECRETS), eq(JITSTATIC_GIT_REALM))).thenReturn(CompletableFuture.completedFuture(null));
        when(storage.getList(Mockito.any()))
        .thenReturn(CompletableFuture.completedFuture(List.of(Pair.of(List.of(Pair.of("key1", storeInfoMock)), "refs/heads/other"))));
        Response response = RESOURCES.target("/bulk/fetch").request().header(HttpHeaders.AUTHORIZATION, createCreds("keyuser", "secret"))
                .buildPost(Entity.entity(
                        // @formatter:off
                        List.of(
                                new BulkSearch("refs/heads/other",
                                        List.of(
                                                new SearchPath("key1", false),
                                                new SearchPath("decoy/key1", false),
                                                new SearchPath("data/data/key1", false),
                                                new SearchPath("dir/dir/key1", false))),
                                new BulkSearch(REFS_HEADS_MASTER,
                                        List.of(
                                                new SearchPath("key1", true)))), MediaType.APPLICATION_JSON))
                // @formatter:on
                .invoke();
        SearchResultWrapper entity = response.readEntity(SearchResultWrapper.class);
        assertNotNull(entity);
        assertTrue(entity.getResult().isEmpty());
    }

    private static String createCreds(String user, String secret) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + secret).getBytes(UTF_8));
    }
}
