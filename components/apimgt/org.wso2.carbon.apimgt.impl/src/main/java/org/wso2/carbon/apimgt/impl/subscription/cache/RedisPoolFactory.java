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

import org.apache.commons.lang3.StringUtils;
import org.wso2.carbon.apimgt.impl.dto.RedisConfig;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

/**
 * Builds a {@link JedisPool} from {@link RedisConfig}.
 */
public final class RedisPoolFactory {

    private RedisPoolFactory() {
    }

    /**
     * Socket read timeout for large HSCAN/HSET workloads. connectionTimeout alone (often 5s)
     * is too short for multi-million-field subscription hashes.
     */
    private static final int DEFAULT_SO_TIMEOUT_MS = 120000;

    public static JedisPool create(RedisConfig redisConfig) {
        JedisPoolConfig jedisPoolConfig = new JedisPoolConfig();
        jedisPoolConfig.setMaxTotal(redisConfig.getMaxTotal());
        jedisPoolConfig.setMaxIdle(redisConfig.getMaxIdle());
        jedisPoolConfig.setMinIdle(redisConfig.getMinIdle());
        jedisPoolConfig.setTestOnBorrow(redisConfig.isTestOnBorrow());
        jedisPoolConfig.setBlockWhenExhausted(redisConfig.isBlockWhenExhausted());
        jedisPoolConfig.setMinEvictableIdleTimeMillis(redisConfig.getMinEvictableIdleTimeMillis());
        jedisPoolConfig.setTimeBetweenEvictionRunsMillis(redisConfig.getTimeBetweenEvictionRunsMillis());
        jedisPoolConfig.setNumTestsPerEvictionRun(redisConfig.getNumTestsPerEvictionRun());
        int connectionTimeout = Math.max(redisConfig.getConnectionTimeout(), 1000);
        int soTimeout = Math.max(connectionTimeout, DEFAULT_SO_TIMEOUT_MS);
        String soOverride = System.getProperty("apim.subscription.cache.redis.soTimeoutMs");
        if (StringUtils.isNotBlank(soOverride)) {
            try {
                soTimeout = Math.max(connectionTimeout, Integer.parseInt(soOverride.trim()));
            } catch (NumberFormatException ignored) {
                // keep computed default
            }
        }
        // Jedis 3.x requires clientName when connectionTimeout and soTimeout are both set.
        String clientName = null;
        if (StringUtils.isNotEmpty(redisConfig.getUser()) && redisConfig.getPassword() != null) {
            return new JedisPool(jedisPoolConfig, redisConfig.getHost(), redisConfig.getPort(),
                    connectionTimeout, soTimeout, redisConfig.getUser(),
                    String.valueOf(redisConfig.getPassword()), redisConfig.getDatabaseId(), clientName,
                    redisConfig.isSslEnabled());
        } else if (redisConfig.getPassword() != null) {
            return new JedisPool(jedisPoolConfig, redisConfig.getHost(), redisConfig.getPort(),
                    connectionTimeout, soTimeout, String.valueOf(redisConfig.getPassword()),
                    redisConfig.getDatabaseId(), clientName, redisConfig.isSslEnabled());
        }
        return new JedisPool(jedisPoolConfig, redisConfig.getHost(), redisConfig.getPort(),
                connectionTimeout, soTimeout, null, redisConfig.getDatabaseId(), clientName,
                redisConfig.isSslEnabled());
    }
}
