package org.wso2.carbon.apimgt.impl.notifier;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.apimgt.api.APIProvider;
import org.wso2.carbon.apimgt.api.model.API;
import org.wso2.carbon.apimgt.api.model.APICategory;
import org.wso2.carbon.apimgt.api.model.APIInfo;
import org.wso2.carbon.apimgt.impl.APIManagerFactory;
import org.wso2.carbon.apimgt.impl.dao.ApiMgtDAO;
import org.wso2.carbon.apimgt.impl.notifier.events.APIEvent;
import org.wso2.carbon.apimgt.impl.notifier.events.Event;
import org.wso2.carbon.apimgt.impl.notifier.exceptions.NotifierException;
import org.wso2.carbon.context.CarbonContext;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 自定义事件通知者.
 */
public class CustomApisNotifier extends ApisNotifier {

    private static final Log log = LogFactory.getLog(CustomApisNotifier.class);

    @Override
    public boolean publishEvent(Event event) throws NotifierException {
        // Enrichment must never block publishing: catch all Throwable so Event Hub / Kafka still receives the event.
        if (event instanceof APIEvent) {
            try {
                enrichApiEvent((APIEvent) event);
            } catch (Throwable t) {
                log.warn("Failed to enrich API event; publishing original event. UUID: "
                        + ((APIEvent) event).getUuid(), t);
            }
        }
        return super.publishEvent(event);
    }

    private void enrichApiEvent(APIEvent apiEvent) {
        String apiUuid = apiEvent.getUuid();
        if (StringUtils.isBlank(apiUuid)) {
            log.warn("API event has no API UUID; skipping enrichment.");
            apiEvent.setCategories(Collections.emptyList());
            return;
        }
        apiEvent.setApiDisplayName(resolveApiDisplayName(apiUuid));
        apiEvent.setCategories(resolveApiCategoryNames(apiUuid));
    }

    private String resolveApiDisplayName(String apiUuid) {
        try {
            APIInfo apiInfo = ApiMgtDAO.getInstance().getAPIInfoByUUID(apiUuid);
            if (apiInfo != null) {
                return apiInfo.getDisplayName();
            }
        } catch (Exception e) {
            log.warn("Failed to load API display name for event enrichment. API UUID: " + apiUuid, e);
        }
        return null;
    }

    private List<String> resolveApiCategoryNames(String apiUuid) {
        try {
            String organization = ApiMgtDAO.getInstance().getOrganizationByAPIUUID(apiUuid);
            String username = CarbonContext.getThreadLocalCarbonContext() != null
                    ? CarbonContext.getThreadLocalCarbonContext().getUsername()
                    : null;
            if (StringUtils.isBlank(username)) {
                log.warn("No username in CarbonContext; skipping category enrichment. API UUID: " + apiUuid);
                return Collections.emptyList();
            }
            APIProvider apiProvider = APIManagerFactory.getInstance().getAPIProvider(username);
            API api = apiProvider.getLightweightAPIByUUID(apiUuid, organization);
            List<APICategory> apiCategories = api.getApiCategories();
            if (apiCategories == null || apiCategories.isEmpty()) {
                return Collections.emptyList();
            }
            return apiCategories.stream()
                    .map(APICategory::getName)
                    .filter(StringUtils::isNotBlank)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Failed to load API categories for event enrichment. API UUID: " + apiUuid, e);
            return Collections.emptyList();
        }
    }
}
