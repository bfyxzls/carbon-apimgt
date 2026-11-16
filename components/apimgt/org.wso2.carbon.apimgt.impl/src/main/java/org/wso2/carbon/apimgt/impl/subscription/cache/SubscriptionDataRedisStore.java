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
import redis.clients.jedis.params.SetParams;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Redis-backed store for subscription validation bulk data (applications, subscriptions, key mappings).
 */
public class SubscriptionDataRedisStore {

    private static final Log log = LogFactory.getLog(SubscriptionDataRedisStore.class);
    private static final Gson GSON = new Gson();
    // Jedis 3.x SetParams.ex(...) takes int seconds.
    private static final int LOCK_TTL_SECONDS = 120;

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
            String ready = jedis.hget(SubscriptionCacheKeys.metaKey(organization), SubscriptionCacheKeys.META_READY);
            return Boolean.parseBoolean(ready);
        } catch (Exception e) {
            log.warn("Failed to read subscription cache warm flag for org " + organization, e);
            return false;
        }
    }

    public void markWarm(String organization) {
        try (Jedis jedis = jedisPool.getResource()) {
            Map<String, String> meta = new HashMap<>();
            meta.put(SubscriptionCacheKeys.META_READY, "true");
            meta.put(SubscriptionCacheKeys.META_WARMED_AT, String.valueOf(System.currentTimeMillis()));
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

    public boolean tryLock(String organization) {
        try (Jedis jedis = jedisPool.getResource()) {
            String result = jedis.set(SubscriptionCacheKeys.lockKey(organization), "1",
                    SetParams.setParams().nx().ex(LOCK_TTL_SECONDS));
            return "OK".equalsIgnoreCase(result);
        } catch (Exception e) {
            log.warn("Failed to acquire subscription cache lock for org " + organization, e);
            return false;
        }
    }

    public void unlock(String organization) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del(SubscriptionCacheKeys.lockKey(organization));
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

    public void putAllApplications(String organization, List<Application> applications) {
        Map<String, String> map = new HashMap<>();
        if (applications != null) {
            for (Application application : applications) {
                map.put(String.valueOf(application.getId()), toGatewayCompatibleApplicationJson(application));
            }
        }
        replaceHash(SubscriptionCacheKeys.applicationsKey(organization), map);
    }

    public void putAllSubscriptions(String organization, List<Subscription> subscriptions) {
        Map<String, String> map = new HashMap<>();
        if (subscriptions != null) {
            for (Subscription subscription : subscriptions) {
                map.put(SubscriptionCacheKeys.subscriptionField(subscription.getAppId(), subscription.getApiId()),
                        GSON.toJson(subscription));
            }
        }
        replaceHash(SubscriptionCacheKeys.subscriptionsKey(organization), map);
    }

    public void putAllKeyMappings(String organization, List<ApplicationKeyMapping> keyMappings) {
        Map<String, String> map = new HashMap<>();
        if (keyMappings != null) {
            for (ApplicationKeyMapping mapping : keyMappings) {
                map.put(SubscriptionCacheKeys.keyMappingField(mapping.getConsumerKey(), mapping.getKeyManager()),
                        GSON.toJson(mapping));
            }
        }
        replaceHash(SubscriptionCacheKeys.keyMappingsKey(organization), map);
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

    private void replaceHash(String key, Map<String, String> map) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del(key);
            if (map != null && !map.isEmpty()) {
                jedis.hset(key, map);
            }
        } catch (Exception e) {
            log.error("Failed to replace redis hash " + key, e);
            throw e;
        }
    }

    private <T> List<T> getHashValues(String key, Class<T> type) {
        List<T> result = new ArrayList<>();
        for (String value : getRawHashValues(key)) {
            result.add(GSON.fromJson(value, type));
        }
        return result;
    }

    private List<String> getRawHashValues(String key) {
        List<String> values = new ArrayList<>();
        try (Jedis jedis = jedisPool.getResource()) {
            Map<String, String> all = jedis.hgetAll(key);
            if (all != null) {
                values.addAll(all.values());
            }
        } catch (Exception e) {
            log.error("Failed to read redis hash " + key, e);
        }
        return values;
    }
}
