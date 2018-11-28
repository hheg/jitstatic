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

import static io.jitstatic.JitStaticConstants.DECLAREDHEADERS;
import static io.jitstatic.JitStaticConstants.DEFERREDHEADERS;
import static java.util.stream.Collectors.joining;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.servlets.CrossOriginFilter;
import org.eclipse.jetty.util.StringUtil;

public class InterceptingCrossOriginFilter extends CrossOriginFilter {

    private static final String ACCESS_CONTROL_EXPOSE_HEADERS = "Access-Control-Expose-Headers";
    private static final String ORIGIN_HEADER = "origin";
    private static final List<String> DEFAULT_ALLOWED_HEADERS = Arrays.asList("x-requested-with", "content-type", "accept", ORIGIN_HEADER, "if-match");
    private final List<String> allowedHeaders = new ArrayList<>();
    private boolean anyHeadersAllowed = false;

    @Override
    public void init(final FilterConfig config) throws ServletException {
        final String allowedHeadersConfig = config.getInitParameter(ALLOWED_HEADERS_PARAM);
        if (allowedHeadersConfig == null) {
            allowedHeaders.addAll(DEFAULT_ALLOWED_HEADERS);
        } else if ("*".equals(allowedHeadersConfig)) {
            anyHeadersAllowed = true;
        } else {
            allowedHeaders.addAll(
                    Arrays.asList(StringUtil.csvSplit(allowedHeadersConfig)).stream().map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toList()));
        }
        super.init(config);
    }

    @Override
    public void doFilter(final ServletRequest request, final ServletResponse response, final FilterChain chain) throws IOException, ServletException {
        handle((HttpServletRequest) request, (HttpServletResponse) response, chain);
    }

    /*
     * This is a hack to work around that the underlying framework flushes headers
     * in the request for requests with a body. So changing them here have no
     * effect.
     */
    private void handle(final HttpServletRequest request, final HttpServletResponse response, final FilterChain chain) throws IOException, ServletException {
        final String origin = request.getHeader(ORIGIN_HEADER);
        if (origin != null && isEnabled(request) && !anyHeadersAllowed) {
            super.doFilter(deferHeaderRequest(request), response, chain);
            final List<String> deferredHeaders = extactAttribute(request, DEFERREDHEADERS);
            List<String> declaredHeaders = extactAttribute(request, DECLAREDHEADERS);
            if (declaredHeaders != null) {
                final String accessControlAllowHeaders = response.getHeader(ACCESS_CONTROL_ALLOW_HEADERS_HEADER);
                if (accessControlAllowHeaders != null && deferredHeaders != null) {
                    declaredHeaders = declaredHeaders.stream().map(m -> m.toLowerCase(Locale.ROOT)).collect(Collectors.toList());
                    if (deferredHeaders.stream().allMatch(declaredHeaders::contains)) {
                        final String declared = declaredHeaders.stream().collect(joining(","));
                        response.setHeader(ACCESS_CONTROL_ALLOW_HEADERS_HEADER,
                                DEFAULT_ALLOWED_HEADERS.stream().collect(joining(",")) + "," + declared);
                    } else {
                        rollback(request, response);
                    }
                }
            } else if (deferredHeaders != null) {
                final String defaultExposedHeaders = response.getHeader(ACCESS_CONTROL_EXPOSE_HEADERS);
                if (defaultExposedHeaders == null) {
                    // There were no declared headers which means that there's no matching ones
                    // Rollback any positive decisions in because we now have non-allowed headers
                    rollback(request, response);
                }
            }
        } else {
            super.doFilter(request, response, chain);
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> extactAttribute(final HttpServletRequest request, final String attribute) {
        return (List<String>) request.getAttribute(attribute);
    }

    private void rollback(final HttpServletRequest request, final HttpServletResponse response) {
        response.setHeader(ACCESS_CONTROL_ALLOW_ORIGIN_HEADER, null);
        response.setHeader("Vary", null);
        response.setHeader(ACCESS_CONTROL_ALLOW_CREDENTIALS_HEADER, null);
        response.setHeader(ACCESS_CONTROL_MAX_AGE_HEADER, null);
        response.setHeader(ACCESS_CONTROL_ALLOW_METHODS_HEADER, null);
        response.setHeader(ACCESS_CONTROL_ALLOW_HEADERS_HEADER, null);
    }

    private HttpServletRequest deferHeaderRequest(HttpServletRequest request) {
        final List<String> requestedHeaders = getAccessControlRequestHeadersFromRequest(request);
        final List<String> deferredHeaders = requestedHeaders.stream().filter(s -> allowedHeaders.stream().noneMatch(a -> a.equalsIgnoreCase(s)))
                .map(h -> h.toLowerCase(Locale.ROOT)).collect(Collectors.toList());
        if (!deferredHeaders.isEmpty()) {
            request.setAttribute(DEFERREDHEADERS, deferredHeaders);
            return new DeferredHeadersHttpServletRequest(request, allowedHeaders);
        }
        return request;
    }

    private List<String> getAccessControlRequestHeadersFromRequest(final HttpServletRequest request) {
        final Enumeration<String> accessControlRequestHeaders = request.getHeaders(ACCESS_CONTROL_REQUEST_HEADERS_HEADER);
        if (accessControlRequestHeaders == null)
            return List.of();
        final List<String> requestedHeaders = new ArrayList<>();
        while (accessControlRequestHeaders.hasMoreElements()) {
            String h = accessControlRequestHeaders.nextElement().trim();
            String[] splitted = StringUtil.csvSplit(h);
            for (String s : splitted) {
                s = s.trim();
                if (s.length() > 0)
                    requestedHeaders.add(s);
            }
        }
        return requestedHeaders;
    }

    private static class DeferredHeadersHttpServletRequest extends HttpServletRequestWrapper {

        private final List<String> approvedHeaders;
        private final HttpServletRequest request;

        public DeferredHeadersHttpServletRequest(final HttpServletRequest request, final List<String> approvedHeaders) {
            super(request);
            this.request = request;
            this.approvedHeaders = approvedHeaders;
        }

        @Override
        public String getHeader(String name) {
            if (ACCESS_CONTROL_REQUEST_HEADERS_HEADER.equals(name)) {
                return approvedHeaders.stream().collect(joining(","));
            }
            return request.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if (ACCESS_CONTROL_REQUEST_HEADERS_HEADER.equals(name)) {
                return Collections.enumeration(approvedHeaders);
            }
            return request.getHeaders(name);
        }
    }
}
