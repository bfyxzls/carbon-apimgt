package org.wso2.carbon.apimgt.impl.notifier;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.APIProvider;
import org.wso2.carbon.apimgt.api.model.API;
import org.wso2.carbon.apimgt.api.model.APICategory;
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
        // 对API的事件源进行增强，添加API分类信息到事件的自定义属性中，以便后续处理器可以使用这些信息进行更丰富的处理。
        if (event instanceof APIEvent) {
            enrichApiUpdateEvent((APIEvent) event);
        }
        return super.publishEvent(event);
    }

    private void enrichApiUpdateEvent(APIEvent apiEvent) {
        apiEvent.setCategories(resolveApiCategoryNames(apiEvent));
    }

    private List<String> resolveApiCategoryNames(APIEvent apiEvent) {
        String apiUuid = apiEvent.getUuid();
        if (StringUtils.isBlank(apiUuid)) {
            log.warn("API_UPDATE event has no API UUID; skipping category enrichment.");
            return Collections.emptyList();
        }
        try {
            String organization = ApiMgtDAO.getInstance().getOrganizationByAPIUUID(apiUuid);
            APIProvider apiProvider = APIManagerFactory.getInstance()
                    .getAPIProvider(CarbonContext.getThreadLocalCarbonContext().getUsername());
            API api = apiProvider.getLightweightAPIByUUID(apiUuid, organization);
            List<APICategory> apiCategories = api.getApiCategories();
            if (apiCategories == null || apiCategories.isEmpty()) {
                return Collections.emptyList();
            }
            return apiCategories.stream()
                    .map(APICategory::getName)
                    .filter(StringUtils::isNotBlank)
                    .collect(Collectors.toList());
        } catch (APIManagementException e) {
            log.warn("Failed to load API categories for API_UPDATE event. API UUID: " + apiUuid, e);
            return Collections.emptyList();
        }
    }
}
