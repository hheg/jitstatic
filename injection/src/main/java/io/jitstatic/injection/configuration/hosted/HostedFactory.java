package io.jitstatic.injection.configuration.hosted;

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

import java.nio.file.Path;

import javax.validation.Valid;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;

import org.hibernate.validator.constraints.NotEmpty;

import com.fasterxml.jackson.annotation.JsonProperty;

public class HostedFactory {

    public static final String SERVLET_NAME = "servlet-name";
    public static final String BASE_PATH = "base-path";
    public static final String EXPOSE_ALL = "expose-all";

    @JsonProperty
    @Pattern(regexp = "^refs/heads/.+$|^refs/tags/.+$")
    private String branch = "refs/heads/master";

    public String getBranch() { return branch; }

    public void setBranch(String branch) { this.branch = branch; }

    @NotNull
    @JsonProperty
    private String servletName = "jitstatic";

    @NotNull
    @JsonProperty
    private String hostedEndpoint = "git";

    @NotNull
    @JsonProperty
    private Path basePath;

    @JsonProperty
    private Path tmpPath;

    @JsonProperty
    @Min(1_000_000)
    @Max(20_000_000)
    private int threshold = 1_000_000;

    @NotNull
    @JsonProperty
    private String userName;

    @NotNull
    @JsonProperty
    private String secret;

    @JsonProperty
    private boolean exposeAll;

    @JsonProperty
    private String adminName = "admin";

    @JsonProperty
    private String adminPass;

    @JsonProperty
    private boolean protectMetrics;

    @JsonProperty
    private boolean protectHealthChecks;

    @JsonProperty
    private boolean protectTasks;

    @JsonProperty
    @Valid
    private Cors cors;

    @JsonProperty
    private String privateSalt = null;

    @JsonProperty
    private int iterations = 5;

    public String getServletName() { return servletName; }

    public void setServletName(String servletName) { this.servletName = servletName; }

    public Path getBasePath() { return basePath; }

    public void setBasePath(Path basePath) { this.basePath = basePath; }

    public boolean isExposeAll() { return exposeAll; }

    public void setExposeAll(boolean exposeAll) { this.exposeAll = exposeAll; }

    public String getHostedEndpoint() { return hostedEndpoint; }

    public void setHostedEndpoint(String hostedEndpoint) { this.hostedEndpoint = hostedEndpoint; }

    public String getUserName() { return userName; }

    public void setUserName(String userName) { this.userName = userName; }

    public String getSecret() { return secret; }

    public void setSecret(String secret) { this.secret = secret; }

    public String getAdminName() { return adminName; }

    public void setAdminName(String adminName) { this.adminName = adminName; }

    public String getAdminPass() { return adminPass; }

    public void setAdminPass(String adminPass) { this.adminPass = adminPass; }

    public boolean isProtectMetrics() { return protectMetrics; }

    public void setProtectMetrics(boolean protectMetrics) { this.protectMetrics = protectMetrics; }

    public boolean isProtectHealthChecks() { return protectHealthChecks; }

    public void setProtectHealthChecks(boolean protectHealthChecks) { this.protectHealthChecks = protectHealthChecks; }

    public static class Cors {
        @JsonProperty
        @NotEmpty
        private String allowedOrigins = "*";

        @JsonProperty
        @NotEmpty
        private String allowedHeaders = "X-Requested-With,Content-Type,Accept,Origin,if-match";

        @JsonProperty
        @NotEmpty
        private String allowedMethods = "OPTIONS,GET,PUT,POST,DELETE,HEAD";

        @JsonProperty
        @NotEmpty
        private String corsBaseUrl = "/storage,/storage/*,/metakey/*,/users/*,/bulk/*,/info/*";

        @JsonProperty
        @NotEmpty
        private String preflightMaxAge = "1800";

        @JsonProperty
        @NotNull
        private String exposedHeaders = "etag,Content-length";

        public String getAllowedOrigins() { return allowedOrigins; }

        public void setAllowedOrigins(String allowedOrigins) { this.allowedOrigins = allowedOrigins; }

        public String getAllowedHeaders() { return allowedHeaders; }

        public void setAllowedHeaders(String allowedHeaders) { this.allowedHeaders = allowedHeaders; }

        public String getAllowedMethods() { return allowedMethods; }

        public void setAllowedMethods(String allowedMethods) { this.allowedMethods = allowedMethods; }

        public String getCorsBaseUrl() { return corsBaseUrl; }

        public void setCorsBaseUrl(String corsBaseUrl) { this.corsBaseUrl = corsBaseUrl; }

        public String getPreflightMaxAge() { return preflightMaxAge; }

        public void setPreflightMaxAge(String preflightMaxAge) { this.preflightMaxAge = preflightMaxAge; }

        public String getExposedHeaders() { return exposedHeaders; }

        public void setExposedHeaders(String exposedHeaders) { this.exposedHeaders = exposedHeaders; }

    }

    public Cors getCors() { return cors; }

    public void setCors(Cors cors) { this.cors = cors; }

    public Path getTmpPath() { return tmpPath; }

    public void setTmpPath(Path tmpPath) { this.tmpPath = tmpPath; }

    public int getThreshold() { return threshold; }

    public void setThreshold(int threshold) { this.threshold = threshold; }

    public String getPrivateSalt() { return privateSalt; }

    public void setPrivateSalt(String privateSalt) { this.privateSalt = privateSalt; }

    public int getIterations() { return iterations; }

    public void setIterations(int iterations) { this.iterations = iterations; }

    public boolean isProtectTasks() { return protectTasks; }

    public void setProtectTasks(boolean protectTasks) { this.protectTasks = protectTasks; }

}
