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

package org.wso2.carbon.apimgt.impl.subscription.cache;

/**
 * Redis key helpers for subscription validation data cache.
 */
public final class SubscriptionCacheKeys {

    public static final String KEY_PREFIX = "apim:subcache:";
    public static final String APPLICATIONS = "applications";
    public static final String SUBSCRIPTIONS = "subscriptions";
    public static final String KEYMAPPINGS = "keymappings";
    public static final String META = "meta";
    public static final String LOCK = "lock";
    public static final String META_READY = "ready";
    public static final String META_WARMED_AT = "warmedAt";

    private SubscriptionCacheKeys() {
    }

    public static String applicationsKey(String organization) {
        return KEY_PREFIX + organization + ":" + APPLICATIONS;
    }

    public static String subscriptionsKey(String organization) {
        return KEY_PREFIX + organization + ":" + SUBSCRIPTIONS;
    }

    public static String keyMappingsKey(String organization) {
        return KEY_PREFIX + organization + ":" + KEYMAPPINGS;
    }

    public static String metaKey(String organization) {
        return KEY_PREFIX + organization + ":" + META;
    }

    public static String lockKey(String organization) {
        return KEY_PREFIX + organization + ":" + LOCK;
    }

    public static String keyMappingField(String consumerKey, String keyManager) {
        return consumerKey + "|" + (keyManager == null ? "" : keyManager);
    }

    public static String subscriptionField(int appId, int apiId) {
        return appId + "." + apiId;
    }
}
