/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.com).
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

package org.wso2.carbon.apimgt.gateway.utils;

import org.apache.commons.lang3.StringUtils;
import org.apache.synapse.MessageContext;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.wso2.carbon.apimgt.gateway.APIMgtGatewayConstants;
import org.wso2.carbon.apimgt.gateway.mcp.request.McpRequest;
import org.wso2.carbon.apimgt.gateway.mcp.request.Params;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.keymgt.model.entity.API;

import java.util.Map;

/**
 * Detects the northbound MCP protocol era (legacy 1.0 vs modern 2.0) and resolves the
 * configured southbound backend protocol version for an MCP Server.
 * <p>
 * For {@code SERVER_PROXY} APIs the gateway is a transparent proxy: negotiation results are
 * stored for analytics, but the request body is not rewritten and
 * {@code MCP_NEEDS_TRANSLATION} is always false so upstream servers can auto-negotiate.
 * </p>
 */
public final class MCPProtocolNegotiator {

    private MCPProtocolNegotiator() {
    }

    /**
     * Negotiates the client (northbound) protocol version/era from headers and request body,
     * resolves the backend protocol from API metadata/properties, and stores results on the
     * message context.
     *
     * @param messageContext synapse message context
     * @param request        parsed MCP JSON-RPC request (may be null for GET)
     * @param matchedApi     matched MCP API (may be null)
     * @return negotiated northbound protocol version
     */
    public static String negotiateAndStore(MessageContext messageContext, McpRequest request, API matchedApi) {
        boolean serverProxyPassthrough = isServerProxy(matchedApi);

        String headerVersion = getTransportHeader(messageContext, APIConstants.MCP.MCP_PROTOCOL_VERSION_HEADER);
        String sessionId = getTransportHeader(messageContext, APIConstants.MCP.HEADER_MCP_SESSION_ID);
        String mcpMethodHeader = getTransportHeader(messageContext, APIConstants.MCP.HEADER_MCP_METHOD);

        String bodyMethod = request != null ? request.getMethod() : null;
        String method = StringUtils.isNotEmpty(mcpMethodHeader) ? mcpMethodHeader : bodyMethod;
        if (StringUtils.isNotEmpty(method)) {
            messageContext.setProperty(APIMgtGatewayConstants.MCP_METHOD, method);
        }

        String metaVersion = request != null ? request.getMetaProtocolVersion() : null;
        // params.protocolVersion is MCP 1.0 initialize-only. Never use it for tools/call etc., or a
        // stale 2025-06-18 wins negotiation and is written to analytics as properties.protocolVersion.
        String legacyParamsProtocolVersion = null;
        if (APIConstants.MCP.METHOD_INITIALIZE.equals(method)
                && request != null && request.getParams() != null) {
            legacyParamsProtocolVersion = request.getParams().getProtocolVersion();
        }

        String northboundVersion = resolveNorthboundVersion(headerVersion, metaVersion, legacyParamsProtocolVersion,
                method, sessionId, mcpMethodHeader);
        String northboundEra = APIConstants.MCP.resolveProtocolEra(northboundVersion);

        messageContext.setProperty(APIMgtGatewayConstants.MCP_PROTOCOL_VERSION_KEY, northboundVersion);
        messageContext.setProperty(APIMgtGatewayConstants.MCP_PROTOCOL_ERA_KEY, northboundEra);

        // SERVER_PROXY: upstream MCP servers negotiate 1.0/2.0 themselves — do not rewrite the body.
        if (!serverProxyPassthrough) {
            // Always strip leftover params.protocolVersion on non-initialize traffic (MCP 2.0 and mixed).
            if (!APIConstants.MCP.METHOD_INITIALIZE.equals(method)) {
                clearLegacyParamsProtocolVersion(messageContext, request);
            } else if (StringUtils.isNotEmpty(legacyParamsProtocolVersion)
                    && APIConstants.MCP.PROTOCOL_ERA_LEGACY.equals(northboundEra)) {
                messageContext.setProperty(APIMgtGatewayConstants.MCP_REQUESTED_PROTOCOL_VERSION_KEY,
                        legacyParamsProtocolVersion);
            }

            if (APIConstants.MCP.PROTOCOL_ERA_MODERN.equals(northboundEra)) {
                clearLegacyParamsProtocolVersion(messageContext, request);
            }
        } else if (APIConstants.MCP.METHOD_INITIALIZE.equals(method)
                && StringUtils.isNotEmpty(legacyParamsProtocolVersion)
                && APIConstants.MCP.PROTOCOL_ERA_LEGACY.equals(northboundEra)) {
            messageContext.setProperty(APIMgtGatewayConstants.MCP_REQUESTED_PROTOCOL_VERSION_KEY,
                    legacyParamsProtocolVersion);
        }

        if (StringUtils.isNotEmpty(sessionId)) {
            messageContext.setProperty(APIMgtGatewayConstants.MCP_SESSION_ID_KEY, sessionId);
        }

        String backendVersion = resolveBackendProtocolVersion(matchedApi);
        String backendEra = APIConstants.MCP.resolveProtocolEra(backendVersion);
        messageContext.setProperty(APIMgtGatewayConstants.MCP_BACKEND_PROTOCOL_VERSION_KEY, backendVersion);
        messageContext.setProperty(APIMgtGatewayConstants.MCP_BACKEND_PROTOCOL_ERA_KEY, backendEra);
        // SERVER_PROXY is a transparent proxy; never translate request/response dialects.
        boolean needsTranslation = !serverProxyPassthrough && !StringUtils.equals(northboundEra, backendEra);
        messageContext.setProperty(APIMgtGatewayConstants.MCP_NEEDS_TRANSLATION_KEY, needsTranslation);

        return northboundVersion;
    }

