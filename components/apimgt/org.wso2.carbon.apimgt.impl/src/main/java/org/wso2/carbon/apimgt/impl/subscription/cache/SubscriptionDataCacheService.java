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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.model.subscription.Application;
import org.wso2.carbon.apimgt.api.model.subscription.ApplicationKeyMapping;
import org.wso2.carbon.apimgt.api.model.subscription.Subscription;
import org.wso2.carbon.apimgt.impl.dao.SubscriptionValidationDAO;
import org.wso2.carbon.apimgt.impl.dto.RedisConfig;
import org.wso2.carbon.apimgt.impl.internal.ServiceReferenceHolder;
import org.wso2.carbon.utils.multitenancy.MultitenantConstants;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Control-plane helper for reading/writing subscription validation data via Redis with DB fallback.
 */
public class SubscriptionDataCacheService {

    private static final Log log = LogFactory.getLog(SubscriptionDataCacheService.class);
    private static final SubscriptionDataCacheService INSTANCE = new SubscriptionDataCacheService();
    private final ConcurrentHashMap<String, ReentrantLock> localLocks = new ConcurrentHashMap<>();

    private SubscriptionDataCacheService() {
    }

    public static SubscriptionDataCacheService getInstance() {
        return INSTANCE;
    }

    public boolean isEnabled() {
        RedisConfig redisConfig = getRedisConfig();
        if (redisConfig == null || !redisConfig.isRedisEnabled()) {
            return false;
        }
        boolean flag = redisConfig.isSubscriptionDataCacheEnabled()
                || Boolean.parseBoolean(System.getProperty("apim.subscription.data.cache.enabled", "false"));
        return flag && ServiceReferenceHolder.getInstance().getSubscriptionDataRedisStore() != null;
    }

    public SubscriptionDataRedisStore getStore() {
        return ServiceReferenceHolder.getInstance().getSubscriptionDataRedisStore();
    }

    public List<Application> getApplicationsByOrganization(String organization) {
        if (!isEnabled()) {
            return new SubscriptionValidationDAO().getAllApplications(organization);
        }
        SubscriptionDataRedisStore store = getStore();
        if (store.isWarm(organization)) {
            return store.getApplications(organization);
        }
        return warmAndGetApplications(organization);
    }

    public List<Subscription> getSubscriptionsByOrganization(String organization) throws APIManagementException {
        if (!isEnabled()) {
            return new SubscriptionValidationDAO().getAllSubscriptionsByOrganization(organization);
        }
        SubscriptionDataRedisStore store = getStore();
        if (store.isWarm(organization)) {
            return store.getSubscriptions(organization);
        }
        warmOrganization(organization);
        return store.getSubscriptions(organization);
    }

    public List<ApplicationKeyMapping> getKeyMappingsByOrganization(String organization) {
        if (!isEnabled()) {
            return new SubscriptionValidationDAO().getAllApplicationKeyMappingsByOrganization(organization);
        }
        SubscriptionDataRedisStore store = getStore();
        if (store.isWarm(organization)) {
            return store.getKeyMappings(organization);
        }
        warmOrganization(organization);
        return store.getKeyMappings(organization);
    }

    public void warmDefaultOrganizations() {
        List<String> orgs = getWarmOrganizations();
        for (String org : orgs) {
            try {
                warmOrganization(org);
            } catch (Exception e) {
                log.error("Failed to warm subscription cache for organization " + org, e);
            }
        }
    }

    public boolean isCacheHealthy() {
        if (!isEnabled()) {
            return true;
        }
        SubscriptionDataRedisStore store = getStore();
        if (store == null || !store.ping()) {
            return false;
        }
        for (String org : getWarmOrganizations()) {
            if (!store.isWarm(org)) {
                return false;
            }
        }
        return true;
    }

    public void warmOrganization(String organization) {
        if (!isEnabled()) {
            return;
        }
        SubscriptionDataRedisStore store = getStore();
        if (store.isWarm(organization)) {
            return;
        }
        ReentrantLock localLock = localLocks.computeIfAbsent(organization, key -> new ReentrantLock());
        localLock.lock();
        try {
            if (store.isWarm(organization)) {
                return;
            }
            boolean locked = store.tryLock(organization);
            if (!locked) {
                // Another node is warming; wait briefly for ready flag.
                waitForWarm(organization, 60_000L);
                return;
            }
            try {
                if (store.isWarm(organization)) {
                    return;
                }
                SubscriptionValidationDAO dao = new SubscriptionValidationDAO();
                List<Application> applications = dao.getAllApplications(organization);
                List<Subscription> subscriptions;
                try {
                    subscriptions = dao.getAllSubscriptionsByOrganization(organization);
                } catch (APIManagementException e) {
                    throw new RuntimeException("Failed loading subscriptions for org " + organization, e);
                }
                List<ApplicationKeyMapping> keyMappings = dao.getAllApplicationKeyMappingsByOrganization(organization);
                store.putAllApplications(organization, applications);
                store.putAllSubscriptions(organization, subscriptions);
                store.putAllKeyMappings(organization, keyMappings);
                store.markWarm(organization);
                log.info("Warmed subscription Redis cache for organization " + organization
                        + " apps=" + applications.size()
                        + " subscriptions=" + subscriptions.size()
                        + " keyMappings=" + keyMappings.size());
            } finally {
                store.unlock(organization);
            }
        } finally {
            localLock.unlock();
        }
    }

    private List<Application> warmAndGetApplications(String organization) {
        warmOrganization(organization);
        return getStore().getApplications(organization);
    }

    private void waitForWarm(String organization, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        SubscriptionDataRedisStore store = getStore();
        while (System.currentTimeMillis() < deadline) {
            if (store.isWarm(organization)) {
                return;
            }
            try {
                Thread.sleep(500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.warn("Timed out waiting for subscription cache warm for org " + organization);
    }

    private List<String> getWarmOrganizations() {
        RedisConfig redisConfig = getRedisConfig();
        List<String> orgs = new ArrayList<>();
        if (redisConfig != null && redisConfig.getSubscriptionCacheOrganizations() != null
                && !redisConfig.getSubscriptionCacheOrganizations().isEmpty()) {
            orgs.addAll(redisConfig.getSubscriptionCacheOrganizations());
        } else {
            orgs.add(MultitenantConstants.SUPER_TENANT_DOMAIN_NAME);
        }
        return orgs;
    }

    private RedisConfig getRedisConfig() {
        if (ServiceReferenceHolder.getInstance().getAPIManagerConfigurationService() == null) {
            return null;
        }
        return ServiceReferenceHolder.getInstance().getAPIManagerConfigurationService()
                .getAPIManagerConfiguration().getRedisConfig();
    }
}
