/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.apimgt.impl.userprofile;

import java.util.regex.Pattern;

/**
 * Redis key helpers for slim audit user-profile cache (userId/username/email/phone).
 * <p>
 * One Redis key per user: {@code apim:userprofile:{tenant}:{canonicalUserKey}}.
 * Callers may pass {@code uuid}, {@code uuid@tenant}, {@code {uuid}}, {@code {uuid}@tenant};
 * {@link #canonicalizeUserKey(String)} collapses them to the bare UUID (or tenant-aware name).
 */
public final class UserProfileCacheKeys {

    public static final String KEY_PREFIX = "apim:userprofile:";

    public static final String FIELD_USER_ID = "userId";
    public static final String FIELD_USERNAME = "username";
    public static final String FIELD_EMAIL = "email";
    public static final String FIELD_PHONE = "phone";
    public static final String FIELD_EMPTY = "empty";

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private UserProfileCacheKeys() {
    }

    /**
     * Normalize username forms used by enrichment to a single cache id.
     * Prefer bare UUID when the input is UUID-shaped (with optional braces / {@code @tenant}).
     */
    public static String canonicalizeUserKey(String userKey) {
        if (userKey == null) {
            return "";
        }
        String candidate = userKey.trim();
        if (candidate.isEmpty()) {
            return "";
        }
        if (candidate.startsWith("{") && candidate.endsWith("}") && candidate.length() > 2) {
            candidate = candidate.substring(1, candidate.length() - 1).trim();
        }
        int at = candidate.indexOf('@');
        if (at > 0) {
            candidate = candidate.substring(0, at).trim();
        }
        if (UUID_PATTERN.matcher(candidate).matches()) {
            return candidate;
        }
        // Non-UUID local users: keep original tenant-aware form without braces.
        String fallback = userKey.trim();
        if (fallback.startsWith("{") && fallback.endsWith("}") && fallback.length() > 2) {
            fallback = fallback.substring(1, fallback.length() - 1).trim();
        }
        return fallback;
    }

    public static String profileKey(String tenantDomain, String userKey) {
        String tenant = tenantDomain == null || tenantDomain.isEmpty() ? "carbon.super" : tenantDomain;
        return KEY_PREFIX + tenant + ":" + canonicalizeUserKey(userKey);
    }
}
