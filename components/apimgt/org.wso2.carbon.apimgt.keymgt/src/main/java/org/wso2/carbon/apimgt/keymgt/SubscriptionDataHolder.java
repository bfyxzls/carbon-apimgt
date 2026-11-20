/*
 * Copyright (c) 2020, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.carbon.apimgt.keymgt;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.apimgt.impl.dto.RedisConfig;
import org.wso2.carbon.apimgt.impl.subscription.cache.SubscriptionDataCacheService;
import org.wso2.carbon.apimgt.keymgt.model.SubscriptionDataStore;
import org.wso2.carbon.apimgt.keymgt.model.impl.SubscriptionDataStoreImpl;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/*
 * This class holds tenant wise subscription data stores
 * */
public class SubscriptionDataHolder {

    protected Map<String, SubscriptionDataStore> subscriptionStore =
            new ConcurrentHashMap<>();
    private static final Log log = LogFactory.getLog(SubscriptionDataHolder.class);
    private static SubscriptionDataHolder instance = new SubscriptionDataHolder();

    public static SubscriptionDataHolder getInstance() {

        return instance;
    }

    public SubscriptionDataStore registerTenantSubscriptionStore(String tenantDomain) {

        SubscriptionDataStore tenantStore = subscriptionStore.get(tenantDomain);
        if (tenantStore == null) {
            tenantStore = new SubscriptionDataStoreImpl(tenantDomain);
        }
        subscriptionStore.put(tenantDomain, tenantStore);
        return tenantStore;
    }

    public void initializeSubscriptionStore(String tenantDomain) {

        waitForSubscriptionRedisStoreIfNeeded(tenantDomain);
        SubscriptionDataStore tenantStore = subscriptionStore.get(tenantDomain);
        if (tenantStore != null) {
            tenantStore.init();
        }
    }

    /**
     * Avoid PopulateTask falling back to ACP when {@link org.wso2.carbon.apimgt.gateway.internal.APIHandlerServiceComponent}
     * has not yet registered the Redis store.
     */
    private void waitForSubscriptionRedisStoreIfNeeded(String tenantDomain) {
        if (!isSubscriptionRedisCacheConfigured()) {
            return;
        }
        long deadline = System.currentTimeMillis() + 30_000L;
        while (SubscriptionDataCacheService.getInstance().getStore() == null
                && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(200L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting for subscription Redis store for tenant=" + tenantDomain);
                return;
            }
        }
        if (SubscriptionDataCacheService.getInstance().getStore() == null) {
            log.warn("Subscription data Redis store not ready after 30s for tenant=" + tenantDomain
                    + "; subscription PopulateTask may fall back to Event Hub internal API");
        }
    }

    private boolean isSubscriptionRedisCacheConfigured() {
        RedisConfig redisConfig = org.wso2.carbon.apimgt.keymgt.internal.ServiceReferenceHolder.getInstance()
                .getAPIManagerConfigurationService().getAPIManagerConfiguration().getRedisConfig();
        if (redisConfig == null || !redisConfig.isRedisEnabled()) {
            return false;
        }
        return redisConfig.isSubscriptionDataCacheEnabled()
                || Boolean.parseBoolean(System.getProperty("apim.subscription.data.cache.enabled", "false"));
    }

    public void unregisterTenantSubscriptionStore(String tenantDomain) {

        SubscriptionDataStore subscriptionDataStore = subscriptionStore.get(tenantDomain);
        if (subscriptionDataStore!= null){
            subscriptionDataStore.destroy();
        }
        subscriptionStore.remove(tenantDomain);
    }

    public SubscriptionDataStore getTenantSubscriptionStore(String tenantDomain) {
        if (subscriptionStore != null && tenantDomain != null) {
            SubscriptionDataStore subscriptionDataStore = subscriptionStore.get(tenantDomain);
            if (subscriptionDataStore == null) {
                synchronized (tenantDomain.concat("getTenantSubscriptionStore").intern()) {
                    subscriptionDataStore = subscriptionStore.get(tenantDomain);
                    if (subscriptionDataStore == null) {
                        subscriptionDataStore = registerTenantSubscriptionStore(tenantDomain);
                    }
                }
            }
            return subscriptionDataStore;
        }
        return null;
    }

    public Map<String, SubscriptionDataStore> getTenantSubscriptionStoreMap() {
        return subscriptionStore;
    }

    public void refreshSubscriptionStore() {
        subscriptionStore.keySet().forEach(tenant -> {
            // Cleaning the existing SubscriptionDataStore instance before re-population
            SubscriptionDataStore oldStore = subscriptionStore.get(tenant);
            if (oldStore != null) {
                try {
                    oldStore.destroy();
                } catch (Throwable t) {
                    log.warn("Error while destroying old SubscriptionDataStore for tenant: " + tenant, t);
                }
            }
            subscriptionStore.put(tenant, new SubscriptionDataStoreImpl(tenant));
            if (log.isDebugEnabled()) {
                log.debug("Refreshing subscription data store for tenant: " + tenant);
            }
            initializeSubscriptionStore(tenant);
        });
    }

}
