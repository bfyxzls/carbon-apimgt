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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.lang3.StringUtils;
import org.apache.synapse.MessageContext;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.wso2.carbon.apimgt.gateway.APIMgtGatewayConstants;
import org.wso2.carbon.apimgt.gateway.mcp.request.McpRequest;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.keymgt.model.entity.API;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Translates MCP JSON-RPC requests/responses between legacy (1.0) and modern (2.0)
 * dialects for SERVER_PROXY backends when northbound and southbound eras differ.
 */
public final class MCPProtocolTranslator {

    private MCPProtocolTranslator() {
    }

    /**
     * Applies southbound request shaping for SERVER_PROXY before the call leaves the gateway.
     * Same-era traffic is left intact aside from optional header enrichment.
     *
     * @return true if the mediator should continue (possibly after rewriting headers/body);
     *         false if the request was fully handled locally (e.g. initialize answered for
     *         a modern-only backend).
     */
    @SuppressWarnings("unchecked")
    public static boolean prepareSouthboundRequest(MessageContext messageContext, API matchedApi,
                                                   McpRequest request) {
        if (matchedApi == null || request == null) {
            return true;
        }
        String northEra = String.valueOf(messageContext.getProperty(APIMgtGatewayConstants.MCP_PROTOCOL_ERA_KEY));
        String southEra = String.valueOf(messageContext.getProperty(APIMgtGatewayConstants.MCP_BACKEND_PROTOCOL_ERA_KEY));
        String southVersion = String.valueOf(
                messageContext.getProperty(APIMgtGatewayConstants.MCP_BACKEND_PROTOCOL_VERSION_KEY));
        String method = (String) messageContext.getProperty(APIMgtGatewayConstants.MCP_METHOD);

        org.apache.axis2.context.MessageContext axis2MC =
                ((Axis2MessageContext) messageContext).getAxis2MessageContext();
        Map<String, Object> headers = (Map<String, Object>) axis2MC.getProperty(
                org.apache.axis2.context.MessageContext.TRANSPORT_HEADERS);
        if (headers == null) {
            headers = new HashMap<>();
            axis2MC.setProperty(org.apache.axis2.context.MessageContext.TRANSPORT_HEADERS, headers);
        }

        // Always stamp backend protocol version when known.
        if (StringUtils.isNotEmpty(southVersion) && !"null".equals(southVersion)) {
            headers.put(APIConstants.MCP.MCP_PROTOCOL_VERSION_HEADER, southVersion);
        }

        if (APIConstants.MCP.PROTOCOL_ERA_LEGACY.equals(northEra)
                && APIConstants.MCP.PROTOCOL_ERA_MODERN.equals(southEra)) {
            return prepareLegacyClientToModernBackend(messageContext, matchedApi, request, method, headers);
        }
        if (APIConstants.MCP.PROTOCOL_ERA_MODERN.equals(northEra)
                && APIConstants.MCP.PROTOCOL_ERA_LEGACY.equals(southEra)) {
            return prepareModernClientToLegacyBackend(messageContext, matchedApi, request, method, headers);
        }

        // Same-era enrichment for modern: ensure Mcp-Method / _meta are present.
        if (APIConstants.MCP.PROTOCOL_ERA_MODERN.equals(southEra)) {
            if (StringUtils.isNotEmpty(method) && !headers.containsKey(APIConstants.MCP.HEADER_MCP_METHOD)) {
                headers.put(APIConstants.MCP.HEADER_MCP_METHOD, method);
            }
            ensureMetaOnRequestBody(axis2MC, request, southVersion);
            // Modern backends do not use sessions.
            headers.remove(APIConstants.MCP.HEADER_MCP_SESSION_ID);
        }
        return true;
    }

