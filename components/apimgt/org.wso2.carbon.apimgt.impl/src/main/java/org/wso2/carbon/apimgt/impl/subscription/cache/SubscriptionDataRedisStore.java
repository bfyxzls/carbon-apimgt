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

import com.google.gson.Gson;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.apimgt.api.model.subscription.Application;
import org.wso2.carbon.apimgt.api.model.subscription.ApplicationKeyMapping;
import org.wso2.carbon.apimgt.api.model.subscription.Subscription;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.ScanParams;
import redis.clients.jedis.ScanResult;
import redis.clients.jedis.params.SetParams;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Redis-backed store for subscription validation bulk data (applications, subscriptions, key mappings).
 */
public class SubscriptionDataRedisStore {

    private static final Log log = LogFactory.getLog(SubscriptionDataRedisStore.class);
    private static final Gson GSON = new Gson();
    /**
     * Base TTL for the warm lock. Renewed periodically while warm is in progress so large DB loads
     * can exceed this without another node stealing the lock.
     */
    public static final int LOCK_TTL_SECONDS = 900;
    /** Suggested renew cadence; keep well below {@link #LOCK_TTL_SECONDS}. */
    public static final int LOCK_RENEW_INTERVAL_SECONDS = 60;
    // Jedis 3.x SetParams.ex(...) takes int seconds.
    private static final String RENEW_LOCK_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('expire', KEYS[1], ARGV[2]) else return 0 end";
    private static final String UNLOCK_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";

    private final JedisPool jedisPool;

    public SubscriptionDataRedisStore(JedisPool jedisPool) {
        this.jedisPool = jedisPool;
    }

    public boolean ping() {
        try (Jedis jedis = jedisPool.getResource()) {
            return "PONG".equalsIgnoreCase(jedis.ping());
        } catch (Exception e) {
            log.warn("Redis ping failed for subscription cache", e);
            return false;
        }
    }