    private static boolean isServerProxy(API matchedApi) {
        return matchedApi != null
                && APIConstants.API_SUBTYPE_SERVER_PROXY.equals(matchedApi.getSubtype());
    }

    /**
     * Removes leftover {@code params.protocolVersion} from the parsed request and JSON body.
     * That field is MCP 1.0 initialize-only; a stale {@code 2025-06-18} on {@code tools/call}
     * must not leak into forwarded payloads or confuse debugging.
     */
    private static void clearLegacyParamsProtocolVersion(MessageContext messageContext, McpRequest request) {
        if (request != null && request.getParams() != null) {
            request.getParams().setProtocolVersion(null);
        }

        if (!(messageContext instanceof Axis2MessageContext)) {
            return;
        }
        try {
            org.apache.axis2.context.MessageContext axis2MC =
                    ((Axis2MessageContext) messageContext).getAxis2MessageContext();
            if (!org.apache.synapse.commons.json.JsonUtil.hasAJsonPayload(axis2MC)) {
                return;
            }
            String body = org.apache.synapse.commons.json.JsonUtil.jsonPayloadToString(axis2MC);
            com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
            if (!root.has(APIConstants.MCP.PARAMS_KEY) || !root.get(APIConstants.MCP.PARAMS_KEY).isJsonObject()) {
                return;
            }
            com.google.gson.JsonObject params = root.getAsJsonObject(APIConstants.MCP.PARAMS_KEY);
            if (!params.has(APIConstants.MCP.PROTOCOL_VERSION_KEY)) {
                return;
            }
            params.remove(APIConstants.MCP.PROTOCOL_VERSION_KEY);
            org.apache.synapse.commons.json.JsonUtil.removeJsonPayload(axis2MC);
            org.apache.synapse.commons.json.JsonUtil.getNewJsonPayload(axis2MC, root.toString(), true, true);
        } catch (Exception e) {
            // Best-effort; negotiated modern dialect already ignores this field.
        }
    }

