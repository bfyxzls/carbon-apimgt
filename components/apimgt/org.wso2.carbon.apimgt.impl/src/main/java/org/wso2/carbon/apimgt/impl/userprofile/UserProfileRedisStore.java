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

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.apimgt.impl.APIConstants;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.HashMap;
import java.util.Map;

/**
 * Redis-backed slim user profile cache for audit enrichment (cross-pod / post-restart).
 * <p>
 * Stores exactly one HASH per user under the canonical key
 * {@code apim:userprofile:{tenant}:{uuid-or-username}}. Read path normalizes
 * {@code uuid}/{@code uuid@tenant}/{@code {uuid}}/{@code {uuid}@tenant} to the same key.
 */
public class UserProfileRedisStore {

    private static final Log log = LogFactory.getLog(UserProfileRedisStore.class);
    private static final int DEFAULT_TTL_SECONDS = 900;
    private static final int NEGATIVE_TTL_SECONDS = 60;

    private final JedisPool jedisPool;
    private final int ttlSeconds;

    public UserProfileRedisStore(JedisPool jedisPool, int ttlSeconds) {
        this.jedisPool = jedisPool;
        this.ttlSeconds = ttlSeconds > 0 ? ttlSeconds : DEFAULT_TTL_SECONDS;
    }

    /**
     * @return slim claim map compatible with enrichment pickers, or {@code null} on miss / Redis error
     */
    public Map<String, String> getProfileClaims(String tenantDomain, String userKey) {
        if (StringUtils.isBlank(userKey)) {
            return null;
        }
        String redisKey = UserProfileCacheKeys.profileKey(tenantDomain, userKey);
        try (Jedis jedis = jedisPool.getResource()) {
            Map<String, String> hash = jedis.hgetAll(redisKey);
            if (hash == null || hash.isEmpty()) {
                return null;
            }
            if ("1".equals(hash.get(UserProfileCacheKeys.FIELD_EMPTY))) {
                return new HashMap<>();
            }
            return toClaimMap(hash);
        } catch (Exception e) {
            log.warn("Failed to read user profile from Redis for key=" + redisKey + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Persist slim profile under a single canonical Redis key.
     */
    public void putProfileClaims(String tenantDomain, String userKey, Map<String, String> claims) {
        if (StringUtils.isBlank(userKey)) {
            return;
        }
        Map<String, String> slim = slimFromClaims(claims);
        // Prefer userid claim as canonical id when present (covers non-uuid login names probing by sub).
        String canonicalInput = userKey;
        String claimUserId = slim.get(UserProfileCacheKeys.FIELD_USER_ID);
        if (StringUtils.isNotBlank(claimUserId)) {
            canonicalInput = claimUserId;
        }
        String redisKey = UserProfileCacheKeys.profileKey(tenantDomain, canonicalInput);
        boolean empty = StringUtils.isBlank(slim.get(UserProfileCacheKeys.FIELD_USER_ID))
                && StringUtils.isBlank(slim.get(UserProfileCacheKeys.FIELD_USERNAME))
                && StringUtils.isBlank(slim.get(UserProfileCacheKeys.FIELD_EMAIL))
                && StringUtils.isBlank(slim.get(UserProfileCacheKeys.FIELD_PHONE));
        int ttl = empty ? NEGATIVE_TTL_SECONDS : ttlSeconds;
        Map<String, String> hash = new HashMap<>();
        if (empty) {
            hash.put(UserProfileCacheKeys.FIELD_EMPTY, "1");
        } else {
            hash.putAll(slim);
            hash.put(UserProfileCacheKeys.FIELD_EMPTY, "0");
        }
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del(redisKey);
            jedis.hset(redisKey, hash);
            jedis.expire(redisKey, ttl);
            if (log.isInfoEnabled()) {
                log.info("Wrote user profile Redis key=" + redisKey
                        + ", empty=" + empty + ", ttlSeconds=" + ttl
                        + ", fields=" + slim.keySet());
            }
        } catch (Exception e) {
            log.warn("Failed to write user profile to Redis for key=" + redisKey + ": " + e.getMessage());
        }
    }

    public static Map<String, String> slimFromClaims(Map<String, String> claims) {
        Map<String, String> slim = new HashMap<>();
        if (claims == null || claims.isEmpty()) {
            return slim;
        }
        String userId = firstClaim(claims,
                "http://wso2.org/claims/userid", "userid", "userId", "sub");
        String username = firstClaim(claims,
                APIConstants.PREFERRED_USERNAME_CLAIM, "username", "http://wso2.org/claims/username",
                APIConstants.NICKNAME_CLAIM, "nickname", "displayName", "http://wso2.org/claims/displayname");
        String email = firstClaim(claims,
                APIConstants.EMAIL_CLAIM, "email", "emailaddress");
        String phone = firstClaim(claims,
                APIConstants.MOBILE_CLAIM, "phoneNumber", "phone_number", "mobile", "phone");
        if (StringUtils.isNotBlank(userId)) {
            slim.put(UserProfileCacheKeys.FIELD_USER_ID, userId.trim());
        }
        if (StringUtils.isNotBlank(username)) {
            slim.put(UserProfileCacheKeys.FIELD_USERNAME, username.trim());
        }
        if (StringUtils.isNotBlank(email)) {
            slim.put(UserProfileCacheKeys.FIELD_EMAIL, email.trim());
        }
        if (StringUtils.isNotBlank(phone)) {
            slim.put(UserProfileCacheKeys.FIELD_PHONE, phone.trim());
        }
        return slim;
    }

    /**
     * Expand slim hash into claim URIs / short names used by enrichment pickers.
     */
    public static Map<String, String> toClaimMap(Map<String, String> hash) {
        Map<String, String> map = new HashMap<>();
        if (hash == null) {
            return map;
        }
        String userId = hash.get(UserProfileCacheKeys.FIELD_USER_ID);
        String username = hash.get(UserProfileCacheKeys.FIELD_USERNAME);
        String email = hash.get(UserProfileCacheKeys.FIELD_EMAIL);
        String phone = hash.get(UserProfileCacheKeys.FIELD_PHONE);
        if (StringUtils.isNotBlank(userId)) {
            map.put("http://wso2.org/claims/userid", userId);
            map.put("userid", userId);
            map.put("userId", userId);
            map.put("sub", userId);
        }
        if (StringUtils.isNotBlank(username)) {
            map.put(APIConstants.PREFERRED_USERNAME_CLAIM, username);
            map.put("username", username);
            map.put("http://wso2.org/claims/username", username);
            map.put("nickname", username);
            map.put(APIConstants.NICKNAME_CLAIM, username);
        }
        if (StringUtils.isNotBlank(email)) {
            map.put(APIConstants.EMAIL_CLAIM, email);
            map.put("email", email);
            map.put("emailaddress", email);
        }
        if (StringUtils.isNotBlank(phone)) {
            map.put(APIConstants.MOBILE_CLAIM, phone);
            map.put("phoneNumber", phone);
            map.put("phone_number", phone);
            map.put("mobile", phone);
            map.put("phone", phone);
        }
        return map;
    }

    private static String firstClaim(Map<String, String> claims, String... keys) {
        for (String key : keys) {
            if (key == null) {
                continue;
            }
            String value = claims.get(key);
            if (StringUtils.isBlank(value)) {
                for (Map.Entry<String, String> entry : claims.entrySet()) {
                    if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key)
                            && StringUtils.isNotBlank(entry.getValue())) {
                        return entry.getValue();
                    }
                }
            } else {
                return value;
            }
        }
        return null;
    }
}
