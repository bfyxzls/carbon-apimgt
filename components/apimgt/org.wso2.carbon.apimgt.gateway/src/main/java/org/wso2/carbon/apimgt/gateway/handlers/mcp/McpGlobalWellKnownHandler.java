/*
 * Copyright (c) 2025, WSO2 LLC. (http://www.wso2.com).
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
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.apimgt.gateway.handlers.mcp;

import org.apache.axis2.Constants;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpStatus;
import org.apache.synapse.MessageContext;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.rest.AbstractHandler;
import org.apache.synapse.rest.RESTConstants;
import org.apache.synapse.transport.nhttp.NhttpConstants;
import org.wso2.carbon.apimgt.gateway.handlers.Utils;
import org.wso2.carbon.apimgt.gateway.utils.GatewayUtils;
import org.wso2.carbon.apimgt.gateway.utils.MCPUtils;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.keymgt.model.entity.API;

/**
 * Serves the gateway-level MCP oauth-protected-resource metadata endpoint, e.g.
 * {@code /.well-known/oauth-protected-resource/mcp-law-agg/1.0.0} or
 * {@code /.well-known/oauth-protected-resource/mcp-law-agg/1.0.0/mcp}.
 * Both paths return the same metadata document.
 */
public class McpGlobalWellKnownHandler extends AbstractHandler {

    private static final Log log = LogFactory.getLog(McpGlobalWellKnownHandler.class);

    @Override
    public boolean handleRequest(MessageContext messageContext) {
        org.apache.axis2.context.MessageContext axis2MsgContext =
                ((Axis2MessageContext) messageContext).getAxis2MessageContext();
        String httpMethod = (String) axis2MsgContext.getProperty(Constants.Configuration.HTTP_METHOD);
        if (!APIConstants.HTTP_GET.equalsIgnoreCase(httpMethod)) {
            MCPUtils.setHttpResponseStatus(messageContext, HttpStatus.SC_METHOD_NOT_ALLOWED, true);
            return true;
        }

        String tenantDomain = GatewayUtils.getTenantDomain();
        if (StringUtils.isEmpty(tenantDomain)) {
            tenantDomain = APIConstants.SUPER_TENANT_DOMAIN;
        }

        String requestPath = resolveRequestPath(messageContext);
        String apiContext = resolveTargetApiContext(messageContext, tenantDomain, requestPath);
        if (StringUtils.isEmpty(apiContext)) {
            log.debug("Unable to resolve MCP API context for global oauth-protected-resource request. "
                    + "tenant=" + tenantDomain + ", path=" + requestPath);
            MCPUtils.setHttpResponseStatus(messageContext, HttpStatus.SC_NOT_FOUND, true);
            return true;
        }

        API matchedAPI = MCPUtils.findMcpApiByContext(tenantDomain, apiContext);
        if (matchedAPI == null) {
            log.debug("No MCP API found for global oauth-protected-resource request. tenant=" + tenantDomain
                    + ", resolvedContext=" + apiContext + ", path=" + requestPath);
            MCPUtils.setHttpResponseStatus(messageContext, HttpStatus.SC_NOT_FOUND, true);
            return true;
        }

        if (log.isDebugEnabled()) {
            log.debug("Resolved MCP API for global oauth-protected-resource request. tenant=" + tenantDomain
                    + ", apiContext=" + matchedAPI.getContext() + ", path=" + requestPath);
        }

        messageContext.setProperty(RESTConstants.REST_API_CONTEXT, matchedAPI.getContext());
        if (!MCPUtils.writeOAuthProtectedResourceMetadataResponse(messageContext, matchedAPI,
                MCPUtils.getAllScopesFromApi(matchedAPI))) {
            log.error("Failed to write oauth-protected-resource metadata for API: " + matchedAPI.getUuid());
            MCPUtils.setHttpResponseStatus(messageContext, HttpStatus.SC_INTERNAL_SERVER_ERROR, true);
        }
        return true;
    }

    @Override
    public boolean handleResponse(MessageContext messageContext) {
        return true;
    }

    private String resolveRequestPath(MessageContext messageContext) {
        org.apache.axis2.context.MessageContext axis2MsgContext =
                ((Axis2MessageContext) messageContext).getAxis2MessageContext();
        String path = (String) messageContext.getProperty(RESTConstants.REST_FULL_REQUEST_PATH);
        if (StringUtils.isEmpty(path)) {
            path = org.apache.synapse.api.ApiUtils.getFullRequestPath(messageContext);
        }
        String postfix = (String) axis2MsgContext.getProperty(NhttpConstants.REST_URL_POSTFIX);
        if (StringUtils.isEmpty(path) && StringUtils.isNotEmpty(postfix)) {
            path = postfix;
        }
        return path;
    }

    private String resolveTargetApiContext(MessageContext messageContext, String tenantDomain, String requestPath) {
        org.apache.axis2.context.MessageContext axis2MsgContext =
                ((Axis2MessageContext) messageContext).getAxis2MessageContext();
        String postfix = (String) axis2MsgContext.getProperty(NhttpConstants.REST_URL_POSTFIX);
        if (StringUtils.isNotEmpty(postfix) && !"/".equals(postfix) && !"/*".equals(postfix)) {
            return postfix.startsWith("/") ? postfix : "/" + postfix;
        }

        String apiContext = Utils.extractMcpApiContextFromGlobalWellKnownPath(requestPath, tenantDomain);
        if (StringUtils.isNotEmpty(apiContext)) {
            return apiContext;
        }

        String suffix = Utils.getMcpRequestPath(messageContext);
        if (StringUtils.isNotEmpty(suffix) && !"/".equals(suffix) && !"/*".equals(suffix)) {
            return suffix.startsWith("/") ? suffix : "/" + suffix;
        }
        return null;
    }
}
