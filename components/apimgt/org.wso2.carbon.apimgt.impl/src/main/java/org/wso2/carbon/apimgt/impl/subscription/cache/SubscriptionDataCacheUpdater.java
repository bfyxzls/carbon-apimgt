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
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.apimgt.api.model.subscription.Application;
import org.wso2.carbon.apimgt.api.model.subscription.ApplicationKeyMapping;
import org.wso2.carbon.apimgt.api.model.subscription.Subscription;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.impl.notifier.events.ApplicationEvent;
import org.wso2.carbon.apimgt.impl.notifier.events.ApplicationRegistrationEvent;
import org.wso2.carbon.apimgt.impl.notifier.events.Event;
import org.wso2.carbon.apimgt.impl.notifier.events.SubscriptionEvent;

import java.util.Map;

/**
 * Applies notifier events to Redis subscription cache (ACP authoritative writes).
 */
public class SubscriptionDataCacheUpdater {

    private static final Log log = LogFactory.getLog(SubscriptionDataCacheUpdater.class);

    private SubscriptionDataCacheUpdater() {
    }

    public static void handleEvent(Event event) {
        if (event == null || !SubscriptionDataCacheService.getInstance().isEnabled()) {
            return;
        }
        try {
            if (event instanceof ApplicationEvent) {
                handleApplicationEvent((ApplicationEvent) event);
            } else if (event instanceof SubscriptionEvent) {
                handleSubscriptionEvent((SubscriptionEvent) event);
            } else if (event instanceof ApplicationRegistrationEvent) {
                handleApplicationRegistrationEvent((ApplicationRegistrationEvent) event);
            }
        } catch (Exception e) {
            log.error("Failed to update subscription Redis cache for event type " + event.getType(), e);
        }
    }

    private static void handleApplicationEvent(ApplicationEvent event) {
        String organization = resolveOrganization(event.getTenantDomain());
        SubscriptionDataRedisStore store = SubscriptionDataCacheService.getInstance().getStore();
        if (APIConstants.EventType.APPLICATION_DELETE.name().equals(event.getType())) {
            store.removeApplication(organization, event.getApplicationId());
            return;
        }
        Application application = new Application();
        application.setId(event.getApplicationId());
        application.setUuid(event.getUuid());
        application.setName(event.getApplicationName());
        application.setTokenType(event.getTokenType());
        application.setPolicy(event.getApplicationPolicy());
        application.setSubName(event.getSubscriber());
        application.setOrganization(organization);
        Map<String, String> attributes = event.getAttributes();
        if (attributes != null) {
            for (Map.Entry<String, String> entry : attributes.entrySet()) {
                application.addAttribute(entry.getKey(), entry.getValue());
            }
        }
        if (StringUtils.isNotEmpty(event.getGroupId())) {
            for (String groupId : event.getGroupId().split(",")) {
                if (StringUtils.isNotBlank(groupId)) {
                    application.addGroupId(groupId.trim());
                }
            }
        }
        store.upsertApplication(organization, application);
    }

    private static void handleSubscriptionEvent(SubscriptionEvent event) {
        String organization = resolveOrganization(event.getTenantDomain());
        SubscriptionDataRedisStore store = SubscriptionDataCacheService.getInstance().getStore();
        if (APIConstants.EventType.SUBSCRIPTIONS_DELETE.name().equals(event.getType())) {
            store.removeSubscription(organization, event.getApplicationId(), event.getApiId());
            return;
        }
        Subscription subscription = new Subscription();
        subscription.setSubscriptionId(event.getSubscriptionId());
        subscription.setSubscriptionUUID(event.getSubscriptionUUID());
        subscription.setApiId(event.getApiId());
        subscription.setApiUUID(event.getApiUUID());
        subscription.setAppId(event.getApplicationId());
        subscription.setApplicationUUID(event.getApplicationUUID());
        subscription.setPolicyId(event.getPolicyId());
        subscription.setSubscriptionState(event.getSubscriptionState());
        subscription.setApiName(event.getApiName());
        subscription.setApiVersion(event.getApiVersion());
        store.upsertSubscription(organization, subscription);
    }

    private static void handleApplicationRegistrationEvent(ApplicationRegistrationEvent event) {
        String organization = resolveOrganization(event.getTenantDomain());
        SubscriptionDataRedisStore store = SubscriptionDataCacheService.getInstance().getStore();
        if (APIConstants.EventType.REMOVE_APPLICATION_KEYMAPPING.name().equals(event.getType())) {
            store.removeKeyMapping(organization, event.getConsumerKey(), event.getKeyManager());
            return;
        }
        ApplicationKeyMapping mapping = new ApplicationKeyMapping();
        mapping.setApplicationId(event.getApplicationId());
        mapping.setApplicationUUID(event.getApplicationUUID());
        mapping.setConsumerKey(event.getConsumerKey());
        mapping.setKeyType(event.getKeyType());
        mapping.setKeyManager(event.getKeyManager());
        store.upsertKeyMapping(organization, mapping);
    }

    private static String resolveOrganization(String tenantDomain) {
        return StringUtils.isNotEmpty(tenantDomain) ? tenantDomain : "carbon.super";
    }
}