    /**
     * Resolves the southbound MCP protocol version configured on the MCP Server.
     * Defaults to legacy {@code 2025-06-18} when unset.
     */
    public static String resolveBackendProtocolVersion(API matchedApi) {
        if (matchedApi == null) {
            return APIConstants.MCP.PROTOCOL_VERSION_2025_JUNE;
        }
        if (StringUtils.isNotEmpty(matchedApi.getProtocolVersion())) {
            return matchedApi.getProtocolVersion();
        }
        Map<String, String> properties = matchedApi.getApiProperties();
        if (properties != null) {
            String fromProps = properties.get(APIConstants.MCP.PROTOCOL_VERSION_KEY);
            if (StringUtils.isNotEmpty(fromProps)) {
                return fromProps;
            }
        }
        return APIConstants.MCP.PROTOCOL_VERSION_2025_JUNE;
    }

    /**
     * Prefer {@code Mcp-Method} header over JSON-RPC body method when present.
     */
    public static String resolveMethod(MessageContext messageContext, McpRequest request) {
        String headerMethod = getTransportHeader(messageContext, APIConstants.MCP.HEADER_MCP_METHOD);
        if (StringUtils.isNotEmpty(headerMethod)) {
            return headerMethod;
        }
        return request != null ? request.getMethod() : null;
    }

    public static boolean isModernNorthbound(MessageContext messageContext) {
        Object era = messageContext.getProperty(APIMgtGatewayConstants.MCP_PROTOCOL_ERA_KEY);
        return APIConstants.MCP.PROTOCOL_ERA_MODERN.equals(era);
    }

    public static boolean isLegacyNorthbound(MessageContext messageContext) {
        return !isModernNorthbound(messageContext);
    }

    public static boolean needsTranslation(MessageContext messageContext) {
        Object flag = messageContext.getProperty(APIMgtGatewayConstants.MCP_NEEDS_TRANSLATION_KEY);
        return Boolean.TRUE.equals(flag) || Boolean.parseBoolean(String.valueOf(flag));
    }

    private static String resolveNorthboundVersion(String headerVersion, String metaVersion,
                                                   String initializeVersion, String method,
                                                   String sessionId, String mcpMethodHeader) {
        // Explicit version signals win first.
        if (APIConstants.MCP.isSupportedProtocolVersion(headerVersion)) {
            return headerVersion;
        }
        if (APIConstants.MCP.isSupportedProtocolVersion(metaVersion)) {
            return metaVersion;
        }
        if (APIConstants.MCP.isSupportedProtocolVersion(initializeVersion)) {
            return initializeVersion;
        }

        // Strong modern signals without an explicit version.
        if (APIConstants.MCP.METHOD_SERVER_DISCOVER.equals(method)
                || StringUtils.isNotEmpty(mcpMethodHeader)
                || APIConstants.MCP.PROTOCOL_VERSION_2026_JULY.equals(headerVersion)
                || APIConstants.MCP.PROTOCOL_VERSION_2026_JULY.equals(metaVersion)) {
            return APIConstants.MCP.PROTOCOL_VERSION_2026_JULY;
        }

        // Legacy signals.
        if (APIConstants.MCP.METHOD_INITIALIZE.equals(method) || StringUtils.isNotEmpty(sessionId)) {
            return APIConstants.MCP.PROTOCOL_VERSION_2025_JUNE;
        }

        // Default to legacy for backward compatibility with existing MCP 1.0 clients.
        return APIConstants.MCP.PROTOCOL_VERSION_2025_JUNE;
    }

    @SuppressWarnings("unchecked")
    public static String getTransportHeader(MessageContext messageContext, String headerName) {
        if (messageContext == null || !(messageContext instanceof Axis2MessageContext)) {
            return null;
        }
        org.apache.axis2.context.MessageContext axis2MC =
                ((Axis2MessageContext) messageContext).getAxis2MessageContext();
        Object headersObj = axis2MC.getProperty(org.apache.axis2.context.MessageContext.TRANSPORT_HEADERS);
        if (!(headersObj instanceof Map)) {
            return null;
        }
        Map<String, Object> headers = (Map<String, Object>) headersObj;
        Object value = headers.get(headerName);
        if (value == null) {
            // Case-insensitive fallback
            for (Map.Entry<String, Object> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(headerName)) {
                    value = entry.getValue();
                    break;
                }
            }
        }
        return value != null ? String.valueOf(value) : null;
    }
}
