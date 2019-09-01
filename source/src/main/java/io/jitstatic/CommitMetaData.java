package io.jitstatic;

import java.time.Instant;

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

import java.util.Objects;

public class CommitMetaData {
    private final String userInfo;
    private final String userMail;
    private final String message;
    private final String proxyUser;
    private final String proxyUserMail;
    private final Instant timeStamp;

    public CommitMetaData(final String userInfo, final String userMail, final String message, final String proxyUser, final String proxyUserMail) {
        this.userInfo = checkNonNullNotEmpty(userInfo, "userInfo");
        this.userMail = checkNonNullNotEmpty(userMail, "userMail");
        this.message = checkNonNullNotEmpty(message, "message");
        this.proxyUser = checkNonNullNotEmpty(proxyUser, "proxyUser");
        this.proxyUserMail = (proxyUserMail != null) ? proxyUserMail : JitStaticConstants.JITSTATIC_NOWHERE;
        this.timeStamp = Instant.now();
    }

    private static String checkNonNullNotEmpty(final String victim, final String field) {
        if (Objects.requireNonNull(victim, field).isEmpty()) {
            throw new IllegalArgumentException(field + " cannot be empty");
        }
        return victim;
    }

    public String getUserInfo() {
        return userInfo;
    }

    public String getUserMail() {
        return userMail;
    }

    public String getMessage() {
        return message;
    }

    public String getProxyUser() {
        return proxyUser;
    }

    public String getProxyUserMail() {
        return proxyUserMail;
    }

    public Instant getTimeStamp() {
        return timeStamp;
    }
}