    public boolean isWarm(String organization) {
        try (Jedis jedis = jedisPool.getResource()) {
            String metaKey = SubscriptionCacheKeys.metaKey(organization);
            String ready = jedis.hget(metaKey, SubscriptionCacheKeys.META_READY);
            if (!Boolean.parseBoolean(ready)) {
                return false;
            }
            // Guard against dirty state: ready=true but business HASH keys were never written / were deleted.
            if (!hasBusinessKeys(jedis, organization)) {
                log.warn("Subscription cache dirty for org " + organization
                        + ": meta.ready=true but applications/subscriptions/keymappings keys missing;"
                        + " clearing ready so warm can rebuild");
                jedis.hset(metaKey, SubscriptionCacheKeys.META_READY, "false");
                return false;
            }
            // Guard: ready=true but all three HASHes only have __empty__ (false empty warm / failed DB load).
            // Intentional empty installs must set meta.emptyWarm=true via allow-empty warm.
            if (isPlaceholderOnlyBusinessCache(jedis, organization)
                    && !Boolean.parseBoolean(jedis.hget(metaKey, SubscriptionCacheKeys.META_EMPTY_WARM))) {
                log.warn("Subscription cache dirty for org " + organization
                        + ": meta.ready=true but business hashes are placeholder-only (__empty__);"
                        + " clearing ready so warm can rebuild");
                jedis.hset(metaKey, SubscriptionCacheKeys.META_READY, "false");
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("Failed to read subscription cache warm flag for org " + organization, e);
            return false;
        }
    }

    /**
     * True when the three warm payload HASH keys exist (may contain only the empty placeholder).
     */
    public boolean hasBusinessKeys(String organization) {
        try (Jedis jedis = jedisPool.getResource()) {
            return hasBusinessKeys(jedis, organization);
        } catch (Exception e) {
            log.warn("Failed to check subscription cache business keys for org " + organization, e);
            return false;
        }
    }

    private static boolean hasBusinessKeys(Jedis jedis, String organization) {
        return Boolean.TRUE.equals(jedis.exists(SubscriptionCacheKeys.applicationsKey(organization)))
                && Boolean.TRUE.equals(jedis.exists(SubscriptionCacheKeys.subscriptionsKey(organization)))
                && Boolean.TRUE.equals(jedis.exists(SubscriptionCacheKeys.keyMappingsKey(organization)));
    }

    private static boolean isPlaceholderOnlyBusinessCache(Jedis jedis, String organization) {
        return isPlaceholderOnlyHash(jedis, SubscriptionCacheKeys.applicationsKey(organization))
                && isPlaceholderOnlyHash(jedis, SubscriptionCacheKeys.subscriptionsKey(organization))
                && isPlaceholderOnlyHash(jedis, SubscriptionCacheKeys.keyMappingsKey(organization));
    }

    private static boolean isPlaceholderOnlyHash(Jedis jedis, String key) {
        // Avoid HGETALL on multi-million-field hashes (would timeout / OOM).
        Long len = jedis.hlen(key);
        if (len == null || len == 0L) {
            return true;
        }
        if (len != 1L) {
            return false;
        }
        return Boolean.TRUE.equals(jedis.hexists(key, SubscriptionCacheKeys.EMPTY_HASH_PLACEHOLDER_FIELD));
    }

    public void markWarm(String organization) {
        markWarm(organization, -1, -1, -1, false);
    }

    /**
     * Mark warm complete. When all dataset counts are 0, {@code emptyWarmAllowed} must be true or this is a no-op
     * (refuses ready=true) so a failed/empty DB load cannot stick forever behind {@code __empty__} placeholders.
     */
    public void markWarm(String organization, int appCount, int subscriptionCount, int keyMappingCount,
            boolean emptyWarmAllowed) {
        try (Jedis jedis = jedisPool.getResource()) {
            if (!hasBusinessKeys(jedis, organization)) {
                log.error("Refusing to markWarm for org " + organization
                        + ": business HASH keys are missing after Redis write");
                return;
            }
            boolean allEmpty = appCount == 0 && subscriptionCount == 0 && keyMappingCount == 0;
            if (allEmpty && !emptyWarmAllowed) {
                log.error("Refusing to markWarm for org " + organization
                        + ": apps/subscriptions/keyMappings all count=0 (likely DB load failure)."
                        + " Set -Dapim.subscription.cache.warm.allow.empty=true only if empty is intentional.");
                jedis.hset(SubscriptionCacheKeys.metaKey(organization), SubscriptionCacheKeys.META_READY, "false");
                return;
            }
            Map<String, String> meta = new HashMap<>();
            meta.put(SubscriptionCacheKeys.META_READY, "true");
            meta.put(SubscriptionCacheKeys.META_WARMED_AT, String.valueOf(System.currentTimeMillis()));
            if (appCount >= 0) {
                meta.put(SubscriptionCacheKeys.META_APP_COUNT, String.valueOf(appCount));
            }
            if (subscriptionCount >= 0) {
                meta.put(SubscriptionCacheKeys.META_SUBSCRIPTION_COUNT, String.valueOf(subscriptionCount));
            }
            if (keyMappingCount >= 0) {
                meta.put(SubscriptionCacheKeys.META_KEY_MAPPING_COUNT, String.valueOf(keyMappingCount));
            }
            meta.put(SubscriptionCacheKeys.META_EMPTY_WARM, allEmpty && emptyWarmAllowed ? "true" : "false");
            jedis.hset(SubscriptionCacheKeys.metaKey(organization), meta);
        } catch (Exception e) {
            log.error("Failed to mark subscription cache warm for org " + organization, e);
        }
    }

    public void clearWarm(String organization) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.hset(SubscriptionCacheKeys.metaKey(organization), SubscriptionCacheKeys.META_READY, "false");
        } catch (Exception e) {
            log.warn("Failed to clear subscription cache warm flag for org " + organization, e);
        }
    }

    /**
     * Acquire the warm lock with a unique ownership token.
     * Token format is human-readable for ops: {@code podName@podIP#shortId}
     * (shortId keeps Lua unlock/renew safe if the same pod retries).
     *
     * @return lock token when acquired, otherwise {@code null}
     */
    public String tryLock(String organization) {
        String token = buildLockOwnerToken();
        try (Jedis jedis = jedisPool.getResource()) {
            String lockKey = SubscriptionCacheKeys.lockKey(organization);
            String result = jedis.set(lockKey, token, SetParams.setParams().nx().ex(LOCK_TTL_SECONDS));
            if ("OK".equalsIgnoreCase(result)) {
                log.info("Acquired subscription cache lock key=" + lockKey + " owner=" + token
                        + " ttlSeconds=" + LOCK_TTL_SECONDS);
                return token;
            }
            String currentOwner = null;
            try {
                currentOwner = jedis.get(lockKey);
            } catch (Exception ignored) {
                // best-effort only
            }
            log.info("Subscription cache lock busy for org " + organization
                    + " currentOwner=" + currentOwner);
            return null;
        } catch (Exception e) {
            log.warn("Failed to acquire subscription cache lock for org " + organization, e);
            return null;
        }
    }

    /**
     * Build lock value from Kubernetes identity when available.
     * Prefers {@code POD_NAME} / {@code NODE_IP}; falls back to {@code HOSTNAME}.
     */
    static String buildLockOwnerToken() {
        String pod = firstNonBlank(System.getenv("POD_NAME"), System.getenv("HOSTNAME"), "unknown-pod");
        String ip = firstNonBlank(System.getenv("NODE_IP"), "unknown-ip");
        String shortId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return pod + "@" + ip + "#" + shortId;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }

    /**
     * Extend lock TTL only if this node still owns the lock ({@code token} matches).
     *
     * @return true when renew succeeded
     */
    public boolean renewLock(String organization, String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        try (Jedis jedis = jedisPool.getResource()) {
            Object result = jedis.eval(RENEW_LOCK_SCRIPT,
                    Collections.singletonList(SubscriptionCacheKeys.lockKey(organization)),
                    Arrays.asList(token, String.valueOf(LOCK_TTL_SECONDS)));
            return result instanceof Long && ((Long) result) == 1L;
        } catch (Exception e) {
            log.warn("Failed to renew subscription cache lock for org " + organization, e);
            return false;
        }
    }

    /**
     * Release the warm lock only if this node still owns it.
     */
    public void unlock(String organization, String token) {
        if (token == null || token.isEmpty()) {
            return;
        }
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.eval(UNLOCK_SCRIPT,
                    Collections.singletonList(SubscriptionCacheKeys.lockKey(organization)),
                    Collections.singletonList(token));
        } catch (Exception e) {
            log.warn("Failed to release subscription cache lock for org " + organization, e);
        }
    }

    public List<Application> getApplications(String organization) {
        return getHashValues(SubscriptionCacheKeys.applicationsKey(organization), Application.class);
    }

    public List<Subscription> getSubscriptions(String organization) {
        return getHashValues(SubscriptionCacheKeys.subscriptionsKey(organization), Subscription.class);
    }

    public List<ApplicationKeyMapping> getKeyMappings(String organization) {
        return getHashValues(SubscriptionCacheKeys.keyMappingsKey(organization), ApplicationKeyMapping.class);
    }

    public List<String> getApplicationJsonValues(String organization) {
        return getRawHashValues(SubscriptionCacheKeys.applicationsKey(organization));
    }

    public List<String> getSubscriptionJsonValues(String organization) {
        return getRawHashValues(SubscriptionCacheKeys.subscriptionsKey(organization));
    }

    public List<String> getKeyMappingJsonValues(String organization) {
        return getRawHashValues(SubscriptionCacheKeys.keyMappingsKey(organization));
    }

    /**
     * Point lookup: single application JSON from Redis (HGET). Returns null if missing or placeholder-only field.
     */
    public String getApplicationJson(String organization, int applicationId) {
        return hgetBusinessField(SubscriptionCacheKeys.applicationsKey(organization), String.valueOf(applicationId));
    }

    /**
     * Point lookup: single subscription JSON from Redis (HGET).
     */
    public String getSubscriptionJson(String organization, int appId, int apiId) {
        return hgetBusinessField(SubscriptionCacheKeys.subscriptionsKey(organization),
                SubscriptionCacheKeys.subscriptionField(appId, apiId));
    }

    /**
     * Point lookup: single key mapping JSON from Redis (HGET).
     */
    public String getKeyMappingJson(String organization, String consumerKey, String keyManager) {
        return hgetBusinessField(SubscriptionCacheKeys.keyMappingsKey(organization),
                SubscriptionCacheKeys.keyMappingField(consumerKey, keyManager));
    }

    private String hgetBusinessField(String hashKey, String field) {
        if (field == null || SubscriptionCacheKeys.EMPTY_HASH_PLACEHOLDER_FIELD.equals(field)) {
            return null;
        }
        try (Jedis jedis = jedisPool.getResource()) {
            String value = jedis.hget(hashKey, field);
            if (value == null || SubscriptionCacheKeys.EMPTY_HASH_PLACEHOLDER_VALUE.equals(value)) {
                return null;
            }
            return value;
        } catch (Exception e) {
            log.warn("Redis HGET failed for hash=" + hashKey + " field=" + field, e);
            return null;
        }
    }

    public void putAllApplications(String organization, List<Application> applications) {
        String redisKey = SubscriptionCacheKeys.applicationsKey(organization);
        long started = System.currentTimeMillis();
        log.info("Redis write prepare [" + redisKey + "]: serializing applications count="
                + (applications == null ? 0 : applications.size()));
        Map<String, String> map = new HashMap<>();
        if (applications != null) {
            for (Application application : applications) {
                map.put(String.valueOf(application.getId()), toGatewayCompatibleApplicationJson(application));
            }
        }
        log.info("Redis write prepare [" + redisKey + "]: serialized fields=" + map.size()
                + " approxBytes=" + approxPayloadBytes(map)
                + " serializeMs=" + (System.currentTimeMillis() - started));
        replaceHash(redisKey, map);
    }

    public void putAllSubscriptions(String organization, List<Subscription> subscriptions) {
        String redisKey = SubscriptionCacheKeys.subscriptionsKey(organization);
        long started = System.currentTimeMillis();
        log.info("Redis write prepare [" + redisKey + "]: serializing subscriptions count="
                + (subscriptions == null ? 0 : subscriptions.size()));
        Map<String, String> map = toSubscriptionHashMap(subscriptions);
        log.info("Redis write prepare [" + redisKey + "]: serialized fields=" + map.size()
                + " approxBytes=" + approxPayloadBytes(map)
                + " serializeMs=" + (System.currentTimeMillis() - started));
        replaceHash(redisKey, map);
    }

    /**
     * Start a streamed subscriptions replace: delete existing HASH so subsequent
     * {@link #appendSubscriptions(String, List)} calls rebuild it page by page.
     */
    public void beginSubscriptionsReplace(String organization) {
        String redisKey = SubscriptionCacheKeys.subscriptionsKey(organization);
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del(redisKey);
            log.info("Redis subscriptions replace begin [" + redisKey + "]: deleted existing key");
        } catch (Exception e) {
            log.error("Failed to begin subscriptions replace for " + redisKey, e);
            throw e;
        }
    }

    /**
     * Append one page of subscriptions to the HASH without deleting previous pages.
     */
    public void appendSubscriptions(String organization, List<Subscription> subscriptions) {
        if (subscriptions == null || subscriptions.isEmpty()) {
            return;
        }
        String redisKey = SubscriptionCacheKeys.subscriptionsKey(organization);
        long started = System.currentTimeMillis();
        Map<String, String> map = toSubscriptionHashMap(subscriptions);
        log.info("Redis subscriptions append [" + redisKey + "]: fields=" + map.size()
                + " approxBytes=" + approxPayloadBytes(map));
        hsetChunked(redisKey, map);
        log.info("Redis subscriptions append done [" + redisKey + "]: fields=" + map.size()
                + " hsetMs=" + (System.currentTimeMillis() - started));
    }

    /**
     * After streamed pages: if nothing was written, keep an empty placeholder so the key EXISTS
     * for {@link #hasBusinessKeys(String)} / {@link #markWarm(String)}.
     */
    public void finishSubscriptionsReplace(String organization, int totalWritten) {
        String redisKey = SubscriptionCacheKeys.subscriptionsKey(organization);
        try (Jedis jedis = jedisPool.getResource()) {
            if (totalWritten <= 0 || !Boolean.TRUE.equals(jedis.exists(redisKey))) {
                jedis.hset(redisKey, SubscriptionCacheKeys.EMPTY_HASH_PLACEHOLDER_FIELD,
                        SubscriptionCacheKeys.EMPTY_HASH_PLACEHOLDER_VALUE);
                log.warn("Redis subscriptions replace finish [" + redisKey
                        + "]: wrote empty placeholder (totalWritten=" + totalWritten + ")");
            } else {
                log.info("Redis subscriptions replace finish [" + redisKey + "]: totalWritten=" + totalWritten);
            }
        } catch (Exception e) {
            log.error("Failed to finish subscriptions replace for " + redisKey, e);
            throw e;
        }
    }

    private static Map<String, String> toSubscriptionHashMap(List<Subscription> subscriptions) {
        Map<String, String> map = new HashMap<>();
        if (subscriptions != null) {
            for (Subscription subscription : subscriptions) {
                map.put(SubscriptionCacheKeys.subscriptionField(subscription.getAppId(), subscription.getApiId()),
                        GSON.toJson(subscription));
            }
        }
        return map;
    }

    public void putAllKeyMappings(String organization, List<ApplicationKeyMapping> keyMappings) {
        String redisKey = SubscriptionCacheKeys.keyMappingsKey(organization);
        long started = System.currentTimeMillis();
        log.info("Redis write prepare [" + redisKey + "]: serializing keyMappings count="
                + (keyMappings == null ? 0 : keyMappings.size()));
        Map<String, String> map = toKeyMappingHashMap(keyMappings);
        log.info("Redis write prepare [" + redisKey + "]: serialized fields=" + map.size()
                + " approxBytes=" + approxPayloadBytes(map)
                + " serializeMs=" + (System.currentTimeMillis() - started));
        replaceHash(redisKey, map);
    }

    /**
     * Start a streamed keyMappings replace: delete existing HASH so subsequent
     * {@link #appendKeyMappings(String, List)} calls rebuild it page by page.
     */
    public void beginKeyMappingsReplace(String organization) {
        String redisKey = SubscriptionCacheKeys.keyMappingsKey(organization);
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del(redisKey);
            log.info("Redis keyMappings replace begin [" + redisKey + "]: deleted existing key");
        } catch (Exception e) {
            log.error("Failed to begin keyMappings replace for " + redisKey, e);
            throw e;
        }
    }

    /**
     * Append one page of keyMappings to the HASH without deleting previous pages.
     */
    public void appendKeyMappings(String organization, List<ApplicationKeyMapping> keyMappings) {
        if (keyMappings == null || keyMappings.isEmpty()) {
            return;
        }
        String redisKey = SubscriptionCacheKeys.keyMappingsKey(organization);
        long started = System.currentTimeMillis();
        Map<String, String> map = toKeyMappingHashMap(keyMappings);
        log.info("Redis keyMappings append [" + redisKey + "]: fields=" + map.size()
                + " approxBytes=" + approxPayloadBytes(map));
        hsetChunked(redisKey, map);
        log.info("Redis keyMappings append done [" + redisKey + "]: fields=" + map.size()
                + " hsetMs=" + (System.currentTimeMillis() - started));
    }

    /**
     * After streamed pages: if nothing was written, keep an empty placeholder so the key EXISTS.
     */
    public void finishKeyMappingsReplace(String organization, int totalWritten) {
        String redisKey = SubscriptionCacheKeys.keyMappingsKey(organization);
        try (Jedis jedis = jedisPool.getResource()) {
            if (totalWritten <= 0 || !Boolean.TRUE.equals(jedis.exists(redisKey))) {
                jedis.hset(redisKey, SubscriptionCacheKeys.EMPTY_HASH_PLACEHOLDER_FIELD,
                        SubscriptionCacheKeys.EMPTY_HASH_PLACEHOLDER_VALUE);
                log.warn("Redis keyMappings replace finish [" + redisKey
                        + "]: wrote empty placeholder (totalWritten=" + totalWritten + ")");
            } else {
                log.info("Redis keyMappings replace finish [" + redisKey + "]: totalWritten=" + totalWritten);
            }
        } catch (Exception e) {
            log.error("Failed to finish keyMappings replace for " + redisKey, e);
            throw e;
        }
    }

    private static Map<String, String> toKeyMappingHashMap(List<ApplicationKeyMapping> keyMappings) {
        Map<String, String> map = new HashMap<>();
        if (keyMappings != null) {
            for (ApplicationKeyMapping mapping : keyMappings) {
                map.put(SubscriptionCacheKeys.keyMappingField(mapping.getConsumerKey(), mapping.getKeyManager()),
                        GSON.toJson(mapping));
            }
        }
        return map;
    }

    public void upsertApplication(String organization, Application application) {
        if (!isWarm(organization) || application == null) {
            return;
        }
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.hset(SubscriptionCacheKeys.applicationsKey(organization), String.valueOf(application.getId()),
                    toGatewayCompatibleApplicationJson(application));
        } catch (Exception e) {
            log.error("Failed to upsert application cache for org " + organization, e);
        }
    }

    public void removeApplication(String organization, int applicationId) {
        if (!isWarm(organization)) {
            return;
        }
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.hdel(SubscriptionCacheKeys.applicationsKey(organization), String.valueOf(applicationId));
        } catch (Exception e) {
            log.error("Failed to remove application cache for org " + organization, e);
        }
    }

    public void upsertSubscription(String organization, Subscription subscription) {
        if (!isWarm(organization) || subscription == null) {
            return;
        }
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.hset(SubscriptionCacheKeys.subscriptionsKey(organization),
                    SubscriptionCacheKeys.subscriptionField(subscription.getAppId(), subscription.getApiId()),
                    GSON.toJson(subscription));
        } catch (Exception e) {
            log.error("Failed to upsert subscription cache for org " + organization, e);
        }
    }

    public void removeSubscription(String organization, int appId, int apiId) {
        if (!isWarm(organization)) {
            return;
        }
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.hdel(SubscriptionCacheKeys.subscriptionsKey(organization),
                    SubscriptionCacheKeys.subscriptionField(appId, apiId));
        } catch (Exception e) {
            log.error("Failed to remove subscription cache for org " + organization, e);
        }
    }

    public void upsertKeyMapping(String organization, ApplicationKeyMapping mapping) {
        if (!isWarm(organization) || mapping == null) {
            return;
        }
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.hset(SubscriptionCacheKeys.keyMappingsKey(organization),
                    SubscriptionCacheKeys.keyMappingField(mapping.getConsumerKey(), mapping.getKeyManager()),
                    GSON.toJson(mapping));
        } catch (Exception e) {
            log.error("Failed to upsert key mapping cache for org " + organization, e);
        }
    }

    public void removeKeyMapping(String organization, String consumerKey, String keyManager) {
        if (!isWarm(organization)) {
            return;
        }
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.hdel(SubscriptionCacheKeys.keyMappingsKey(organization),
                    SubscriptionCacheKeys.keyMappingField(consumerKey, keyManager));
        } catch (Exception e) {
            log.error("Failed to remove key mapping cache for org " + organization, e);
        }
    }

    /**
     * Serialize application in a shape compatible with gateway Gson {@code Application} entity
     * (groupIds as list of {groupId, applicationId}).
     */
    private String toGatewayCompatibleApplicationJson(Application application) {
        Map<String, Object> json = new HashMap<>();
        json.put("uuid", application.getUuid());
        json.put("id", application.getId());
        json.put("name", application.getName());
        json.put("subName", application.getSubName());
        json.put("policy", application.getPolicy());
        json.put("tokenType", application.getTokenType());
        json.put("organization", application.getOrganization());
        json.put("attributes", application.getAttributes());
        List<Map<String, Object>> groupIds = new ArrayList<>();
        if (application.getGroupIds() != null) {
            for (String groupId : application.getGroupIds()) {
                Map<String, Object> group = new HashMap<>();
                group.put("groupId", groupId);
                group.put("applicationId", application.getId());
                groupIds.add(group);
            }
        }
        json.put("groupIds", groupIds);
        return GSON.toJson(json);
    }

    /** Avoid a single giant HSET when field count is huge; reduces peak Redis client buffers. */
    private static final int REDIS_HSET_CHUNK_SIZE = 500;

    private void replaceHash(String key, Map<String, String> map) {
        long started = System.currentTimeMillis();
        int fields = map == null ? 0 : map.size();
        long approxBytes = approxPayloadBytes(map);
        log.info("Redis write start [" + key + "]: fields=" + fields + " approxBytes=" + approxBytes
                + " chunkSize=" + REDIS_HSET_CHUNK_SIZE);
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del(key);
            if (map != null && !map.isEmpty()) {
                hsetChunked(jedis, key, map, fields);
                log.info("Redis write done [" + key + "]: writtenFields=" + fields
                        + " hsetMs=" + (System.currentTimeMillis() - started));
            } else {
                // Keep key present so isWarm can distinguish "warm finished with 0 rows" from "never written".
                jedis.hset(key, SubscriptionCacheKeys.EMPTY_HASH_PLACEHOLDER_FIELD,
                        SubscriptionCacheKeys.EMPTY_HASH_PLACEHOLDER_VALUE);
                log.warn("Redis write done [" + key + "]: map empty; wrote placeholder field so key EXISTS");
            }
        } catch (Exception e) {
            log.error("Failed to replace redis hash " + key + " fields=" + fields
                    + " approxBytes=" + approxBytes
                    + " after " + (System.currentTimeMillis() - started) + " ms", e);
            throw e;
        }
    }

    private void hsetChunked(String key, Map<String, String> map) {
        try (Jedis jedis = jedisPool.getResource()) {
            hsetChunked(jedis, key, map, map == null ? 0 : map.size());
        } catch (Exception e) {
            log.error("Failed chunked HSET for redis hash " + key, e);
            throw e;
        }
    }

    private void hsetChunked(Jedis jedis, String key, Map<String, String> map, int totalFields) {
        if (map == null || map.isEmpty()) {
            return;
        }
        Map<String, String> chunk = new HashMap<>(Math.min(REDIS_HSET_CHUNK_SIZE, map.size()));
        int written = 0;
        for (Map.Entry<String, String> entry : map.entrySet()) {
            chunk.put(entry.getKey(), entry.getValue());
            if (chunk.size() >= REDIS_HSET_CHUNK_SIZE) {
                jedis.hset(key, chunk);
                written += chunk.size();
                chunk.clear();
                if (written % (REDIS_HSET_CHUNK_SIZE * 10) == 0) {
                    log.info("Redis write progress [" + key + "]: writtenFields=" + written
                            + "/" + totalFields);
                }
            }
        }
        if (!chunk.isEmpty()) {
            jedis.hset(key, chunk);
        }
    }

    private static long approxPayloadBytes(Map<String, String> map) {
        if (map == null || map.isEmpty()) {
            return 0L;
        }
        long total = 0L;
        for (Map.Entry<String, String> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                total += entry.getKey().length();
            }
            if (entry.getValue() != null) {
                total += entry.getValue().length();
            }
        }
        return total;
    }

    private <T> List<T> getHashValues(String key, Class<T> type) {
        List<T> result = new ArrayList<>();
        for (String value : getRawHashValues(key)) {
            result.add(GSON.fromJson(value, type));
        }
        return result;
    }

    /**
     * Default HSCAN COUNT hint. 2.2M fields ≈ ~110 round-trips at 20k (vs ~1100 at 2k).
     * Redis COUNT is a hint; reply size stays far below full HGETALL.
     * Override: {@code -Dapim.subscription.cache.redis.hscan.count=50000}
     */
    private static final int DEFAULT_REDIS_HSCAN_COUNT = 20000;
    private static final String REDIS_HSCAN_COUNT_PROPERTY = "apim.subscription.cache.redis.hscan.count";

    private static int resolveHscanCount() {
        String raw = System.getProperty(REDIS_HSCAN_COUNT_PROPERTY);
        if (raw == null || raw.trim().isEmpty()) {
            return DEFAULT_REDIS_HSCAN_COUNT;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            if (parsed < 100) {
                return DEFAULT_REDIS_HSCAN_COUNT;
            }
            return parsed;
        } catch (NumberFormatException e) {
            return DEFAULT_REDIS_HSCAN_COUNT;
        }
    }

    private List<String> getRawHashValues(String key) {
        List<String> values = new ArrayList<>();
        long started = System.currentTimeMillis();
        int hscanCount = resolveHscanCount();
        try (Jedis jedis = jedisPool.getResource()) {
            Long hlen = jedis.hlen(key);
            if (hlen != null && hlen > 0) {
                values = new ArrayList<>(Math.min(hlen.intValue(), Integer.MAX_VALUE / 4));
            }
            ScanParams scanParams = new ScanParams().count(hscanCount);
            String cursor = ScanParams.SCAN_POINTER_START;
            int scanned = 0;
            int rounds = 0;
            do {
                ScanResult<Map.Entry<String, String>> scanResult = jedis.hscan(key, cursor, scanParams);
                cursor = scanResult.getCursor();
                rounds++;
                List<Map.Entry<String, String>> page = scanResult.getResult();
                if (page != null) {
                    for (Map.Entry<String, String> entry : page) {
                        if (SubscriptionCacheKeys.EMPTY_HASH_PLACEHOLDER_FIELD.equals(entry.getKey())) {
                            continue;
                        }
                        values.add(entry.getValue());
                        scanned++;
                        if (scanned % 100000 == 0) {
                            log.info("Redis HSCAN progress [" + key + "]: values=" + scanned
                                    + (hlen != null ? "/" + hlen : "")
                                    + " rounds=" + rounds + " countHint=" + hscanCount
                                    + " elapsedMs=" + (System.currentTimeMillis() - started));
                        }
                    }
                }
            } while (!ScanParams.SCAN_POINTER_START.equals(cursor));
            log.info("Redis HSCAN done [" + key + "]: values=" + values.size()
                    + " rounds=" + rounds + " countHint=" + hscanCount
                    + " elapsedMs=" + (System.currentTimeMillis() - started));
        } catch (Exception e) {
            log.error("Failed to read redis hash " + key + " after scanning " + values.size()
                    + " values in " + (System.currentTimeMillis() - started) + " ms", e);
        }
        return values;
    }
}
