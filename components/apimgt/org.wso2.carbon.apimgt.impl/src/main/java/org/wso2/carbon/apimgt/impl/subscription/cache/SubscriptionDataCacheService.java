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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Control-plane helper for reading/writing subscription validation data via Redis with DB fallback.
 */
public class SubscriptionDataCacheService {

    private static final Log log = LogFactory.getLog(SubscriptionDataCacheService.class);
    private static final SubscriptionDataCacheService INSTANCE = new SubscriptionDataCacheService();
    /**
     * Peer wait while another node holds the warm lock. Longer than a single lock TTL because the
     * holder renews the lock every {@link SubscriptionDataRedisStore#LOCK_RENEW_INTERVAL_SECONDS}s.
     */
    private static final long WAIT_FOR_WARM_TIMEOUT_MS =
            TimeUnit.SECONDS.toMillis(SubscriptionDataRedisStore.LOCK_TTL_SECONDS * 5L);
    /** Default DB page size for streamed subscriptions warm (keyset on SUBSCRIPTION_ID). */
    private static final int DEFAULT_SUBSCRIPTIONS_WARM_BATCH_SIZE = 100000;
    private static final String SUBSCRIPTIONS_WARM_BATCH_SIZE_PROPERTY =
            "apim.subscription.cache.warm.batch.size";
    /** Default DB page size for streamed keyMappings warm (keyset on APPLICATION_ID/KEY_TYPE/KEY_MANAGER). */
    private static final int DEFAULT_KEYMAPPINGS_WARM_BATCH_SIZE = 100000;
    private static final String KEYMAPPINGS_WARM_BATCH_SIZE_PROPERTY =
            "apim.subscription.cache.warm.keymapping.batch.size";
    /** Allow markWarm when apps+subs+keyMappings are all 0 (empty install only). */
    private static final String WARM_ALLOW_EMPTY_PROPERTY = "apim.subscription.cache.warm.allow.empty";
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
            log.info("Subscription Redis cache already marked warm for organization " + organization
                    + "; skip DB load and Redis rewrite. If business keys are missing, clear meta.ready and retry.");
            return;
        }
        ReentrantLock localLock = localLocks.computeIfAbsent(organization, key -> new ReentrantLock());
        localLock.lock();
        try {
            if (store.isWarm(organization)) {
                log.info("Subscription Redis cache already marked warm for organization " + organization
                        + " after local lock; skip warm.");
                return;
            }
            String lockToken = store.tryLock(organization);
            if (lockToken == null) {
                log.info("Subscription cache warm lock held by another node for organization " + organization
                        + "; waiting up to " + WAIT_FOR_WARM_TIMEOUT_MS + " ms");
                waitForWarm(organization, WAIT_FOR_WARM_TIMEOUT_MS);
                return;
            }
            ScheduledExecutorService renewer = null;
            ScheduledFuture<?> renewFuture = null;
            long warmStartedAt = System.currentTimeMillis();
            try {
                if (store.isWarm(organization)) {
                    return;
                }
                renewer = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "SubscriptionCacheLockRenew-" + organization);
                    t.setDaemon(true);
                    return t;
                });
                final String token = lockToken;
                renewFuture = renewer.scheduleAtFixedRate(() -> {
                    if (!store.renewLock(organization, token)) {
                        log.warn("Failed to renew subscription cache lock for org " + organization
                                + "; lock may have been lost");
                    }
                }, SubscriptionDataRedisStore.LOCK_RENEW_INTERVAL_SECONDS,
                        SubscriptionDataRedisStore.LOCK_RENEW_INTERVAL_SECONDS, TimeUnit.SECONDS);

                // Load → write Redis → drop List, one dataset at a time, to cut peak heap
                // (avoid holding apps+subs+keyMappings in memory together before any Redis write).
                SubscriptionValidationDAO dao = new SubscriptionValidationDAO();
                int appCount;
                int subscriptionCount;
                int keyMappingCount;

                log.info("Subscription cache warm [" + organization
                        + "]: phase applications (DB load → Redis write → release)");
                long t0 = System.currentTimeMillis();
                List<Application> applications = dao.getAllApplications(organization);
                appCount = sizeOf(applications);
                log.info("Subscription cache warm [" + organization + "]: loaded applications count="
                        + appCount + " in " + (System.currentTimeMillis() - t0) + " ms");
                store.renewLock(organization, lockToken);
                t0 = System.currentTimeMillis();
                store.putAllApplications(organization, applications);
                log.info("Subscription cache warm [" + organization + "]: wrote applications to Redis in "
                        + (System.currentTimeMillis() - t0) + " ms");
                applications.clear();
                applications = null;

                log.info("Subscription cache warm [" + organization
                        + "]: phase subscriptions (paged DB load → Redis append)");
                t0 = System.currentTimeMillis();
                subscriptionCount = warmSubscriptionsPaged(organization, dao, store, lockToken);
                log.info("Subscription cache warm [" + organization + "]: wrote subscriptions to Redis"
                        + " total=" + subscriptionCount
                        + " in " + (System.currentTimeMillis() - t0) + " ms");

                log.info("Subscription cache warm [" + organization
                        + "]: phase keyMappings (paged DB load → Redis append)");
                t0 = System.currentTimeMillis();
                keyMappingCount = warmKeyMappingsPaged(organization, dao, store, lockToken);
                log.info("Subscription cache warm [" + organization + "]: wrote keyMappings to Redis"
                        + " total=" + keyMappingCount
                        + " in " + (System.currentTimeMillis() - t0) + " ms");

                boolean allowEmpty = Boolean.parseBoolean(System.getProperty(WARM_ALLOW_EMPTY_PROPERTY, "false"));
                if (appCount == 0 && subscriptionCount == 0 && keyMappingCount == 0 && !allowEmpty) {
                    store.clearWarm(organization);
                    throw new IllegalStateException("Subscription cache warm for organization " + organization
                            + " loaded 0 apps / 0 subscriptions / 0 keyMappings. Refusing markWarm so"
                            + " __empty__ placeholders cannot stick as ready."
                            + " Check DB connectivity/SQL errors, or set -D"
                            + WARM_ALLOW_EMPTY_PROPERTY + "=true if empty is intentional.");
                }
                store.markWarm(organization, appCount, subscriptionCount, keyMappingCount, allowEmpty);
                log.info("Warmed subscription Redis cache for organization " + organization
                        + " apps=" + appCount
                        + " subscriptions=" + subscriptionCount
                        + " keyMappings=" + keyMappingCount
                        + " totalWarmMs=" + (System.currentTimeMillis() - warmStartedAt));
            } catch (Throwable t) {
                log.error("Subscription cache warm FAILED for organization " + organization
                        + " after " + (System.currentTimeMillis() - warmStartedAt) + " ms"
                        + " (check whether failure was during DB load or Redis write)", t);
                throw t;
            } finally {
                if (renewFuture != null) {
                    renewFuture.cancel(false);
                }
                if (renewer != null) {
                    renewer.shutdownNow();
                }
                store.unlock(organization, lockToken);
            }
        } finally {
            localLock.unlock();
        }
    }

    private static int sizeOf(List<?> list) {
        return list == null ? 0 : list.size();
    }

    /**
     * Keyset-page subscriptions from DB and append each page to Redis so only one page
     * resides on the heap at a time.
     */
    private int warmSubscriptionsPaged(String organization, SubscriptionValidationDAO dao,
            SubscriptionDataRedisStore store, String lockToken) {
        int batchSize = resolveSubscriptionsWarmBatchSize();
        store.beginSubscriptionsReplace(organization);
        int afterId = 0;
        int total = 0;
        int page = 0;
        try {
            while (true) {
                List<Subscription> pageRows;
                try {
                    pageRows = dao.getSubscriptionsByOrganizationPage(organization, afterId, batchSize);
                } catch (APIManagementException e) {
                    throw new RuntimeException("Failed loading subscriptions page for org " + organization
                            + " afterId=" + afterId + " limit=" + batchSize, e);
                }
                int pageSize = sizeOf(pageRows);
                if (pageSize == 0) {
                    break;
                }
                page++;
                int lastId = pageRows.get(pageSize - 1).getSubscriptionId();
                store.appendSubscriptions(organization, pageRows);
                total += pageSize;
                log.info("Subscription cache warm [" + organization + "]: subscriptions page=" + page
                        + " size=" + pageSize + " afterId=" + afterId + " lastId=" + lastId
                        + " totalSoFar=" + total);
                afterId = lastId;
                pageRows.clear();
                store.renewLock(organization, lockToken);
                if (pageSize < batchSize) {
                    break;
                }
            }
        } finally {
            store.finishSubscriptionsReplace(organization, total);
        }
        return total;
    }

    private static int resolveSubscriptionsWarmBatchSize() {
        return resolvePositiveIntProperty(SUBSCRIPTIONS_WARM_BATCH_SIZE_PROPERTY,
                DEFAULT_SUBSCRIPTIONS_WARM_BATCH_SIZE);
    }

    /**
     * Keyset-page keyMappings from DB and append each page to Redis so only one page
     * resides on the heap at a time.
     */
    private int warmKeyMappingsPaged(String organization, SubscriptionValidationDAO dao,
            SubscriptionDataRedisStore store, String lockToken) {
        int batchSize = resolveKeyMappingsWarmBatchSize();
        store.beginKeyMappingsReplace(organization);
        SubscriptionValidationDAO.KeyMappingPageCursor cursor =
                new SubscriptionValidationDAO.KeyMappingPageCursor();
        int total = 0;
        int page = 0;
        try {
            while (true) {
                List<ApplicationKeyMapping> pageRows;
                try {
                    pageRows = dao.getApplicationKeyMappingsByOrganizationPage(organization, cursor, batchSize);
                } catch (APIManagementException e) {
                    throw new RuntimeException("Failed loading keyMappings page for org " + organization
                            + " afterAppId=" + cursor.getApplicationId()
                            + " afterKeyType=" + cursor.getKeyType()
                            + " limit=" + batchSize, e);
                }
                int pageSize = sizeOf(pageRows);
                if (pageSize == 0) {
                    break;
                }
                page++;
                store.appendKeyMappings(organization, pageRows);
                total += pageSize;
                log.info("Subscription cache warm [" + organization + "]: keyMappings page=" + page
                        + " size=" + pageSize
                        + " lastAppId=" + cursor.getApplicationId()
                        + " lastKeyType=" + cursor.getKeyType()
                        + " totalSoFar=" + total);
                pageRows.clear();
                store.renewLock(organization, lockToken);
                if (pageSize < batchSize) {
                    break;
                }
            }
        } finally {
            store.finishKeyMappingsReplace(organization, total);
        }
        return total;
    }

    private static int resolveKeyMappingsWarmBatchSize() {
        return resolvePositiveIntProperty(KEYMAPPINGS_WARM_BATCH_SIZE_PROPERTY,
                DEFAULT_KEYMAPPINGS_WARM_BATCH_SIZE);
    }

    private static int resolvePositiveIntProperty(String property, int defaultValue) {
        String raw = System.getProperty(property);
        if (raw == null || raw.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            if (parsed <= 0) {
                log.warn("Invalid " + property + "=" + raw + "; using default " + defaultValue);
                return defaultValue;
            }
            return parsed;
        } catch (NumberFormatException e) {
            log.warn("Invalid " + property + "=" + raw + "; using default " + defaultValue);
            return defaultValue;
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
        log.warn("Timed out waiting for subscription cache warm for org " + organization
                + " after " + timeoutMs + " ms");
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
