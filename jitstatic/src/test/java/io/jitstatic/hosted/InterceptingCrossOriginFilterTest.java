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

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.servlets.CrossOriginFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.jitstatic.JitStaticConstants;

public class InterceptingCrossOriginFilterTest {

    @ParameterizedTest
    @MethodSource("injector")
    public void testNoCORS(Supplier<CrossOriginFilter> filterSupp) throws IOException, ServletException {
        CrossOriginFilter filter = filterSupp.get();
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(request, response, chain);
        verify(chain, times(1)).doFilter(eq(request), eq(response));
    }

    @ParameterizedTest
    @MethodSource("injector")
    public void testSimpleRequest(Supplier<CrossOriginFilter> filterSupp) throws IOException, ServletException {
        CrossOriginFilter filter = filterSupp.get();
        FilterConfig fc = mock(FilterConfig.class);
        filter.init(fc);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getHeader(eq("origin"))).thenReturn("http://localhost");
        when(request.getHeader(eq("Origin"))).thenReturn("http://localhost");
        when(request.getHeaders(eq("Connection"))).thenReturn(Collections.enumeration(List.of()));
        when(request.getMethod()).thenReturn("GET");
        filter.doFilter(request, response, chain);
        verify(response, times(1)).setHeader("Access-Control-Allow-Origin", "http://localhost");
        verify(response, times(1)).setHeader("Access-Control-Allow-Credentials", "true");
    }

    @ParameterizedTest
    @MethodSource("injector")
    public void testSimpleRequestWithExposedHeader(Supplier<CrossOriginFilter> filterSupp) throws ServletException, IOException {
        CrossOriginFilter filter = filterSupp.get();
        FilterConfig fc = mock(FilterConfig.class);
        when(fc.getInitParameter(eq("exposedHeaders"))).thenReturn("etag");
        filter.init(fc);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getHeader(eq("origin"))).thenReturn("http://localhost");
        when(request.getHeader(eq("Origin"))).thenReturn("http://localhost");
        when(request.getHeaders(eq("Connection"))).thenReturn(Collections.enumeration(List.of()));
        when(request.getMethod()).thenReturn("GET");
        filter.doFilter(request, response, chain);
        verify(response, times(1)).setHeader("Access-Control-Allow-Origin", "http://localhost");
        verify(response, times(1)).setHeader("Access-Control-Allow-Credentials", "true");
        verify(response, times(1)).setHeader("Access-Control-Expose-Headers", "etag");
    }

    @ParameterizedTest
    @MethodSource("injector")
    public void testPreflightSimpleRequest(Supplier<CrossOriginFilter> filterSupp) throws IOException, ServletException {
        CrossOriginFilter filter = filterSupp.get();
        FilterConfig fc = mock(FilterConfig.class);
        when(fc.getInitParameter(eq("exposedHeaders"))).thenReturn("etag");
        filter.init(fc);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getHeader(eq("origin"))).thenReturn("http://localhost");
        when(request.getHeader(eq("Origin"))).thenReturn("http://localhost");
        when(request.getHeaders(eq("Connection"))).thenReturn(Collections.enumeration(List.of()));
        when(request.getHeader(eq("Access-Control-Request-Method"))).thenReturn("POST");
        when(request.getMethod()).thenReturn("OPTIONS");
        filter.doFilter(request, response, chain);
        verify(response, times(1)).setHeader("Access-Control-Allow-Origin", "http://localhost");
        verify(response, times(1)).setHeader("Access-Control-Allow-Credentials", "true");
        verify(response, times(1)).setHeader("Access-Control-Max-Age", "1800");
        verify(response, times(1)).setHeader("Access-Control-Allow-Methods", "GET,POST,HEAD");
        verify(response, times(1)).setHeader("Access-Control-Allow-Headers", "X-Requested-With,Content-Type,Accept,Origin");
    }

    @ParameterizedTest
    @MethodSource("injector")
    public void testPreflightNonSimpleRequest(Supplier<CrossOriginFilter> filterSupp) throws IOException, ServletException {
        CrossOriginFilter filter = filterSupp.get();
        FilterConfig fc = mock(FilterConfig.class);
        when(fc.getInitParameter(eq("exposedHeaders"))).thenReturn("etag");
        when(fc.getInitParameter(eq("allowedMethods"))).thenReturn("OPTIONS,GET,PUT,POST,DELETE,HEAD");
        filter.init(fc);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getHeader(eq("origin"))).thenReturn("http://localhost");
        when(request.getHeader(eq("Origin"))).thenReturn("http://localhost");
        when(request.getHeaders(eq("Connection"))).thenReturn(Collections.enumeration(List.of()));
        when(request.getHeader(eq("Access-Control-Request-Method"))).thenReturn("PUT");
        when(request.getHeader(eq("Access-Control-Request-Headers"))).thenReturn("Origin,Content-Type");
        when(request.getMethod()).thenReturn("OPTIONS");
        filter.doFilter(request, response, chain);
        verify(response, times(1)).setHeader("Access-Control-Allow-Origin", "http://localhost");
        verify(response, times(1)).setHeader("Access-Control-Allow-Credentials", "true");
        verify(response, times(1)).setHeader("Access-Control-Max-Age", "1800");
        verify(response, times(1)).setHeader("Access-Control-Allow-Methods", "OPTIONS,GET,PUT,POST,DELETE,HEAD");
        verify(response, times(1)).setHeader("Access-Control-Allow-Headers", "X-Requested-With,Content-Type,Accept,Origin");
    }

    @Test
    public void testPreflightDeclaredHeader() throws ServletException, IOException {
        CrossOriginFilter filter = new InterceptingCrossOriginFilter();
        FilterConfig fc = mock(FilterConfig.class);
        when(fc.getInitParameter(eq("exposedHeaders"))).thenReturn("etag");
        when(fc.getInitParameter(eq("allowedMethods"))).thenReturn("OPTIONS,GET,PUT,POST,DELETE,HEAD");
        when(fc.getInitParameter(eq("allowedHeaders"))).thenReturn("x-requested-with, content-type, accept, origin, if-match");
        filter.init(fc);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getHeader(eq("origin"))).thenReturn("http://localhost");
        when(request.getHeader(eq("Origin"))).thenReturn("http://localhost");
        when(request.getHeaders(eq("Access-Control-Request-Headers"))).thenReturn(Collections.enumeration(List.of("Origin", "Content-Type", "extra")));
        when(request.getHeaders(eq("Connection"))).thenReturn(Collections.enumeration(List.of()));
        when(request.getHeader(eq("Access-Control-Request-Method"))).thenReturn("PUT");
        when(request.getHeader(eq("Access-Control-Request-Headers"))).thenReturn("Origin,Content-Type,extra");
        when(request.getMethod()).thenReturn("OPTIONS");
        when(request.getAttribute(JitStaticConstants.DEFERREDHEADERS)).thenReturn(List.of("extra"));
        when(request.getAttribute(JitStaticConstants.DECLAREDHEADERS)).thenReturn(List.of("extra"));
        when(response.getHeader(eq("Access-Control-Allow-Headers"))).thenReturn("X-Requested-With,Content-Type,Accept,Origin");
        filter.doFilter(request, response, chain);
        verify(response, times(1)).setHeader("Access-Control-Allow-Origin", "http://localhost");
        verify(response, times(1)).setHeader("Access-Control-Allow-Credentials", "true");
        verify(response, times(1)).setHeader("Access-Control-Max-Age", "1800");
        verify(response, times(1)).setHeader("Access-Control-Allow-Methods", "OPTIONS,GET,PUT,POST,DELETE,HEAD");
        verify(response, times(1)).setHeader("Access-Control-Allow-Headers", "x-requested-with,content-type,accept,origin,if-match");
        verify(response, times(1)).setHeader("Access-Control-Allow-Headers", "x-requested-with,content-type,accept,origin,if-match,extra");
    }

    @Test
    public void testPreflightDeclaredWrongHeader() throws ServletException, IOException {
        CrossOriginFilter filter = new InterceptingCrossOriginFilter();
        FilterConfig fc = mock(FilterConfig.class);
        when(fc.getInitParameter(eq("exposedHeaders"))).thenReturn("etag");
        when(fc.getInitParameter(eq("allowedMethods"))).thenReturn("OPTIONS,GET,PUT,POST,DELETE,HEAD");
        when(fc.getInitParameter(eq("allowedHeaders"))).thenReturn("x-requested-with, content-type, accept, origin, if-match");
        filter.init(fc);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getHeader(eq("origin"))).thenReturn("http://localhost");
        when(request.getHeader(eq("Origin"))).thenReturn("http://localhost");
        when(request.getHeaders(eq("Access-Control-Request-Headers"))).thenReturn(Collections.enumeration(List.of("Origin", "Content-Type", "wrong")));
        when(request.getHeaders(eq("Connection"))).thenReturn(Collections.enumeration(List.of()));
        when(request.getHeader(eq("Access-Control-Request-Method"))).thenReturn("PUT");
        when(request.getHeader(eq("Access-Control-Request-Headers"))).thenReturn("Origin,Content-Type,extra");
        when(request.getMethod()).thenReturn("OPTIONS");
        when(request.getAttribute(JitStaticConstants.DEFERREDHEADERS)).thenReturn(List.of("wrong"));
        when(request.getAttribute(JitStaticConstants.DECLAREDHEADERS)).thenReturn(List.of("extra"));
        when(response.getHeader(eq("Access-Control-Allow-Headers"))).thenReturn("X-Requested-With,Content-Type,Accept,Origin");
        filter.doFilter(request, response, chain);
        verify(response, times(1)).setHeader("Access-Control-Allow-Origin", null);
        verify(response, times(1)).setHeader("Access-Control-Allow-Credentials", null);
        verify(response, times(1)).setHeader("Access-Control-Max-Age", null);
        verify(response, times(1)).setHeader("Access-Control-Allow-Methods", null);
        verify(response, times(1)).setHeader("Access-Control-Allow-Headers", null);
    }

    @Test
    public void testPreflightDeclaredNotDeclaredHeader() throws ServletException, IOException {
        CrossOriginFilter filter = new InterceptingCrossOriginFilter();
        FilterConfig fc = mock(FilterConfig.class);
        when(fc.getInitParameter(eq("exposedHeaders"))).thenReturn("etag");
        when(fc.getInitParameter(eq("allowedMethods"))).thenReturn("OPTIONS,GET,PUT,POST,DELETE,HEAD");
        when(fc.getInitParameter(eq("allowedHeaders"))).thenReturn("x-requested-with, content-type, accept, origin, if-match");
        filter.init(fc);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getHeader(eq("origin"))).thenReturn("http://localhost");
        when(request.getHeader(eq("Origin"))).thenReturn("http://localhost");
        when(request.getHeaders(eq("Access-Control-Request-Headers"))).thenReturn(Collections.enumeration(List.of("Origin", "Content-Type", "wrong")));
        when(request.getHeaders(eq("Connection"))).thenReturn(Collections.enumeration(List.of()));
        when(request.getHeader(eq("Access-Control-Request-Method"))).thenReturn("PUT");
        when(request.getHeader(eq("Access-Control-Request-Headers"))).thenReturn("Origin,Content-Type,extra");
        when(request.getMethod()).thenReturn("OPTIONS");
        when(request.getAttribute(JitStaticConstants.DEFERREDHEADERS)).thenReturn(List.of("wrong"));
        when(response.getHeader(eq("Access-Control-Allow-Headers"))).thenReturn("X-Requested-With,Content-Type,Accept,Origin");
        filter.doFilter(request, response, chain);
        verify(response, times(1)).setHeader("Access-Control-Allow-Origin", null);
        verify(response, times(1)).setHeader("Access-Control-Allow-Credentials", null);
        verify(response, times(1)).setHeader("Access-Control-Max-Age", null);
        verify(response, times(1)).setHeader("Access-Control-Allow-Methods", null);
        verify(response, times(1)).setHeader("Access-Control-Allow-Headers", null);
    }

    @SuppressWarnings("unused")
    private static Stream<Arguments> injector() {
        Supplier<CrossOriginFilter> f1 = () -> new CrossOriginFilter();
        Supplier<CrossOriginFilter> f2 = () -> new InterceptingCrossOriginFilter();
        return Stream.of(
                Arguments.of(f1),
                Arguments.of(f2));
    }

}