    /**
     * Shapes a southbound JSON-RPC response back toward the northbound client era.
     */
    public static String translateSouthboundResponse(MessageContext messageContext, String responseBody) {
        if (StringUtils.isEmpty(responseBody) || !MCPProtocolNegotiator.needsTranslation(messageContext)) {
            return responseBody;
        }
        String northEra = String.valueOf(messageContext.getProperty(APIMgtGatewayConstants.MCP_PROTOCOL_ERA_KEY));
        String southEra = String.valueOf(messageContext.getProperty(APIMgtGatewayConstants.MCP_BACKEND_PROTOCOL_ERA_KEY));

        if (APIConstants.MCP.PROTOCOL_ERA_MODERN.equals(northEra)
                && APIConstants.MCP.PROTOCOL_ERA_LEGACY.equals(southEra)) {
            // Hide session from modern clients; capture it for cache if present.
            captureSessionFromResponse(messageContext, responseBody);
            stripSessionHeader(messageContext);
            return responseBody;
        }
        if (APIConstants.MCP.PROTOCOL_ERA_LEGACY.equals(northEra)
                && APIConstants.MCP.PROTOCOL_ERA_MODERN.equals(southEra)) {
            stripSessionHeader(messageContext);
            return mapModernErrorCodesToLegacyIfNeeded(responseBody);
        }
        return responseBody;
    }

    private static boolean prepareLegacyClientToModernBackend(MessageContext messageContext, API matchedApi,
                                                              McpRequest request, String method,
                                                              Map<String, Object> headers) {
        // initialize / notifications/initialized are answered locally for modern backends.
        if (APIConstants.MCP.METHOD_INITIALIZE.equals(method)
                || APIConstants.MCP.METHOD_NOTIFICATION_INITIALIZED.equals(method)) {
            return false;
        }
        headers.remove(APIConstants.MCP.HEADER_MCP_SESSION_ID);
        headers.put(APIConstants.MCP.HEADER_MCP_METHOD, method);
        headers.put(APIConstants.MCP.MCP_PROTOCOL_VERSION_HEADER, APIConstants.MCP.PROTOCOL_VERSION_2026_JULY);

        org.apache.axis2.context.MessageContext axis2MC =
                ((Axis2MessageContext) messageContext).getAxis2MessageContext();
        // Map legacy tools/list to modern server/discover when appropriate is not required —
        // tools/list remains valid; ensure _meta is attached.
        ensureMetaOnRequestBody(axis2MC, request, APIConstants.MCP.PROTOCOL_VERSION_2026_JULY);
        return true;
    }

    private static boolean prepareModernClientToLegacyBackend(MessageContext messageContext, API matchedApi,
                                                              McpRequest request, String method,
                                                              Map<String, Object> headers) {
        // server/discover is answered locally against gateway capabilities for legacy backends.
        if (APIConstants.MCP.METHOD_SERVER_DISCOVER.equals(method)) {
            return false;
        }
        headers.put(APIConstants.MCP.MCP_PROTOCOL_VERSION_HEADER, APIConstants.MCP.PROTOCOL_VERSION_2025_JUNE);
        headers.remove(APIConstants.MCP.HEADER_MCP_METHOD);
        headers.remove(APIConstants.MCP.HEADER_MCP_NAME);

        String cacheKey = buildSessionCacheKey(messageContext, matchedApi);
        String sessionId = MCPSessionCache.getInstance().get(cacheKey);
        if (StringUtils.isNotEmpty(sessionId)) {
            headers.put(APIConstants.MCP.HEADER_MCP_SESSION_ID, sessionId);
            messageContext.setProperty(APIMgtGatewayConstants.MCP_SESSION_ID_KEY, sessionId);
        } else {
            // Session will be established lazily by the publisher-style handshake on first call
            // if the backend requires it; mark so OUT flow can cache returned session.
            messageContext.setProperty(APIMgtGatewayConstants.MCP_SESSION_ID_KEY, null);
        }
        return true;
    }

