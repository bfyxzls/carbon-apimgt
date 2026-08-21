/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.com).
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
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.apimgt.gateway.utils;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-TTL in-memory cache for southbound MCP 1.0 session IDs when the gateway
 * holds a legacy backend session on behalf of a modern (2.0) northbound client.
 */
public final class MCPSessionCache {

    private static final long DEFAULT_TTL_MS = 5 * 60 * 1000L;
    private static final MCPSessionCache INSTANCE = new MCPSessionCache(DEFAULT_TTL_MS);

    private final long ttlMs;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    MCPSessionCache(long ttlMs) {
        this.ttlMs = ttlMs;
    }

    public static MCPSessionCache getInstance() {
        return INSTANCE;
    }

    public void put(String key, String sessionId) {
        if (key == null || sessionId == null) {
            return;
        }
        cache.put(key, new CacheEntry(sessionId, System.currentTimeMillis() + ttlMs));
        evictExpired();
    }

    public String get(String key) {
        if (key == null) {
            return null;
        }
        CacheEntry entry = cache.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.expiresAtMs < System.currentTimeMillis()) {
            cache.remove(key, entry);
            return null;
        }
        return entry.sessionId;
    }

    public void invalidate(String key) {
        if (key != null) {
            cache.remove(key);
        }
    }

    private void evictExpired() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, CacheEntry>> it = cache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, CacheEntry> entry = it.next();
            if (entry.getValue().expiresAtMs < now) {
                it.remove();
            }
        }
    }

    private static final class CacheEntry {
        private final String sessionId;
        private final long expiresAtMs;

        private CacheEntry(String sessionId, long expiresAtMs) {
            this.sessionId = sessionId;
            this.expiresAtMs = expiresAtMs;
        }
    }
}