    private static void ensureMetaOnRequestBody(org.apache.axis2.context.MessageContext axis2MC,
                                                McpRequest request, String protocolVersion) {
        try {
            if (!org.apache.synapse.commons.json.JsonUtil.hasAJsonPayload(axis2MC)) {
                return;
            }
            String body = org.apache.synapse.commons.json.JsonUtil.jsonPayloadToString(axis2MC);
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonObject meta;
            if (root.has(APIConstants.MCP.META_KEY) && root.get(APIConstants.MCP.META_KEY).isJsonObject()) {
                meta = root.getAsJsonObject(APIConstants.MCP.META_KEY);
            } else {
                meta = new JsonObject();
                root.add(APIConstants.MCP.META_KEY, meta);
            }
            if (!meta.has(APIConstants.MCP.PROTOCOL_VERSION_KEY)) {
                meta.addProperty(APIConstants.MCP.PROTOCOL_VERSION_KEY, protocolVersion);
            }
            if (request.getMeta() == null) {
                Map<String, Object> metaMap = new HashMap<>();
                metaMap.put(APIConstants.MCP.PROTOCOL_VERSION_KEY, protocolVersion);
                request.setMeta(metaMap);
            }
            org.apache.synapse.commons.json.JsonUtil.removeJsonPayload(axis2MC);
            org.apache.synapse.commons.json.JsonUtil.getNewJsonPayload(axis2MC, root.toString(), true, true);
        } catch (Exception e) {
            // Best-effort enrichment; leave original body on failure.
        }
    }

    @SuppressWarnings("unchecked")
    private static void stripSessionHeader(MessageContext messageContext) {
        org.apache.axis2.context.MessageContext axis2MC =
                ((Axis2MessageContext) messageContext).getAxis2MessageContext();
        Object headersObj = axis2MC.getProperty(org.apache.axis2.context.MessageContext.TRANSPORT_HEADERS);
        if (headersObj instanceof Map) {
            ((Map<String, Object>) headersObj).remove(APIConstants.MCP.HEADER_MCP_SESSION_ID);
        }
    }

    @SuppressWarnings("unchecked")
    private static void captureSessionFromResponse(MessageContext messageContext, String responseBody) {
        org.apache.axis2.context.MessageContext axis2MC =
                ((Axis2MessageContext) messageContext).getAxis2MessageContext();
        Object headersObj = axis2MC.getProperty(org.apache.axis2.context.MessageContext.TRANSPORT_HEADERS);
        String sessionId = null;
        if (headersObj instanceof Map) {
            Object value = ((Map<String, Object>) headersObj).get(APIConstants.MCP.HEADER_MCP_SESSION_ID);
            if (value != null) {
                sessionId = String.valueOf(value);
            }
        }
        if (StringUtils.isEmpty(sessionId)) {
            return;
        }
        API matchedApi = GatewayUtils.getAPI(messageContext);
        String cacheKey = buildSessionCacheKey(messageContext, matchedApi);
        MCPSessionCache.getInstance().put(cacheKey, sessionId);
    }

    private static String mapModernErrorCodesToLegacyIfNeeded(String responseBody) {
        // Keep body as-is for now; error-code remapping can be expanded per-method.
        try {
            JsonElement element = JsonParser.parseString(responseBody);
            if (!element.isJsonObject()) {
                return responseBody;
            }
            JsonObject root = element.getAsJsonObject();
            if (root.has("error") && root.get("error").isJsonObject()) {
                JsonObject error = root.getAsJsonObject("error");
                if (error.has("code") && error.get("code").getAsInt()
                        == APIConstants.MCP.RpcConstants.INVALID_PARAMS_CODE) {
                    // Resource-not-found moved to -32602 in modern; leave as-is for legacy clients
                    // when message indicates missing resource — no remap needed for tools/call.
                }
            }
            return responseBody;
        } catch (Exception e) {
            return responseBody;
        }
    }

    public static String buildSessionCacheKey(MessageContext messageContext, API matchedApi) {
        String apiId = matchedApi != null ? matchedApi.getUuid() : "unknown";
        String authHint = MCPProtocolNegotiator.getTransportHeader(messageContext, "Authorization");
        if (StringUtils.isEmpty(authHint)) {
            authHint = MCPProtocolNegotiator.getTransportHeader(messageContext, "apiKey");
        }
        if (StringUtils.isEmpty(authHint)) {
            authHint = "anonymous";
        }
        return apiId + ":" + Integer.toHexString(authHint.hashCode());
    }

    /**
     * Issues a new legacy session id for northbound initialize responses.
     */
    public static String issueNorthboundSessionId() {
        return UUID.randomUUID().toString();
    }
}
