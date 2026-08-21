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

package org.wso2.carbon.apimgt.gateway.utils;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.apache.axis2.AxisFault;
import org.apache.axis2.Constants;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.util.EntityUtils;
import org.apache.synapse.Mediator;
import org.apache.synapse.MessageContext;
import org.apache.synapse.SynapseConstants;
import org.apache.synapse.api.ApiUtils;
import org.apache.synapse.commons.json.JsonUtil;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.rest.RESTConstants;
import org.apache.synapse.transport.nhttp.NhttpConstants;
import org.apache.synapse.transport.passthru.PassThroughConstants;
import org.apache.synapse.transport.passthru.util.RelayUtils;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.model.APIOperationMapping;
import org.wso2.carbon.apimgt.api.model.BackendOperation;
import org.wso2.carbon.apimgt.api.model.BackendOperationMapping;
import org.wso2.carbon.apimgt.api.model.KeyManager;
import org.wso2.carbon.apimgt.api.model.KeyManagerConfiguration;
import org.wso2.carbon.apimgt.api.model.VHost;
import org.wso2.carbon.apimgt.api.model.subscription.URLMapping;
import org.wso2.carbon.apimgt.gateway.APIMgtGatewayConstants;
import org.wso2.carbon.apimgt.gateway.dto.OAuthProtectedResourceDTO;
import org.wso2.carbon.apimgt.gateway.exception.McpException;
import org.wso2.carbon.apimgt.gateway.exception.McpExceptionWithId;
import org.wso2.carbon.apimgt.gateway.handlers.Utils;
import org.wso2.carbon.apimgt.gateway.handlers.security.APISecurityConstants;
import org.wso2.carbon.apimgt.gateway.handlers.streaming.sse.SseApiConstants;
import org.wso2.carbon.apimgt.api.dto.KeyManagerConfigurationDTO;
import org.wso2.carbon.apimgt.gateway.internal.DataHolder;
import org.wso2.carbon.apimgt.gateway.internal.ServiceReferenceHolder;
import org.wso2.carbon.apimgt.impl.dto.EventHubConfigurationDto;
import org.wso2.carbon.apimgt.gateway.mcp.request.McpRequest;
import org.wso2.carbon.apimgt.gateway.mcp.request.Params;
import org.wso2.carbon.apimgt.gateway.mcp.response.McpResponseDto;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.impl.dto.KeyManagerDto;
import org.wso2.carbon.apimgt.impl.factory.KeyManagerHolder;
import org.wso2.carbon.apimgt.impl.kmclient.model.OpenIdConnectConfiguration;
import org.wso2.carbon.apimgt.impl.utils.APIUtil;
import org.wso2.carbon.apimgt.keymgt.SubscriptionDataHolder;
import org.wso2.carbon.apimgt.keymgt.model.SubscriptionDataStore;
import org.wso2.carbon.apimgt.keymgt.model.entity.API;
import org.wso2.carbon.apimgt.keymgt.model.impl.SubscriptionDataLoaderImpl;
import org.wso2.carbon.mcp.transformer.exception.MCPRequestResolverException;
import org.wso2.carbon.mcp.transformer.impl.PrefixBasedSchemaMappingParser;
import org.wso2.carbon.mcp.transformer.impl.RequestResolver;
import org.wso2.carbon.mcp.transformer.model.ResolvedRequest;
import org.wso2.carbon.mcp.transformer.model.SchemaMapping;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.TreeMap;
import java.util.regex.Pattern;

public class MCPUtils {

    private static final Log log = LogFactory.getLog(MCPUtils.class);

    private static final Pattern VALID_HOST_HEADER_PATTERN =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9.-]*(:\\d{1,5})?$");

    /**
     * Cache disabled Key Manager configs loaded from Event Hub for MCP metadata only (not for token validation).
     */
    private static final Map<String, KeyManagerConfigurationDTO> MCP_METADATA_KEY_MANAGER_CACHE =
            new ConcurrentHashMap<>();

    /**
     * Tenant-level cache of Key Manager lists fetched from Event Hub for MCP well-known metadata.
     */
    private static final Map<String, EventHubKeyManagerTenantCacheEntry> EVENT_HUB_KM_TENANT_CACHE =
            new ConcurrentHashMap<>();

    private static final long EVENT_HUB_KM_TENANT_CACHE_TTL_MS = 15 * 60 * 1000L;

    /**
     * Cache for MCP API context lookups (well-known metadata). Avoids scanning all APIs per request.
     */
    private static final Map<String, API> MCP_API_CONTEXT_LOOKUP_CACHE = new ConcurrentHashMap<>();

    private static final int MCP_API_CONTEXT_LOOKUP_CACHE_MAX = 512;

    private static final Gson MCP_METADATA_GSON = new Gson();

    private static final class EventHubKeyManagerTenantCacheEntry {

        private final List<KeyManagerConfigurationDTO> configurations;
        private final long loadedAtMs;

        private EventHubKeyManagerTenantCacheEntry(List<KeyManagerConfigurationDTO> configurations,
                                                   long loadedAtMs) {
            this.configurations = configurations;
            this.loadedAtMs = loadedAtMs;
        }

        private boolean isExpired() {
            return System.currentTimeMillis() - loadedAtMs > EVENT_HUB_KM_TENANT_CACHE_TTL_MS;
        }
    }
    /**
     * Validates the MCP request.
     *
     * @param request MCP request object
     * @return true if the request is valid, false otherwise
     * @throws McpException if the request is invalid
     */
    public static boolean validateRequest(McpRequest request) throws McpException {
        String jsonRpcVersion = request.getJsonRpcVersion();
        if (StringUtils.isEmpty(jsonRpcVersion)) {
            throw new McpException(APIConstants.MCP.RpcConstants.INVALID_REQUEST_CODE,
                    APIConstants.MCP.RpcConstants.INVALID_REQUEST_MESSAGE, "Missing jsonrpc field");
        }
        if (!APIConstants.MCP.RpcConstants.JSON_RPC_VERSION.equals(jsonRpcVersion)) {
            throw new McpException(APIConstants.MCP.RpcConstants.INVALID_REQUEST_CODE,
                    APIConstants.MCP.RpcConstants.INVALID_REQUEST_MESSAGE, "Invalid JSON-RPC version");
        }

        String method = request.getMethod();
        if (StringUtils.isEmpty(method)) {
            throw new McpException(APIConstants.MCP.RpcConstants.INVALID_REQUEST_CODE,
                    APIConstants.MCP.RpcConstants.INVALID_REQUEST_MESSAGE, "Missing method field");
        }
        if (!APIConstants.MCP.ALLOWED_METHODS.contains(method)) {
            throw new McpException(APIConstants.MCP.RpcConstants.METHOD_NOT_FOUND_CODE,
                    APIConstants.MCP.RpcConstants.METHOD_NOT_FOUND_MESSAGE, "Method " + method + " not found");
        }

        Object id = request.getId();
        if (id == null && !APIConstants.MCP.METHOD_NOTIFICATION_INITIALIZED.equals(method)) {
            throw new McpException(APIConstants.MCP.RpcConstants.INVALID_REQUEST_CODE,
                    APIConstants.MCP.RpcConstants.INVALID_REQUEST_MESSAGE, "Missing id field");
        }
        return true;
    }

    /**
     * Processes the MCP requests for existing APIs and direct backends.
     *
     * @param matchedMcpApi matched API in the gateway
     * @param requestObject MCP request payload
     * @param method        MCP JSON RPC method
     * @return the response payload as a String
     */
    public static McpResponseDto processInternalRequest(MessageContext messageContext, API matchedMcpApi,
                                                        McpRequest requestObject, String method) {
        try {
            Object id = -1;
            if (!method.contains("notifications/")) {
                id = requestObject.getId();
            }

            switch (method) {
                case APIConstants.MCP.METHOD_INITIALIZE:
                    validateInitializeRequest(id, requestObject);
                    return handleMcpInitialize(messageContext, id, matchedMcpApi);
                case APIConstants.MCP.METHOD_SERVER_DISCOVER:
                    return handleMcpServerDiscover(id, matchedMcpApi);
                case APIConstants.MCP.METHOD_TOOL_LIST:
                    return handleMcpToolList(id, matchedMcpApi, false);
                case APIConstants.MCP.METHOD_TOOL_CALL:
                    validateToolsCallRequest(requestObject, matchedMcpApi);
                    return handleMcpToolsCall(messageContext, id, matchedMcpApi, requestObject);
                case APIConstants.MCP.METHOD_PING:
                    return handleMcpPing(id);
                case APIConstants.MCP.METHOD_RESOURCES_LIST:
                    return new McpResponseDto(MCPPayloadGenerator.generateResourceListResponse(id), 200, null);
                case APIConstants.MCP.METHOD_RESOURCES_READ:
                    return new McpResponseDto(MCPPayloadGenerator.generateResourceReadResponse(id), 200, null);
                case APIConstants.MCP.METHOD_RESOURCE_TEMPLATE_LIST:
                    return new McpResponseDto(MCPPayloadGenerator.generateResourceTemplateListResponse(id), 200, null);
                case APIConstants.MCP.METHOD_PROMPTS_LIST:
                    return new McpResponseDto(MCPPayloadGenerator.generatePromptListResponse(id), 200, null);
                case APIConstants.MCP.METHOD_NOTIFICATION_INITIALIZED:
                    // We don't need to send a reply when it's a notification
                    return null;
                default:
                    throw new McpException(APIConstants.MCP.RpcConstants.METHOD_NOT_FOUND_CODE,
                            APIConstants.MCP.RpcConstants.METHOD_NOT_FOUND_MESSAGE, "Method not found");
            }
        } catch (McpException e) {
            int httpStatus = e.getErrorCode() == APIConstants.MCP.RpcConstants.METHOD_NOT_FOUND_CODE
                    ? HttpStatus.SC_METHOD_NOT_ALLOWED
                    : HttpStatus.SC_OK;
            return new McpResponseDto(e.toJsonRpcErrorPayload(), httpStatus, null);
        }
    }

    /**
     * Validates the MCP initialize request.
     *
     * @param id            id of the request
     * @param requestObject MCP request object
     * @throws McpException if the request is invalid
     */
    public static void validateInitializeRequest(Object id, McpRequest requestObject) throws McpException {
        if (requestObject.getParams() != null) {
            Params params = requestObject.getParams();
            String protocolVersion = params.getProtocolVersion();
            if (!StringUtils.isEmpty(protocolVersion)) {
                if (!APIConstants.MCP.SUPPORTED_PROTOCOL_VERSIONS.contains(protocolVersion)) {
                    throw new McpExceptionWithId(id, APIConstants.MCP.RpcConstants.INVALID_PARAMS_CODE,
                            APIConstants.MCP.PROTOCOL_MISMATCH_ERROR,
                            MCPPayloadGenerator.getInitializeErrorBody(protocolVersion));
                }
            } else {
                throw new McpException(APIConstants.MCP.RpcConstants.INVALID_REQUEST_CODE,
                        APIConstants.MCP.RpcConstants.INVALID_REQUEST_MESSAGE, "Missing protocolVersion field");
            }
        } else {
            throw new McpException(APIConstants.MCP.RpcConstants.INVALID_REQUEST_CODE,
                    APIConstants.MCP.RpcConstants.INVALID_REQUEST_MESSAGE, "Missing params field");
        }
    }

    /**
     * Validates the MCP tools/call request.
     *
     * @param mcpRequest MCP request object
     * @param matchedApi matched API in the gateway
     * @throws McpException if the request is invalid
     */
    private static void validateToolsCallRequest(McpRequest mcpRequest, API matchedApi) throws McpException {
        Params params = mcpRequest.getParams();
        if (params != null) {
            String toolName = params.getToolName();
            if (StringUtils.isEmpty(toolName)) {
                throw new McpException(APIConstants.MCP.RpcConstants.INVALID_REQUEST_CODE,
                        APIConstants.MCP.RpcConstants.INVALID_REQUEST_MESSAGE, "Missing toolName field");
            } else {
                if (!validateToolName(toolName, matchedApi)) {
                    throw new McpException(APIConstants.MCP.RpcConstants.INVALID_REQUEST_CODE,
                            APIConstants.MCP.RpcConstants.INVALID_REQUEST_MESSAGE, "The requested tool does not exist");
                }
            }
        } else {
            throw new McpException(APIConstants.MCP.RpcConstants.INVALID_REQUEST_CODE,
                    APIConstants.MCP.RpcConstants.INVALID_REQUEST_MESSAGE, "Missing params field");
        }
    }

    private static boolean validateToolName(String toolName, API matchedApi) {
        return matchedApi.getUrlMappings()
                .stream()
                .anyMatch(operation -> toolName.equals(operation.getUrlPattern()));
    }


    /**
     * Handles the MCP initialize request.
     *
     * @param messageContext message context (for negotiated protocol version / session)
     * @param id             id of the request
     * @param matchedApi     matched API in the gateway
     * @return the response payload as a String
     */
    public static McpResponseDto handleMcpInitialize(MessageContext messageContext, Object id, API matchedApi) {
        String name = matchedApi.getName();
        String version = matchedApi.getVersion();
        String description = "This is an MCP Server";
        Object negotiated = messageContext != null
                ? messageContext.getProperty(APIMgtGatewayConstants.MCP_PROTOCOL_VERSION_KEY) : null;
        String protocolVersion = negotiated != null ? String.valueOf(negotiated)
                : APIConstants.MCP.PROTOCOL_VERSION_2025_JUNE;
        String sessionId = null;
        if (APIConstants.MCP.isLegacyProtocol(protocolVersion)) {
            sessionId = MCPProtocolTranslator.issueNorthboundSessionId();
            if (messageContext != null) {
                messageContext.setProperty(APIMgtGatewayConstants.MCP_SESSION_ID_KEY, sessionId);
            }
        }
        return new McpResponseDto(MCPPayloadGenerator
                .getInitializeResponse(id, name, version, description, false, protocolVersion),
                200, sessionId);
    }

    /**
     * Backward-compatible initialize handler without message context.
     */
    public static McpResponseDto handleMcpInitialize(Object id, API matchedApi) {
        return handleMcpInitialize(null, id, matchedApi);
    }

    /**
     * Handles MCP 2.0 {@code server/discover}.
     */
    public static McpResponseDto handleMcpServerDiscover(Object id, API matchedApi) {
        String name = matchedApi.getName();
        String version = matchedApi.getVersion();
        String description = "This is an MCP Server";
        return new McpResponseDto(
                MCPPayloadGenerator.getServerDiscoverResponse(id, name, version, description, false),
                200, null);
    }

    /**
     * Handles the MCP tools/list request.
     *
     * @param id           id of the request
     * @param matchedApi   matched API in the gateway
     * @param isThirdParty whether the request is from a third-party client
     * @return the response payload as a String
     */
    public static McpResponseDto handleMcpToolList(Object id, API matchedApi, boolean isThirdParty) {
        return new McpResponseDto(
                MCPPayloadGenerator.generateToolListPayload(id, matchedApi.getUrlMappings(),
                        isThirdParty), 200, null);
    }

    /**
     * Handles the MCP tools/call request.
     *
     * @param messageContext message context of the request
     * @param id             id of the request
     * @param matchedApi     matched API in the gateway
     * @param mcpRequest     MCP request object
     * @return the response payload as a String
     * @throws McpException if an error occurs while processing the request
     */
    private static McpResponseDto handleMcpToolsCall(MessageContext messageContext, Object id, API matchedApi,
                                                     McpRequest mcpRequest) throws McpException {
        Params params = mcpRequest.getParams();
        if (params != null) {
            String toolName = params.getToolName();
            URLMapping extendedOperation = matchedApi.getUrlMappings()
                    .stream()
                    .filter(operation -> operation.getUrlPattern().equals(toolName))
                    .findFirst()
                    .orElse(null);
            String subType = matchedApi.getSubtype();
            transformMcpRequest(messageContext, id, extendedOperation, mcpRequest, subType);
        }
        return null;
    }

    /**
     * Handles the MCP ping request.
     *
     * @param id id of the request
     * @return the response payload as a String
     */
    private static McpResponseDto handleMcpPing(Object id) {
        return new McpResponseDto(MCPPayloadGenerator.generatePingResponse(id), 200, null);
    }

    /**
     * Transforms the MCP request to a standard REST request.
     *
     * @param messageContext    message context of the request
     * @param id                id of the request
     * @param extendedOperation matched operation in the API
     * @param mcpRequest        MCP request object
     * @param subType           subtype of the API (existing API or direct backend)
     * @throws McpException if an error occurs while transforming the request
     */
    private static void transformMcpRequest(MessageContext messageContext, Object id, URLMapping extendedOperation,
                                            McpRequest mcpRequest, String subType) throws McpException {
        if (extendedOperation != null) {
            BackendOperation backendOperation = null;
            if (APIConstants.API_SUBTYPE_EXISTING_API.equals(subType)) {
                APIOperationMapping apiOperationMapping = extendedOperation.getApiOperationMapping();
                if (apiOperationMapping != null) {
                    backendOperation = apiOperationMapping.getBackendOperation();
                }
            } else if (APIConstants.API_SUBTYPE_DIRECT_BACKEND.equals(subType)) {
                BackendOperationMapping backendOperationMapping = extendedOperation.getBackendOperationMapping();
                if (backendOperationMapping != null) {
                    backendOperation = backendOperationMapping.getBackendOperation();
                }
            }

            if (backendOperation != null) {
                //process schema
                String schemaDefinition = extendedOperation.getSchemaDefinition();
                if (StringUtils.isEmpty(schemaDefinition)) {
                    throw new McpException(APIConstants.MCP.RpcConstants.INTERNAL_ERROR_CODE,
                            APIConstants.MCP.RpcConstants.INTERNAL_ERROR_MESSAGE,
                            "No schema defined for the requested tool: " + extendedOperation.getUrlPattern());
                }

                PrefixBasedSchemaMappingParser parser = new PrefixBasedSchemaMappingParser();
                RequestResolver requestResolver = new RequestResolver(parser);
                Map<String, Object> arguments = new HashMap<>();
                if (mcpRequest.getParams() != null && mcpRequest.getParams().getArguments() != null) {
                    arguments = mcpRequest.getParams().getArguments();
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug("No parameter arguments found in the MCP request");
                    }
                }

                try {
                    ResolvedRequest resolvedRequest = requestResolver.resolve(schemaDefinition, arguments);

                    //process resource path including query and path params
                    processResource(messageContext, resolvedRequest, backendOperation);

                    //process headers
                    processHeaders(messageContext, resolvedRequest);

                    //process request body
                    SchemaMapping parsedSchemaDefinition = parser.parse(schemaDefinition);
                    processRequestBody(messageContext, resolvedRequest, parsedSchemaDefinition.isHasBody(),
                            parsedSchemaDefinition.getContentType());

                    //set received id to msg context
                    messageContext.setProperty(APIConstants.MCP.RECEIVED_MCP_ID, id);
                } catch (MCPRequestResolverException e) {
                    throw new McpExceptionWithId(id, APIConstants.MCP.RpcConstants.INTERNAL_ERROR_CODE,
                            APIConstants.MCP.RpcConstants.INTERNAL_ERROR_MESSAGE, e.getMessage());
                }
            } else {
                throw new McpExceptionWithId(id, APIConstants.MCP.RpcConstants.INTERNAL_ERROR_CODE,
                        APIConstants.MCP.RpcConstants.INTERNAL_ERROR_MESSAGE, "No matched tool found");
            }
        }
    }

    /**
     * Processes the resource path, query parameters, and path parameters.
     *
     * @param messageContext   message context of the request
     * @param resolvedRequest  resolved request object
     * @param backendOperation backend operation object
     * @throws McpException if an error occurs while processing the resource
     */
    private static void processResource(MessageContext messageContext, ResolvedRequest resolvedRequest,
                                        BackendOperation backendOperation) throws McpException {
        //remove fully qualified path
        org.wso2.carbon.apimgt.api.APIConstants.SupportedHTTPVerbs httpMethod = backendOperation.getVerb();
        String target = backendOperation.getTarget();

        StringBuilder resourcePath = new StringBuilder();
        StringBuilder queryString = new StringBuilder();
        resourcePath.append(target);
        Map<String, Object> queryParams = resolvedRequest.getQueryParams();

        if (queryParams != null && !queryParams.isEmpty()) {
            for (Object key : queryParams.keySet()) {
                String paramName = key.toString();
                Object paramValueObj = queryParams.get(paramName);
                String paramValue = paramValueObj != null ? paramValueObj.toString() : "";
                if (!StringUtils.isEmpty(paramValue)) {
                    if (queryString.length() == 0) {
                        queryString.append(paramName).append("=").append(paramValue);
                    } else {
                        queryString.append("&").append(paramName).append("=").append(paramValue);
                    }
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug("Query param " + paramName + " is defined but has no value, hence skipped");
                    }
                }
            }
        }

        if (queryString.length() > 0) {
            resourcePath.append("?").append(queryString);
        }

        Map<String, Object> pathParams = resolvedRequest.getPathParams();
        String resourcePathString = resourcePath.toString();

        if (pathParams != null && !pathParams.isEmpty()) {
            for (Object key : pathParams.keySet()) {
                Object paramValueObj = pathParams.get(key);
                if (paramValueObj == null) {
                    //param value obj cannot be null at this point
                    throw new McpException(APIConstants.MCP.RpcConstants.INVALID_PARAMS_CODE,
                            APIConstants.MCP.RpcConstants.INVALID_PARAMS_MESSAGE,
                            "Required path param " + key + " is not defined");
                }
                String paramValue = paramValueObj.toString();
                resourcePathString = resourcePathString.replace("{" + key + "}", paramValue);
            }
        }

        resourcePath.setLength(0);
        resourcePath.append(resourcePathString);

        org.apache.axis2.context.MessageContext axis2MessageContext =
                ((Axis2MessageContext) messageContext).getAxis2MessageContext();
        axis2MessageContext.setProperty(APIMgtGatewayConstants.REST_URL_POSTFIX, resourcePath.toString());
        axis2MessageContext.setProperty(APIConstants.RESOURCE_METHOD, httpMethod.toString().toUpperCase());
    }

    /**
     * Processes the headers of the request.
     *
     * @param messageContext  message context of the request
     * @param resolvedRequest resolved request object
     */
    public static void processHeaders(MessageContext messageContext, ResolvedRequest resolvedRequest) {
        Map<String, Object> headerParams = resolvedRequest.getHeaderParams();
        if (headerParams != null && !headerParams.isEmpty()) {
            org.apache.axis2.context.MessageContext axis2MessageContext =
                    ((Axis2MessageContext) messageContext).getAxis2MessageContext();
            Map headers =
                    (Map) axis2MessageContext.getProperty(org.apache.axis2.context.MessageContext.TRANSPORT_HEADERS);
            for (Object key : headerParams.keySet()) {
                String paramName = (String) key;
                Object paramValueObj = headerParams.get(paramName);
                if (paramValueObj != null) {
                    String paramValue = paramValueObj.toString();
                    headers.put(paramName, paramValue);
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug("Header param " + paramName + " is defined but has no value, hence skipped");
                    }
                }
            }
        }
    }

    /**
     * Processes the request body of the request.
     *
     * @param messageContext  message context of the request
     * @param resolvedRequest resolved request object
     * @param hasBody         whether the request has a body
     * @param contentType     content type of the request body
     * @throws McpException if an error occurs while processing the request body
     */
    public static void processRequestBody(MessageContext messageContext, ResolvedRequest resolvedRequest,
                                          boolean hasBody, String contentType) throws McpException {

        if (hasBody) {
            org.apache.axis2.context.MessageContext axis2MessageContext =
                    ((Axis2MessageContext) messageContext).getAxis2MessageContext();
            JsonObject payload = resolvedRequest.getBody();

            String ct = contentType != null ? contentType : "";
            if (ct.startsWith(APIConstants.APPLICATION_JSON_MEDIA_TYPE)) {
                try {
                    JsonUtil.removeJsonPayload(axis2MessageContext);
                    JsonObject safePayload = (payload != null) ? payload : new JsonObject();
                    JsonUtil.getNewJsonPayload(axis2MessageContext, new Gson().toJson(safePayload), true, true);
                    axis2MessageContext.setProperty(Constants.Configuration.MESSAGE_TYPE,
                            APIConstants.APPLICATION_JSON_MEDIA_TYPE);
                    axis2MessageContext.setProperty(Constants.Configuration.CONTENT_TYPE,
                            APIConstants.APPLICATION_JSON_MEDIA_TYPE);
                } catch (AxisFault e) {
                    throw new McpException(APIConstants.MCP.RpcConstants.INTERNAL_ERROR_CODE,
                            APIConstants.MCP.RpcConstants.INTERNAL_ERROR_MESSAGE,
                            "Failed to process JSON request body: " + e.getMessage());
                }
            } else {
                throw new McpException(APIConstants.MCP.RpcConstants.INTERNAL_ERROR_CODE,
                        APIConstants.MCP.RpcConstants.INTERNAL_ERROR_MESSAGE,
                        "Unsupported content type: " + contentType);
            }
        }
    }

    /**
     * Throws an error for a missing jsonrpc field.
     *
     * @throws McpException always
     */
    private static void throwMissingJsonRpcError() throws McpException {
        throw new McpException(APIConstants.MCP.RpcConstants.INVALID_REQUEST_CODE,
                APIConstants.MCP.RpcConstants.INVALID_REQUEST_MESSAGE, "Missing jsonrpc field");
    }

    /**
     * Throws an error for an invalid jsonrpc version.
     *
     * @throws McpException always
     */
    private static void throwMissingIdError() throws McpException {
        throw new McpException(APIConstants.MCP.RpcConstants.INVALID_REQUEST_CODE,
                APIConstants.MCP.RpcConstants.INVALID_REQUEST_MESSAGE, "Missing id field");
    }

    /**
     * Throws an error for a missing method field.
     *
     * @throws McpException always
     */
    private static void throwMissingMethodError() throws McpException {
        throw new McpException(APIConstants.MCP.RpcConstants.INVALID_REQUEST_CODE,
                APIConstants.MCP.RpcConstants.INVALID_REQUEST_MESSAGE, "Missing method field");
    }

    /**
     * Consumes and discards any buffered pass-through payload so fault responses do not retain Pipe data.
     *
     * @param messageContext Synapse message context
     */
    public static void discardPassthroughMessage(MessageContext messageContext) {
        if (messageContext == null) {
            return;
        }
        org.apache.axis2.context.MessageContext axis2MC =
                ((Axis2MessageContext) messageContext).getAxis2MessageContext();
        axis2MC.setProperty(PassThroughConstants.MESSAGE_BUILDER_INVOKED, Boolean.TRUE);
        try {
            RelayUtils.consumeAndDiscardMessage(axis2MC);
        } catch (AxisFault axisFault) {
            log.error("Error occurred while consuming and discarding the passthrough message", axisFault);
        }
    }

    /**
     * Marks an MCP Streamable HTTP GET channel as an async SSE stream for passthrough lifecycle hooks.
     *
     * @param messageContext Synapse message context
     */
    public static void markMcpStreamableHttpAsAsync(MessageContext messageContext) {
        org.apache.axis2.context.MessageContext axis2MC =
                ((Axis2MessageContext) messageContext).getAxis2MessageContext();
        axis2MC.setProperty(PassThroughConstants.SYNAPSE_ARTIFACT_TYPE, APIConstants.API_TYPE_SSE);
        messageContext.setProperty(
                org.wso2.carbon.apimgt.gateway.handlers.analytics.Constants.IS_ASYNC_API, true);
        messageContext.setProperty(APIConstants.AsyncApi.ASYNC_MESSAGE_TYPE,
                APIConstants.AsyncApi.ASYNC_MESSAGE_TYPE_SUBSCRIBE);
    }

    /**
     * This method handles failures
     *
     * @param messageContext message context of the request
     * @param responseDto    payload of the error
     */
    public static void handleMCPFailure(MessageContext messageContext, McpResponseDto responseDto) {
        messageContext.setProperty("MCP_ID", null);
        messageContext.setProperty(SynapseConstants.ERROR_MESSAGE, responseDto.getResponse());
        messageContext.setProperty("MCP_ERROR_CODE", responseDto.getStatusCode());
        Mediator sequence = messageContext.getSequence(APIConstants.MCP.MCP_FAILURE_HANDLER);
        if (sequence != null && !sequence.mediate(messageContext)) {
            return;
        }

        // Fallback in case failure sequence is not defined
        org.apache.axis2.context.MessageContext axis2MC =
                ((Axis2MessageContext) messageContext).getAxis2MessageContext();
        try {
            JsonUtil.removeJsonPayload(axis2MC);
            JsonUtil.getNewJsonPayload(axis2MC, MCPPayloadGenerator.getErrorResponse(null,
                    responseDto.getStatusCode(), "MCP Failure", responseDto.getResponse()), true, true);
            axis2MC.setProperty(Constants.Configuration.MESSAGE_TYPE, APIConstants.APPLICATION_JSON_MEDIA_TYPE);
            axis2MC.setProperty(Constants.Configuration.CONTENT_TYPE, APIConstants.APPLICATION_JSON_MEDIA_TYPE);
        } catch (AxisFault e) {
            log.warn("Failed to set MCP error JSON payload", e);
        }
        if (responseDto.getStatusCode() == HttpStatus.SC_UNAUTHORIZED) {
            setMcpWwwAuthenticateHeader(messageContext, HttpStatus.SC_UNAUTHORIZED, null, null);
        }
        discardPassthroughMessage(messageContext);
        Utils.sendFault(messageContext, responseDto.getStatusCode());
    }

    /**
     * Builds the oauth-protected-resource metadata URL for the current MCP API request.
     *
     * @param messageContext Synapse message context
     * @return Metadata URL (gateway base + API context + well-known path), or null if it cannot be resolved
     */
    public static String buildOAuthProtectedResourceMetadataUrl(MessageContext messageContext) {
        String contextPath = (String) messageContext.getProperty(RESTConstants.REST_API_CONTEXT);
        if (StringUtils.isEmpty(contextPath)) {
            return null;
        }
        org.apache.axis2.context.MessageContext axis2MC =
                ((Axis2MessageContext) messageContext).getAxis2MessageContext();
        Map headers = (Map) axis2MC.getProperty(org.apache.axis2.context.MessageContext.TRANSPORT_HEADERS);
        String hostHeader = headers != null ? (String) headers.get(APIMgtGatewayConstants.HOST) : null;
        if (StringUtils.isBlank(hostHeader) || !VALID_HOST_HEADER_PATTERN.matcher(hostHeader).matches()) {
            hostHeader = APIUtil.getHostAddress();
        }
        String gatewayUrl = getGatewayServerURL(hostHeader, contextPath);
        if (StringUtils.isEmpty(gatewayUrl)) {
            return null;
        }
        return gatewayUrl + contextPath + APIMgtGatewayConstants.MCP_WELL_KNOWN_RESOURCE;
    }

    /**
     * Sets the MCP {@code WWW-Authenticate} response header for OAuth discovery (RFC 9728 style).
     *
     * @param messageContext        Synapse message context
     * @param statusCode            HTTP status code of the response
     * @param oauthError            OAuth error code (e.g. invalid_request, invalid_token); optional
     * @param oauthErrorDescription Human-readable error description; optional
     */
    public static void setMcpWwwAuthenticateHeader(MessageContext messageContext, int statusCode, String oauthError,
                                                   String oauthErrorDescription) {
        if (statusCode != HttpStatus.SC_UNAUTHORIZED) {
            return;
        }
        String resourceMetadataUrl = buildOAuthProtectedResourceMetadataUrl(messageContext);
        if (StringUtils.isEmpty(resourceMetadataUrl)) {
            if (log.isDebugEnabled()) {
                log.debug("Skipping WWW-Authenticate header: unable to resolve oauth-protected-resource metadata URL");
            }
            return;
        }
        if (StringUtils.isEmpty(oauthError)) {
            Object errorCode = messageContext.getProperty(SynapseConstants.ERROR_CODE);
            if (errorCode instanceof Integer
                    && (Integer) errorCode == APISecurityConstants.API_AUTH_MISSING_CREDENTIALS) {
                oauthError = "invalid_request";
                oauthErrorDescription = "Access token is missing";
            } else {
                oauthError = "invalid_token";
                oauthErrorDescription = "Access token is missing or invalid";
            }
        }
        org.apache.axis2.context.MessageContext axis2MC =
                ((Axis2MessageContext) messageContext).getAxis2MessageContext();
        Map headers = (Map) axis2MC.getProperty(org.apache.axis2.context.MessageContext.TRANSPORT_HEADERS);
        if (headers == null) {
            headers = new HashMap();
            axis2MC.setProperty(org.apache.axis2.context.MessageContext.TRANSPORT_HEADERS, headers);
        }
        StringBuilder wwwAuthenticate = new StringBuilder("Bearer resource_metadata=\"")
                .append(resourceMetadataUrl).append("\"");
        if (StringUtils.isNotEmpty(oauthError)) {
            wwwAuthenticate.append(", error=\"").append(oauthError).append("\"");
        }
        if (StringUtils.isNotEmpty(oauthErrorDescription)) {
            wwwAuthenticate.append(", error_description=\"").append(oauthErrorDescription).append("\"");
        }
        headers.put(HttpHeaders.WWW_AUTHENTICATE, wwwAuthenticate.toString());
        exposeWwwAuthenticateHeader(headers);
        if (log.isDebugEnabled()) {
            log.debug("Set MCP WWW-Authenticate header: " + wwwAuthenticate);
        }
    }

    /**
     * Resolves the HTTP status code from the message context.
     */
    public static int resolveResponseStatusCode(MessageContext messageContext) {
        org.apache.axis2.context.MessageContext axis2MC =
                ((Axis2MessageContext) messageContext).getAxis2MessageContext();
        Object statusCode = axis2MC.getProperty(APIMgtGatewayConstants.HTTP_SC);
        if (statusCode == null) {
            statusCode = axis2MC.getProperty(NhttpConstants.HTTP_SC);
        }
        if (statusCode == null) {
            statusCode = messageContext.getProperty(APIMgtGatewayConstants.HTTP_RESPONSE_STATUS_CODE);
        }
        if (statusCode instanceof Integer) {
            return (Integer) statusCode;
        }
        if (statusCode != null && StringUtils.isNumeric(statusCode.toString())) {
            return Integer.parseInt(statusCode.toString());
        }
        return 0;
    }

    private static void exposeWwwAuthenticateHeader(Map headers) {
        Object exposeHeadersList = headers.get(APIConstants.CORSHeaders.ACCESS_CONTROL_EXPOSE_HEADERS);
        String exposeHeaders = HttpHeaders.WWW_AUTHENTICATE;
        if (exposeHeadersList instanceof String) {
            exposeHeaders = (String) exposeHeadersList;
            if (StringUtils.isNotEmpty(exposeHeaders)
                    && !exposeHeaders.toLowerCase().contains(HttpHeaders.WWW_AUTHENTICATE.toLowerCase())) {
                exposeHeaders += "," + HttpHeaders.WWW_AUTHENTICATE;
            }
        }
        headers.put(APIConstants.CORSHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, exposeHeaders);
    }

    /**
     * Get the Gateway server URL from the supported vhosts of the API, prioritizing the value passed in
     * host header of the request
     *
     * @param hostHeader  Host header of the request
     * @param contextPath Context path of the matched API
     * @return Gateway server URL
     */
    public static String getGatewayServerURL(String hostHeader, String contextPath) {
        String serverURL = null;
        API api = findApiByContextInDataHolder(contextPath);
        if (api != null) {
            List<VHost> gwVhosts = api.getVhosts();
            if (gwVhosts == null || gwVhosts.isEmpty()) {
                return null;
            }

            if (!StringUtils.isEmpty(hostHeader)) {
                String host = hostHeader.trim();
                // Remove port if present in the host header
                int colonIndex = host.indexOf(':');
                host = (colonIndex > -1) ? host.substring(0, colonIndex) : host;
                for (VHost vHost : gwVhosts) {
                    if (vHost.getHost() != null && vHost.getHost().equalsIgnoreCase(host)) {
                        serverURL = vHost.getHttpsUrl();
                        break;
                    }
                }
            }

            // If server URL is not resolved using host header, pick the first vhost as the resource endpoint
            if (StringUtils.isEmpty(serverURL)) {
                serverURL = gwVhosts.get(0).getHttpsUrl();
            }
        }
        return serverURL;
    }

    private static API findApiByContextInDataHolder(String contextPath) {
        if (StringUtils.isEmpty(contextPath)) {
            return null;
        }
        Map<String, Map<String, API>> tenantAPIMap = DataHolder.getInstance().getTenantAPIMap();
        if (tenantAPIMap == null || tenantAPIMap.isEmpty()) {
            return null;
        }
        for (String contextCandidate : getContextPathCandidates(contextPath)) {
            for (Map<String, API> contextApiMap : tenantAPIMap.values()) {
                if (contextApiMap == null) {
                    continue;
                }
                API api = contextApiMap.get(contextCandidate);
                if (api != null) {
                    return api;
                }
            }
        }
        return null;
    }

    private static List<String> getContextPathCandidates(String contextPath) {
        String normalized = contextPath.trim();
        if (normalized.isEmpty()) {
            return Collections.emptyList();
        }
        if (normalized.startsWith("/")) {
            return Arrays.asList(normalized, normalized.substring(1));
        }
        return Arrays.asList("/" + normalized, normalized);
    }

    /**
     * Resolves scopes_supported for oauth-protected-resource metadata.
     * Fetches scopes from the Key Manager OpenID discovery document (well_known_endpoint or issuer-based URL).
     * Falls back to API resource scopes when discovery is unavailable.
     *
     * @param matchedAPI     MCP API
     * @param keyManagers    Key managers bound to the API
     * @param fallbackScopes Scopes from API URL mappings when discovery cannot be resolved
     * @return scopes_supported list for the metadata response
     */
    public static List<String> resolveScopesSupported(API matchedAPI, List<String> keyManagers,
                                                      List<String> fallbackScopes) {
        // TODO:  scope太多先注解一下，这块先写死了，后续再完善
        //        KeyManagerDto keyManagerDto = resolveKeyManagerForMcp(matchedAPI, keyManagers);
//        List<String> discoveryScopes = fetchScopesSupportedFromDiscovery(keyManagerDto);
//        if (!discoveryScopes.isEmpty()) {
//            return discoveryScopes;
//        }

        return Arrays.asList("openid", "wso2-role", "pkulaw-extensions");
        //return fallbackScopes != null ? fallbackScopes : Collections.emptyList();
    }

    private static KeyManagerDto resolveKeyManagerForMcp(API matchedAPI, List<String> keyManagers) {
        if (matchedAPI == null) {
            return null;
        }
        String organization = matchedAPI.getOrganization();
        if (keyManagers == null || keyManagers.isEmpty() || keyManagers.size() > 1) {
            Map<String, KeyManagerDto> keyManagerMap = KeyManagerHolder.getTenantKeyManagers(organization);
            if (keyManagerMap != null && keyManagerMap.size() == 1) {
                return keyManagerMap.values().iterator().next();
            }
            return null;
        }
        String keyManagerName = keyManagers.get(0);
        if (APIConstants.KeyManager.API_LEVEL_ALL_KEY_MANAGERS.equals(keyManagerName)) {
            Map<String, KeyManagerDto> keyManagerMap = KeyManagerHolder.getTenantKeyManagers(organization);
            if (keyManagerMap != null && keyManagerMap.size() == 1) {
                return keyManagerMap.values().iterator().next();
            }
            return null;
        }
        return resolveKeyManagerDtoForMetadata(organization, keyManagerName);
    }

    private static String resolveOpenIdDiscoveryUrl(KeyManagerDto keyManagerDto) {
        if (keyManagerDto == null) {
            return null;
        }
        try {
            KeyManager keyManager = keyManagerDto.getKeyManager();
            if (keyManager != null) {
                KeyManagerConfiguration configuration = keyManager.getKeyManagerConfiguration();
                if (configuration != null) {
                    Object wellKnownEndpoint = configuration.getParameter(APIConstants.KeyManager.WELL_KNOWN_ENDPOINT);
                    if (wellKnownEndpoint != null && StringUtils.isNotBlank(wellKnownEndpoint.toString())) {
                        return wellKnownEndpoint.toString().trim();
                    }
                }
            }
        } catch (APIManagementException e) {
            if (log.isDebugEnabled()) {
                log.debug("Failed to read key manager configuration for OpenID discovery URL", e);
            }
        }
        String issuer = keyManagerDto.getIssuer();
        if (StringUtils.isBlank(issuer)) {
            return null;
        }
        String trimmedIssuer = issuer.trim();
        if (trimmedIssuer.contains("/.well-known/")) {
            return trimmedIssuer;
        }
        if (trimmedIssuer.endsWith("/")) {
            return trimmedIssuer + ".well-known/openid-configuration";
        }
        return trimmedIssuer + "/.well-known/openid-configuration";
    }

    private static List<String> fetchScopesSupportedFromDiscovery(KeyManagerDto keyManagerDto) {
        String discoveryUrl = resolveOpenIdDiscoveryUrl(keyManagerDto);
        if (StringUtils.isBlank(discoveryUrl)) {
            return Collections.emptyList();
        }
        try {
            OpenIdConnectConfiguration configuration = APIUtil.getOpenIdConnectConfigurations(discoveryUrl);
            if (configuration != null && configuration.getScopesSupported() != null
                    && !configuration.getScopesSupported().isEmpty()) {
                return new ArrayList<>(configuration.getScopesSupported());
            }
        } catch (APIManagementException e) {
            log.warn("Failed to fetch scopes_supported from OpenID discovery URL: " + discoveryUrl, e);
        }
        return Collections.emptyList();
    }

    /**
     * Collects scopes defined on the MCP API URL mappings.
     */
    public static List<String> getAllScopesFromApi(API api) {
        // 这块resolveScopesSupported之前已经写死了，所以不需要再查询了
        //        List<String> allScopes = new ArrayList<>();
        //        if (api != null && api.getResources() != null) {
        //            for (URLMapping urlMapping : api.getResources()) {
        //                if (urlMapping.getScopes() != null) {
        //                    allScopes.addAll(urlMapping.getScopes());
        //                }
        //            }
        //        }
        //        return allScopes;
        return Collections.emptyList();
    }

    /**
     * Looks up an MCP API by its gateway context path.
     * Accepts both API version paths (e.g. {@code /mcp-law-agg/1.0.0}) and full MCP contexts
     * (e.g. {@code /mcp-law-agg/1.0.0/mcp}).
     */
    public static API findMcpApiByContext(String tenantDomain, String apiContext) {
        if (StringUtils.isEmpty(apiContext)) {
            return null;
        }
        String tenant = StringUtils.isEmpty(tenantDomain) ? APIConstants.SUPER_TENANT_DOMAIN : tenantDomain;
        String cacheKey = tenant + '|' + apiContext;
        API cached = MCP_API_CONTEXT_LOOKUP_CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        API api = findMcpApiByContextAndVersion(tenantDomain, apiContext);
        if (api != null) {
            cacheMcpApiContextLookup(cacheKey, api);
            return api;
        }
        for (String contextCandidate : getMcpApiContextLookupCandidates(apiContext)) {
            api = findMcpApiByContextCandidate(tenantDomain, contextCandidate);
            if (api != null) {
                cacheMcpApiContextLookup(cacheKey, api);
                return api;
            }
            api = findMcpApiByPathMatch(tenantDomain, contextCandidate);
            if (api != null) {
                cacheMcpApiContextLookup(cacheKey, api);
                return api;
            }
        }
        api = findMcpApiByScanningAllStores(tenantDomain, apiContext);
        if (api != null) {
            cacheMcpApiContextLookup(cacheKey, api);
        }
        return api;
    }

    private static void cacheMcpApiContextLookup(String cacheKey, API api) {
        if (api == null || MCP_API_CONTEXT_LOOKUP_CACHE.size() >= MCP_API_CONTEXT_LOOKUP_CACHE_MAX) {
            return;
        }
        MCP_API_CONTEXT_LOOKUP_CACHE.putIfAbsent(cacheKey, api);
    }

    /**
     * Resolves MCP API using subscription store keys ({@code context + "." + version}).
     */
    private static API findMcpApiByContextAndVersion(String tenantDomain, String pathSuffix) {
        for (String[] contextVersionPair : resolveMcpContextVersionPairs(pathSuffix)) {
            String context = contextVersionPair[0];
            String version = contextVersionPair[1];
            for (String tenant : getTenantLookupCandidates(tenantDomain)) {
                SubscriptionDataStore subscriptionDataStore =
                        SubscriptionDataHolder.getInstance().getTenantSubscriptionStore(tenant);
                if (subscriptionDataStore != null) {
                    API api = subscriptionDataStore.getApiByContextAndVersion(context, version);
                    if (isMcpApi(api)) {
                        return api;
                    }
                }
            }
            try {
                API api = new SubscriptionDataLoaderImpl().getApi(context, version);
                if (isMcpApi(api)) {
                    return api;
                }
            } catch (org.wso2.carbon.apimgt.keymgt.model.exception.DataLoadingException e) {
                if (log.isDebugEnabled()) {
                    log.debug("Failed to load API metadata for context=" + context + ", version=" + version, e);
                }
            }
        }
        return null;
    }

    private static List<String[]> resolveMcpContextVersionPairs(String pathSuffix) {
        List<String[]> pairs = new ArrayList<>();
        Set<String> seenPairs = new HashSet<>();
        for (String context : getMcpApiContextLookupCandidates(pathSuffix)) {
            String version = extractApiVersionFromContextPath(context);
            if (StringUtils.isEmpty(version)) {
                continue;
            }
            addContextVersionPair(pairs, seenPairs, context, version);
            if (!context.endsWith(APIMgtGatewayConstants.MCP_RESOURCE)) {
                String fullMcpContext = context.endsWith("/")
                        ? context + APIMgtGatewayConstants.MCP_RESOURCE.substring(1)
                        : context + APIMgtGatewayConstants.MCP_RESOURCE;
                addContextVersionPair(pairs, seenPairs, fullMcpContext, version);
            }
        }
        return pairs;
    }

    private static void addContextVersionPair(List<String[]> pairs, Set<String> seenPairs, String context,
                                              String version) {
        String pairKey = context + "|" + version;
        if (seenPairs.add(pairKey)) {
            pairs.add(new String[] {context, version});
        }
    }

    private static String extractApiVersionFromContextPath(String contextPath) {
        String normalizedPath = contextPath;
        if (normalizedPath.endsWith(APIMgtGatewayConstants.MCP_RESOURCE)) {
            normalizedPath = normalizedPath.substring(0,
                    normalizedPath.length() - APIMgtGatewayConstants.MCP_RESOURCE.length());
        }
        if (normalizedPath.endsWith("/")) {
            normalizedPath = normalizedPath.substring(0, normalizedPath.length() - 1);
        }
        int lastSlashIndex = normalizedPath.lastIndexOf('/');
        if (lastSlashIndex >= 0 && lastSlashIndex < normalizedPath.length() - 1) {
            return normalizedPath.substring(lastSlashIndex + 1);
        }
        return null;
    }

    public static boolean isMcpApi(API api) {
        if (api == null) {
            return false;
        }
        if (APIConstants.API_TYPE_MCP.equals(api.getApiType())) {
            return true;
        }
        String context = api.getContext();
        return context != null && context.endsWith(APIMgtGatewayConstants.MCP_RESOURCE);
    }

    /**
     * Resolves an MCP API using the same path-matching logic as {@link Utils#getSelectedAPIList(String, String)}.
     */
    private static API findMcpApiByPathMatch(String tenantDomain, String path) {
        if (StringUtils.isEmpty(path)) {
            return null;
        }
        TreeMap<String, API> selectedApis = Utils.getSelectedAPIList(path, tenantDomain);
        API api = getFirstMcpApi(selectedApis);
        if (api != null) {
            return api;
        }
        Map<String, Map<String, API>> tenantAPIMap = DataHolder.getInstance().getTenantAPIMap();
        if (tenantAPIMap == null || tenantAPIMap.isEmpty()) {
            return null;
        }
        TreeMap<String, API> longestMatch = new TreeMap<>((c1, c2) -> Integer.compare(c2.length(), c1.length()));
        for (Map<String, API> contextApiMap : tenantAPIMap.values()) {
            if (contextApiMap == null) {
                continue;
            }
            contextApiMap.forEach((context, candidateApi) -> {
                if (ApiUtils.matchApiPath(path, context) && isMcpApi(candidateApi)) {
                    longestMatch.put(context, candidateApi);
                }
            });
        }
        return getFirstMcpApi(longestMatch);
    }

    private static API getFirstMcpApi(Map<String, API> apisByContext) {
        if (apisByContext == null || apisByContext.isEmpty()) {
            return null;
        }
        for (API api : apisByContext.values()) {
            if (isMcpApi(api)) {
                return api;
            }
        }
        return null;
    }

    /**
     * Scans all gateway API registries and matches MCP APIs by context path suffix.
     */
    private static API findMcpApiByScanningAllStores(String tenantDomain, String pathSuffix) {
        Set<String> lookupKeys = new HashSet<>(getMcpApiContextLookupCandidates(pathSuffix));
        Set<String> seenApiIds = new HashSet<>();
        API longestMatch = null;
        int longestContextLength = -1;

        for (API api : collectAllRegisteredApis(tenantDomain)) {
            if (!isMcpApi(api)) {
                continue;
            }
            String apiId = api.getUuid();
            if (apiId != null && !seenApiIds.add(apiId)) {
                continue;
            }
            String apiContext = api.getContext();
            if (StringUtils.isEmpty(apiContext)) {
                continue;
            }
            if (lookupKeys.contains(apiContext) || matchesMcpContextSuffix(apiContext, pathSuffix)) {
                if (apiContext.length() > longestContextLength) {
                    longestMatch = api;
                    longestContextLength = apiContext.length();
                }
            }
        }
        return longestMatch;
    }

    private static boolean matchesMcpContextSuffix(String apiContext, String pathSuffix) {
        for (String candidate : getMcpApiContextLookupCandidates(pathSuffix)) {
            if (apiContext.equals(candidate)) {
                return true;
            }
            if (apiContext.endsWith(APIMgtGatewayConstants.MCP_RESOURCE)) {
                String apiContextWithoutMcp = apiContext.substring(0,
                        apiContext.length() - APIMgtGatewayConstants.MCP_RESOURCE.length());
                if (apiContextWithoutMcp.equals(candidate)
                        || apiContextWithoutMcp.equals(StringUtils.removeEnd(candidate, "/"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<API> collectAllRegisteredApis(String tenantDomain) {
        List<API> apis = new ArrayList<>();
        Set<String> seenApiIds = new HashSet<>();
        Map<String, Map<String, API>> tenantAPIMap = DataHolder.getInstance().getTenantAPIMap();
        if (tenantAPIMap != null) {
            for (Map<String, API> contextApiMap : tenantAPIMap.values()) {
                addApisFromMap(contextApiMap, apis, seenApiIds);
            }
        }
        for (String tenant : getTenantLookupCandidates(tenantDomain)) {
            SubscriptionDataStore subscriptionDataStore =
                    SubscriptionDataHolder.getInstance().getTenantSubscriptionStore(tenant);
            if (subscriptionDataStore == null) {
                continue;
            }
            addApisFromMap(subscriptionDataStore.getAllAPIsByContextList(), apis, seenApiIds);
        }
        return apis;
    }

    private static void addApisFromMap(Map<String, API> contextApiMap, List<API> apis, Set<String> seenApiIds) {
        if (contextApiMap == null) {
            return;
        }
        for (API api : contextApiMap.values()) {
            if (api == null) {
                continue;
            }
            String apiId = api.getUuid();
            if (apiId == null || seenApiIds.add(apiId)) {
                apis.add(api);
            }
        }
    }

    private static String[] getTenantLookupCandidates(String tenantDomain) {
        if (StringUtils.isEmpty(tenantDomain)) {
            return new String[] {APIConstants.SUPER_TENANT_DOMAIN};
        }
        if (APIConstants.SUPER_TENANT_DOMAIN.equals(tenantDomain)) {
            return new String[] {tenantDomain};
        }
        return new String[] {tenantDomain, APIConstants.SUPER_TENANT_DOMAIN};
    }

    /**
     * Builds lookup candidates for a well-known path suffix, including the {@code /mcp} resource suffix.
     */
    private static List<String> getMcpApiContextLookupCandidates(String apiContext) {
        List<String> candidates = new ArrayList<>(getContextPathCandidates(apiContext));
        for (String context : new ArrayList<>(candidates)) {
            if (context.endsWith(APIMgtGatewayConstants.MCP_RESOURCE)) {
                continue;
            }
            String withMcpResource = context.endsWith("/")
                    ? context + APIMgtGatewayConstants.MCP_RESOURCE.substring(1)
                    : context + APIMgtGatewayConstants.MCP_RESOURCE;
            if (!candidates.contains(withMcpResource)) {
                candidates.add(withMcpResource);
            }
        }
        return candidates;
    }

    private static API findMcpApiByContextCandidate(String tenantDomain, String apiContext) {
        API api = findMcpApiInSubscriptionStore(tenantDomain, apiContext);
        if (api != null) {
            return api;
        }
        api = findApiByContextInDataHolder(apiContext);
        if (isMcpApi(api)) {
            return api;
        }
        return null;
    }

    private static API findMcpApiInSubscriptionStore(String tenantDomain, String apiContext) {
        for (String tenant : getTenantLookupCandidates(tenantDomain)) {
            SubscriptionDataStore subscriptionDataStore =
                    SubscriptionDataHolder.getInstance().getTenantSubscriptionStore(tenant);
            if (subscriptionDataStore == null) {
                continue;
            }
            Map<String, API> contextApiMap = subscriptionDataStore.getAllAPIsByContextList();
            if (contextApiMap == null) {
                continue;
            }
            API api = contextApiMap.get(apiContext);
            if (isMcpApi(api)) {
                return api;
            }
            String version = extractApiVersionFromContextPath(apiContext);
            if (StringUtils.isNotEmpty(version)) {
                api = subscriptionDataStore.getApiByContextAndVersion(apiContext, version);
                if (isMcpApi(api)) {
                    return api;
                }
            }
        }
        return null;
    }

    /**
     * Sets the HTTP status on the message context without invoking Axis2 sendBack.
     * Use this for Synapse APIs that complete via the {@code respond} mediator.
     */
    public static void setHttpResponseStatus(MessageContext messageContext, int statusCode, boolean noEntityBody) {
        org.apache.axis2.context.MessageContext axis2MessageContext =
                ((Axis2MessageContext) messageContext).getAxis2MessageContext();
        axis2MessageContext.setProperty(APIMgtGatewayConstants.HTTP_SC, statusCode);
        axis2MessageContext.setProperty(NhttpConstants.HTTP_SC, statusCode);
        messageContext.setProperty(APIConstants.CUSTOM_HTTP_STATUS_CODE, statusCode);
        if (noEntityBody) {
            axis2MessageContext.setProperty(APIConstants.NO_ENTITY_BODY, true);
        } else {
            axis2MessageContext.removeProperty(APIConstants.NO_ENTITY_BODY);
        }
    }

    /**
     * Populates {@code authorization_servers} for MCP {@code /.well-known/oauth-protected-resource} only.
     * Uses Key Managers bound to this MCP API in Publisher (not gateway-wide defaults). Disabled bindings
     * (e.g. {@code kc} with {@code AM_KEY_MANAGER.ENABLED = 0}) are loaded via Event Hub for this response
     * only and are not registered in {@link KeyManagerHolder}.
     */
    private static void populateAuthorizationServers(OAuthProtectedResourceDTO metadata, API matchedAPI,
                                                     List<String> keyManagers) {
        String organization = matchedAPI.getOrganization();
        List<String> normalizedKeyManagers = normalizeKeyManagerBindings(keyManagers, organization);
        if (normalizedKeyManagers.isEmpty()) {
            log.error("No Key Managers bound to MCP API " + matchedAPI.getUuid()
                    + ". Bind Key Managers in Publisher and redeploy the MCP API.");
            return;
        }
        if (log.isDebugEnabled()) {
            log.debug("MCP well-known Key Manager bindings for API " + matchedAPI.getUuid() + ": raw=" + keyManagers
                    + ", resolved=" + normalizedKeyManagers);
        }

        List<String> metadataKeyManagers = selectKeyManagersForMetadata(normalizedKeyManagers, organization);
        Set<String> authorizationServers = new LinkedHashSet<>();

        if (metadataKeyManagers.size() == 1
                && isAllKeyManagersBinding(metadataKeyManagers.get(0))) {
            metadataKeyManagers = resolveKeyManagersForAllBinding(organization);
        }

        if (metadataKeyManagers.size() == 1 && isAllKeyManagersBinding(metadataKeyManagers.get(0))) {
            for (KeyManagerDto keyManagerDto : getAvailableKeyManagers(organization).values()) {
                if (!isResidentKeyManager(keyManagerDto.getName())) {
                    addAuthorizationServer(authorizationServers, keyManagerDto);
                }
            }
        } else {
            for (String keyManagerName : metadataKeyManagers) {
                if (isAllKeyManagersBinding(keyManagerName)) {
                    continue;
                }
                KeyManagerDto keyManager = resolveKeyManagerDtoForMetadata(organization, keyManagerName);
                if (keyManager != null) {
                    addAuthorizationServer(authorizationServers, keyManager);
                } else {
                    log.warn("Key Manager '" + keyManagerName + "' not found for MCP well-known metadata, API: "
                            + matchedAPI.getUuid() + ". organization=" + organization
                            + ", enabledOnGateway=" + getAvailableKeyManagers(organization).keySet());
                }
            }
        }
        removeResidentAuthorizationServersIfExternalPresent(authorizationServers);

        for (String authorizationServer : authorizationServers) {
            metadata.addAuthorizationServer(authorizationServer);
        }
        if (authorizationServers.isEmpty()) {
            log.error("No authorization_servers resolved for MCP well-known, API: " + matchedAPI.getUuid()
                    + ". boundKeyManagers=" + normalizedKeyManagers + ", metadataKeyManagers=" + metadataKeyManagers
                    + ". Ensure Event Hub is enabled and MCP API is bound to 'kc' (or set -D"
                    + APIMgtGatewayConstants.MCP_OAUTH_METADATA_KEY_MANAGER_PROPERTY + "=kc).");
        } else if (log.isDebugEnabled()) {
            log.debug("MCP well-known authorization_servers for API " + matchedAPI.getUuid() + ": "
                    + authorizationServers);
        }
    }

    private static List<String> normalizeKeyManagerNames(List<String> keyManagers) {
        if (keyManagers == null) {
            return Collections.emptyList();
        }
        List<String> normalized = new ArrayList<>();
        for (String keyManager : keyManagers) {
            if (StringUtils.isBlank(keyManager)) {
                continue;
            }
            if (keyManager.contains(",")) {
                for (String part : keyManager.split(",")) {
                    if (StringUtils.isNotBlank(part)) {
                        normalized.add(part.trim());
                    }
                }
            } else {
                normalized.add(keyManager.trim());
            }
        }
        return normalized;
    }

    /**
     * Resolves binding values that may be Key Manager UUIDs to their display names via Event Hub.
     */
    private static List<String> normalizeKeyManagerBindings(List<String> keyManagers, String organization) {
        List<String> normalized = normalizeKeyManagerNames(keyManagers);
        if (normalized.isEmpty()) {
            return normalized;
        }
        List<KeyManagerConfigurationDTO> catalog = getAllKeyManagerConfigurationsForMetadata(organization);
        List<String> resolved = new ArrayList<>();
        for (String binding : normalized) {
            KeyManagerConfigurationDTO configuration =
                    findKeyManagerConfigurationByNameOrId(catalog, binding, organization);
            resolved.add(configuration != null ? configuration.getName() : binding);
        }
        return resolved;
    }

    private static boolean isAllKeyManagersBinding(String keyManagerName) {
        return APIConstants.KeyManager.API_LEVEL_ALL_KEY_MANAGERS.equalsIgnoreCase(keyManagerName);
    }

    /**
     * When Publisher binds "All", well-known must not default to Resident {@code /oauth2/token}.
     * Prefer JVM override, then external Key Manager {@code kc} from Event Hub (incl. disabled).
     */
    private static List<String> resolveKeyManagersForAllBinding(String organization) {
        String preferredKeyManager = getMcpMetadataKeyManagerProperty();
        if (StringUtils.isNotBlank(preferredKeyManager)) {
            return Collections.singletonList(preferredKeyManager);
        }
        List<KeyManagerConfigurationDTO> configurations = getAllKeyManagerConfigurationsForMetadata(organization);
        KeyManagerConfigurationDTO kcConfiguration =
                findKeyManagerConfigurationByNameOrId(configurations, "kc", organization);
        if (kcConfiguration != null) {
            return Collections.singletonList(kcConfiguration.getName());
        }
        for (KeyManagerConfigurationDTO configuration : configurations) {
            if (configuration == null || isResidentKeyManager(configuration.getName())) {
                continue;
            }
            if (!APIConstants.KeyManager.DEFAULT_KEY_MANAGER_TYPE.equals(configuration.getType())
                    && isKeyManagerAllowedForOrganization(configuration, organization)) {
                return Collections.singletonList(configuration.getName());
            }
        }
        log.warn("MCP API uses 'All' Key Managers but no external Key Manager was found for well-known metadata.");
        return Collections.singletonList(APIConstants.KeyManager.API_LEVEL_ALL_KEY_MANAGERS);
    }

    private static boolean isResidentKeyManager(String keyManagerName) {
        return APIConstants.KeyManager.DEFAULT_KEY_MANAGER.equals(keyManagerName);
    }

    /**
     * Selects which API-bound Key Manager names are used for MCP well-known metadata only.
     */
    private static List<String> selectKeyManagersForMetadata(List<String> keyManagers, String organization) {
        if (keyManagers == null || keyManagers.isEmpty()) {
            return Collections.emptyList();
        }
        if (keyManagers.size() == 1) {
            return keyManagers;
        }
        String preferredKeyManager = getMcpMetadataKeyManagerProperty();
        if (StringUtils.isNotBlank(preferredKeyManager)) {
            for (String keyManagerName : keyManagers) {
                if (preferredKeyManager.equalsIgnoreCase(keyManagerName)) {
                    return Collections.singletonList(keyManagerName);
                }
            }
            KeyManagerConfigurationDTO preferredConfiguration =
                    getKeyManagerConfigurationForMetadata(organization, preferredKeyManager);
            if (preferredConfiguration != null) {
                return Collections.singletonList(preferredConfiguration.getName());
            }
        }
        List<String> selected = new ArrayList<>();
        for (String keyManagerName : keyManagers) {
            if (!isAllKeyManagersBinding(keyManagerName) && !isResidentKeyManager(keyManagerName)) {
                selected.add(keyManagerName);
            }
        }
        if (!selected.isEmpty()) {
            return selected;
        }
        return keyManagers;
    }

    /**
     * Optional override for MCP well-known metadata when an API binds multiple Key Managers.
     * Returns null if unset (use API bindings only).
     */
    private static String getMcpMetadataKeyManagerProperty() {
        String preferredKeyManager = System.getProperty(
                APIMgtGatewayConstants.MCP_OAUTH_METADATA_KEY_MANAGER_PROPERTY);
        return StringUtils.isNotBlank(preferredKeyManager) ? preferredKeyManager.trim() : null;
    }

    private static Map<String, KeyManagerDto> getAvailableKeyManagers(String organization) {
        Map<String, KeyManagerDto> keyManagersByName = new LinkedHashMap<>();
        Set<String> visitedTenants = new HashSet<>();
        String[] tenantCandidates = {
                APIConstants.GLOBAL_KEY_MANAGER_TENANT_DOMAIN,
                organization,
                GatewayUtils.getTenantDomain(),
                APIConstants.SUPER_TENANT_DOMAIN
        };
        for (String tenant : tenantCandidates) {
            if (StringUtils.isBlank(tenant) || !visitedTenants.add(tenant)) {
                continue;
            }
            Map<String, KeyManagerDto> tenantKeyManagers = KeyManagerHolder.getTenantKeyManagers(tenant);
            if (tenantKeyManagers != null) {
                tenantKeyManagers.forEach(keyManagersByName::putIfAbsent);
            }
        }
        return keyManagersByName;
    }

    /**
     * Resolves a Key Manager for MCP well-known metadata. External/disabled KMs (e.g. {@code kc}) are loaded from
     * Event Hub first so token_endpoint/issuer are not replaced by Resident {@code /oauth2/token}.
     */
    private static KeyManagerDto resolveKeyManagerDtoForMetadata(String organization, String keyManagerName) {
        if (!isResidentKeyManager(keyManagerName)) {
            KeyManagerConfigurationDTO configuration =
                    getKeyManagerConfigurationForMetadata(organization, keyManagerName);
            KeyManagerDto fromEventHub = keyManagerDtoFromConfiguration(configuration);
            if (fromEventHub != null) {
                return fromEventHub;
            }
        }
        KeyManagerDto enabledKeyManager = resolveEnabledKeyManagerDto(organization, keyManagerName);
        if (enabledKeyManager != null && !isWso2ResidentTokenUrl(enabledKeyManager.getIssuer())) {
            return enabledKeyManager;
        }
        if (!isResidentKeyManager(keyManagerName)) {
            KeyManagerConfigurationDTO configuration =
                    getKeyManagerConfigurationForMetadata(organization, keyManagerName);
            return keyManagerDtoFromConfiguration(configuration);
        }
        return enabledKeyManager;
    }

    private static KeyManagerDto resolveEnabledKeyManagerDto(String organization, String keyManagerName) {
        Map<String, KeyManagerDto> availableKeyManagers = getAvailableKeyManagers(organization);
        KeyManagerDto keyManagerDto = availableKeyManagers.get(keyManagerName);
        if (keyManagerDto != null) {
            return keyManagerDto;
        }
        for (Map.Entry<String, KeyManagerDto> entry : availableKeyManagers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(keyManagerName)) {
                return entry.getValue();
            }
        }
        return KeyManagerHolder.getKeyManagerByName(organization, keyManagerName);
    }

    private static KeyManagerDto keyManagerDtoFromConfiguration(KeyManagerConfigurationDTO configuration) {
        if (configuration == null) {
            return null;
        }
        String authorizationServer = extractAuthorizationServerFromConfiguration(configuration);
        if (StringUtils.isBlank(authorizationServer)) {
            return null;
        }
        KeyManagerDto keyManagerDto = new KeyManagerDto();
        keyManagerDto.setName(configuration.getName());
        keyManagerDto.setIssuer(authorizationServer.trim());
        return keyManagerDto;
    }

    /**
     * Issuer URL for {@code authorization_servers} in MCP oauth-protected-resource metadata (RFC 9728).
     * Must be the authorization server identifier (realm issuer), not the token endpoint.
     */
    private static String extractAuthorizationServerFromConfiguration(KeyManagerConfigurationDTO configuration) {
        String issuer = normalizeAuthorizationServerIssuer(
                getConfigurationProperty(configuration, APIConstants.KeyManager.ISSUER));
        if (StringUtils.isNotBlank(issuer) && !isWso2ResidentTokenUrl(issuer)) {
            return issuer;
        }
        String tokenEndpoint = getConfigurationProperty(configuration, APIConstants.KeyManager.TOKEN_ENDPOINT);
        if (StringUtils.isBlank(tokenEndpoint) && configuration.getEndpoints() != null) {
            tokenEndpoint = configuration.getEndpoints().get(APIConstants.KeyManager.TOKEN_ENDPOINT);
        }
        issuer = normalizeAuthorizationServerIssuer(tokenEndpoint);
        if (StringUtils.isNotBlank(issuer) && !isWso2ResidentTokenUrl(issuer)) {
            return issuer;
        }
        return null;
    }

    /**
     * Normalizes issuer for metadata: strips OpenID token paths if a token URL was configured by mistake.
     */
    private static String normalizeAuthorizationServerIssuer(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        String normalized = value.trim();
        int openIdConnectIndex = normalized.indexOf("/protocol/openid-connect");
        if (openIdConnectIndex > 0) {
            normalized = normalized.substring(0, openIdConnectIndex);
        }
        if (normalized.endsWith("/oauth2/token")) {
            normalized = normalized.substring(0, normalized.length() - "/oauth2/token".length());
        }
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String getConfigurationProperty(KeyManagerConfigurationDTO configuration, String key) {
        if (configuration == null || StringUtils.isBlank(key)) {
            return null;
        }
        Object value = configuration.getProperty(key);
        if (value == null && configuration.getAdditionalProperties() != null) {
            value = configuration.getAdditionalProperties().get(key);
        }
        return value != null ? value.toString() : null;
    }

    private static boolean isWso2ResidentTokenUrl(String url) {
        return StringUtils.isNotBlank(url) && url.contains("/oauth2/token");
    }

    private static void removeResidentAuthorizationServersIfExternalPresent(Set<String> authorizationServers) {
        boolean hasExternal = false;
        for (String authorizationServer : authorizationServers) {
            if (!isWso2ResidentTokenUrl(authorizationServer)) {
                hasExternal = true;
                break;
            }
        }
        if (hasExternal) {
            authorizationServers.removeIf(MCPUtils::isWso2ResidentTokenUrl);
        }
    }

    private static KeyManagerConfigurationDTO getKeyManagerConfigurationForMetadata(String organization,
                                                                                    String keyManagerNameOrId) {
        if (StringUtils.isBlank(keyManagerNameOrId)) {
            return null;
        }
        String cacheKey = buildMetadataKeyManagerCacheKey(organization, keyManagerNameOrId);
        KeyManagerConfigurationDTO cached = MCP_METADATA_KEY_MANAGER_CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        KeyManagerConfigurationDTO configuration = findKeyManagerConfigurationByNameOrId(
                getAllKeyManagerConfigurationsForMetadata(organization), keyManagerNameOrId, organization);
        if (configuration != null) {
            MCP_METADATA_KEY_MANAGER_CACHE.put(cacheKey, configuration);
        }
        return configuration;
    }

    /**
     * Filters Key Managers by {@code AM_KEY_MANAGER_ALLOWED_ORGS.ALLOWED_ORGANIZATIONS} for the MCP API organization.
     * {@code ALL} allows any organization; otherwise the MCP API organization must match (case-insensitive).
     */
    private static boolean isKeyManagerAllowedForOrganization(KeyManagerConfigurationDTO configuration,
                                                              String mcpOrganization) {
        if (configuration == null) {
            return false;
        }
        List<String> allowedOrganizations = configuration.getAllowedOrganizations();
        if (allowedOrganizations == null || allowedOrganizations.isEmpty()) {
            return true;
        }
        if (StringUtils.isBlank(mcpOrganization)) {
            return false;
        }
        for (String allowedOrganization : allowedOrganizations) {
            if (APIConstants.ORG_ALL_QUERY_PARAM.equalsIgnoreCase(allowedOrganization)
                    || mcpOrganization.equalsIgnoreCase(allowedOrganization)) {
                return true;
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("Key Manager '" + configuration.getName() + "' is not allowed for MCP organization '"
                    + mcpOrganization + "'. allowedOrganizations=" + allowedOrganizations);
        }
        return false;
    }

    private static List<KeyManagerConfigurationDTO> getAllKeyManagerConfigurationsForMetadata(String organization) {
        Map<String, KeyManagerConfigurationDTO> configurationsByUuid = new LinkedHashMap<>();
        Set<String> visitedTenants = new HashSet<>();
        // Do not call Event Hub with WSO2/System: it is not a Carbon tenant and
        // /keymanagers for a real tenant already includes Global Key Managers.
        String[] tenantCandidates = {
                APIConstants.SUPER_TENANT_DOMAIN,
                organization,
                GatewayUtils.getTenantDomain()
        };
        for (String tenant : tenantCandidates) {
            if (StringUtils.isBlank(tenant) || !visitedTenants.add(tenant)) {
                continue;
            }
            for (KeyManagerConfigurationDTO configuration : fetchKeyManagerConfigurationsFromEventHub(tenant)) {
                if (configuration != null && StringUtils.isNotBlank(configuration.getUuid())
                        && isKeyManagerAllowedForOrganization(configuration, organization)) {
                    configurationsByUuid.putIfAbsent(configuration.getUuid(), configuration);
                }
            }
        }
        return new ArrayList<>(configurationsByUuid.values());
    }

    private static String buildMetadataKeyManagerCacheKey(String organization, String keyManagerName) {
        String org = StringUtils.isNotBlank(organization) ? organization : APIConstants.SUPER_TENANT_DOMAIN;
        return org.toLowerCase() + '|' + keyManagerName.trim().toLowerCase();
    }

    private static KeyManagerConfigurationDTO findKeyManagerConfigurationByNameOrId(
            List<KeyManagerConfigurationDTO> configurations, String keyManagerNameOrId, String mcpOrganization) {
        if (configurations == null || configurations.isEmpty() || StringUtils.isBlank(keyManagerNameOrId)) {
            return null;
        }
        for (KeyManagerConfigurationDTO configuration : configurations) {
            if (configuration == null) {
                continue;
            }
            if ((keyManagerNameOrId.equalsIgnoreCase(configuration.getName())
                    || keyManagerNameOrId.equals(configuration.getUuid()))
                    && isKeyManagerAllowedForOrganization(configuration, mcpOrganization)) {
                return configuration;
            }
        }
        return null;
    }

    private static List<KeyManagerConfigurationDTO> fetchKeyManagerConfigurationsFromEventHub(String tenantDomain) {
        if (StringUtils.isBlank(tenantDomain)) {
            return Collections.emptyList();
        }
        EventHubKeyManagerTenantCacheEntry cachedEntry = EVENT_HUB_KM_TENANT_CACHE.get(tenantDomain);
        if (cachedEntry != null && !cachedEntry.isExpired()) {
            if (log.isDebugEnabled()) {
                log.debug("Using cached Event Hub Key Manager configurations for tenant: " + tenantDomain);
            }
            return cachedEntry.configurations;
        }
        if (cachedEntry != null) {
            EVENT_HUB_KM_TENANT_CACHE.remove(tenantDomain, cachedEntry);
        }

        List<KeyManagerConfigurationDTO> loadedConfigurations =
                loadKeyManagerConfigurationsFromEventHub(tenantDomain);
        if (loadedConfigurations == null) {
            return Collections.emptyList();
        }
        EVENT_HUB_KM_TENANT_CACHE.put(tenantDomain, new EventHubKeyManagerTenantCacheEntry(
                Collections.unmodifiableList(new ArrayList<>(loadedConfigurations)), System.currentTimeMillis()));
        populateMetadataKeyManagerCacheFromTenantLoad(loadedConfigurations);
        return loadedConfigurations;
    }

    private static List<KeyManagerConfigurationDTO> loadKeyManagerConfigurationsFromEventHub(String tenantDomain) {
        EventHubConfigurationDto eventHubConfiguration =
                ServiceReferenceHolder.getInstance().getAPIManagerConfiguration().getEventHubConfigurationDto();
        if (eventHubConfiguration == null || !eventHubConfiguration.isEnabled()) {
            if (log.isDebugEnabled()) {
                log.debug("Event Hub is not enabled; cannot load disabled Key Manager configurations for MCP metadata");
            }
            return null;
        }
        HttpResponse httpResponse = null;
        try {
            String url = eventHubConfiguration.getServiceUrl().concat(APIConstants.INTERNAL_WEB_APP_EP)
                    .concat("/keymanagers");
            HttpGet method = new HttpGet(url);
            byte[] credentials = Base64.encodeBase64((eventHubConfiguration.getUsername() + ":"
                    + eventHubConfiguration.getPassword()).getBytes(StandardCharsets.UTF_8));
            method.setHeader("Authorization", "Basic " + new String(credentials, StandardCharsets.UTF_8));
            method.setHeader(APIConstants.HEADER_TENANT, tenantDomain);
            URL configUrl = new URL(url);
            HttpClient httpClient = APIUtil.getHttpClient(configUrl.getPort(), configUrl.getProtocol());
            httpResponse = httpClient.execute(method);
            int statusCode = httpResponse.getStatusLine().getStatusCode();
            if (statusCode == HttpStatus.SC_OK) {
                String responseString = EntityUtils.toString(httpResponse.getEntity(), StandardCharsets.UTF_8);
                KeyManagerConfigurationDTO[] keyManagerConfigurations =
                        MCP_METADATA_GSON.fromJson(responseString, KeyManagerConfigurationDTO[].class);
                if (keyManagerConfigurations == null) {
                    return Collections.emptyList();
                }
                return Arrays.asList(keyManagerConfigurations);
            }
            if (log.isDebugEnabled()) {
                log.debug("Failed to retrieve Key Manager configurations from Event Hub for tenant " + tenantDomain
                        + ", status=" + statusCode);
            }
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("Error retrieving Key Manager configurations from Event Hub for MCP metadata, tenant="
                        + tenantDomain, e);
            }
        } finally {
            consumeHttpResponseEntity(httpResponse);
        }
        return null;
    }

    /**
     * Seeds per-Key-Manager metadata cache entries after a tenant-level Event Hub load.
     */
    private static void populateMetadataKeyManagerCacheFromTenantLoad(
            List<KeyManagerConfigurationDTO> configurations) {
        if (configurations == null || configurations.isEmpty()) {
            return;
        }
        for (KeyManagerConfigurationDTO configuration : configurations) {
            if (configuration == null) {
                continue;
            }
            if (StringUtils.isNotBlank(configuration.getName())) {
                MCP_METADATA_KEY_MANAGER_CACHE.putIfAbsent(
                        buildMetadataKeyManagerCacheKey(APIConstants.SUPER_TENANT_DOMAIN, configuration.getName()),
                        configuration);
            }
            if (StringUtils.isNotBlank(configuration.getUuid())) {
                MCP_METADATA_KEY_MANAGER_CACHE.putIfAbsent(
                        buildMetadataKeyManagerCacheKey(APIConstants.SUPER_TENANT_DOMAIN, configuration.getUuid()),
                        configuration);
            }
        }
    }

    /**
     * Ensures the HTTP response entity is consumed so connections are returned to the pool.
     * No-op when the entity was already read (e.g. by {@link EntityUtils#toString}).
     */
    private static void consumeHttpResponseEntity(HttpResponse httpResponse) {
        if (httpResponse == null || httpResponse.getEntity() == null) {
            return;
        }
        try {
            EntityUtils.consume(httpResponse.getEntity());
        } catch (IOException e) {
            if (log.isDebugEnabled()) {
                log.debug("Failed to consume Event Hub HTTP response entity", e);
            }
        }
    }

    private static void addAuthorizationServer(Set<String> authorizationServers, KeyManagerDto keyManagerDto) {
        if (keyManagerDto == null) {
            return;
        }
        String authorizationServer = normalizeAuthorizationServerIssuer(keyManagerDto.getIssuer());
        if (StringUtils.isNotBlank(authorizationServer) && !isWso2ResidentTokenUrl(authorizationServer)) {
            authorizationServers.add(authorizationServer);
        }
    }

    /**
     * Builds oauth-protected-resource metadata for an MCP API.
     */
    public static OAuthProtectedResourceDTO buildOAuthProtectedResourceMetadata(MessageContext messageContext,
                                                                                API matchedAPI,
                                                                                List<String> fallbackScopes) {
        OAuthProtectedResourceDTO metadata = new OAuthProtectedResourceDTO();
        List<String> keyManagers = DataHolder.getInstance().getKeyManagersFromUUID(matchedAPI.getUuid());
        log.info("buildOAuthProtectedResourceMetadata keyManagers:" + keyManagers);
        org.apache.axis2.context.MessageContext axis2MC =
                ((Axis2MessageContext) messageContext).getAxis2MessageContext();
        Map headers = (Map) axis2MC.getProperty(org.apache.axis2.context.MessageContext.TRANSPORT_HEADERS);
        String hostHeader = headers != null ? (String) headers.get(APIMgtGatewayConstants.HOST) : null;
        if (StringUtils.isBlank(hostHeader) || !VALID_HOST_HEADER_PATTERN.matcher(hostHeader).matches()) {
            hostHeader = APIUtil.getHostAddress();
        }

        String contextPath = matchedAPI.getContext();
        if (StringUtils.isEmpty(contextPath)) {
            contextPath = (String) messageContext.getProperty(RESTConstants.REST_API_CONTEXT);
        }
        String serverURL = getGatewayServerURL(hostHeader, contextPath);
        if (StringUtils.isEmpty(serverURL)) {
            log.error("Error while generating MCP oauth-protected-resource metadata for API: " + matchedAPI.getUuid());
            return metadata;
        }

        metadata.setResource(serverURL + contextPath + APIMgtGatewayConstants.MCP_RESOURCE);
        populateAuthorizationServers(metadata, matchedAPI, keyManagers);

        metadata.addScopesSupported(resolveScopesSupported(matchedAPI, keyManagers, fallbackScopes));
        return metadata;
    }

    /**
     * Minimal SSE preamble for Streamable HTTP {@code GET /mcp}. Clients use this channel for server-initiated
     * JSON-RPC messages after {@code initialize} on POST; the gateway answers POST synchronously today.
     */
    private static final String STREAMABLE_HTTP_SSE_PREAMBLE = ": stream open\n\n";

    /**
     * Answers Streamable HTTP {@code GET /mcp} with {@code text/event-stream} (MCP 1.0 / legacy transport).
     * Modern (MCP 2.0) clients receive HTTP 405 because they do not rely on a server-push SSE channel.
     */
    public static boolean writeStreamableHttpGetResponse(MessageContext messageContext) {
        org.apache.axis2.context.MessageContext axis2MessageContext =
                ((Axis2MessageContext) messageContext).getAxis2MessageContext();
        String headerVersion = MCPProtocolNegotiator.getTransportHeader(messageContext,
                APIConstants.MCP.MCP_PROTOCOL_VERSION_HEADER);
        if (APIConstants.MCP.isModernProtocol(headerVersion)) {
            try {
                JsonUtil.removeJsonPayload(axis2MessageContext);
                axis2MessageContext.setProperty(APIConstants.NO_ENTITY_BODY, true);
                axis2MessageContext.setProperty(APIMgtGatewayConstants.HTTP_SC, HttpStatus.SC_METHOD_NOT_ALLOWED);
                axis2MessageContext.setProperty(NhttpConstants.HTTP_SC, HttpStatus.SC_METHOD_NOT_ALLOWED);
                messageContext.setProperty("MCP_PROCESSED", "true");
                if (log.isDebugEnabled()) {
                    log.debug("Rejected Streamable HTTP GET /mcp for modern MCP protocol version: " + headerVersion);
                }
                return true;
            } catch (Exception e) {
                log.error("Error while rejecting Streamable HTTP GET /mcp for modern protocol", e);
                return false;
            }
        }
        try {
            JsonUtil.removeJsonPayload(axis2MessageContext);
            JsonUtil.getNewJsonPayload(axis2MessageContext, STREAMABLE_HTTP_SSE_PREAMBLE, true, true);
            axis2MessageContext.setProperty(Constants.Configuration.MESSAGE_TYPE, SseApiConstants.SSE_CONTENT_TYPE);
            axis2MessageContext.setProperty(Constants.Configuration.CONTENT_TYPE, SseApiConstants.SSE_CONTENT_TYPE);
            axis2MessageContext.setProperty(APIMgtGatewayConstants.HTTP_SC, HttpStatus.SC_OK);
            axis2MessageContext.setProperty(NhttpConstants.HTTP_SC, HttpStatus.SC_OK);
            messageContext.setProperty(APIConstants.CUSTOM_HTTP_STATUS_CODE, null);
            messageContext.setProperty(APIConstants.CUSTOM_ERROR_CODE, null);
            messageContext.setProperty(APIConstants.CUSTOM_ERROR_MESSAGE, null);
            axis2MessageContext.removeProperty(APIConstants.NO_ENTITY_BODY);

            Map headers = (Map) axis2MessageContext.getProperty(
                    org.apache.axis2.context.MessageContext.TRANSPORT_HEADERS);
            if (headers == null) {
                headers = new HashMap<>();
                axis2MessageContext.setProperty(org.apache.axis2.context.MessageContext.TRANSPORT_HEADERS, headers);
            }
            headers.put(HttpHeaders.CACHE_CONTROL, "no-cache");
            headers.put(HttpHeaders.CONNECTION, "keep-alive");
            headers.put(APIConstants.HEADER_CONTENT_TYPE, SseApiConstants.SSE_CONTENT_TYPE);
            markMcpStreamableHttpAsAsync(messageContext);
            messageContext.setProperty("MCP_PROCESSED", "true");
            if (log.isDebugEnabled()) {
                log.debug("Streamable HTTP GET /mcp answered with text/event-stream");
            }
            return true;
        } catch (AxisFault e) {
            log.error("Error while generating Streamable HTTP GET /mcp response "
                    + axis2MessageContext.getLogIDString(), e);
            return false;
        }
    }

    /**
     * Writes oauth-protected-resource metadata JSON to the message context.
     */
    public static boolean writeOAuthProtectedResourceMetadataResponse(MessageContext messageContext, API matchedAPI,
                                                                      List<String> fallbackScopes) {
        OAuthProtectedResourceDTO metadata =
                buildOAuthProtectedResourceMetadata(messageContext, matchedAPI, fallbackScopes);
        if (StringUtils.isEmpty(metadata.getResource())) {
            return false;
        }
        org.apache.axis2.context.MessageContext axis2MessageContext =
                ((Axis2MessageContext) messageContext).getAxis2MessageContext();
        try {
            JsonUtil.getNewJsonPayload(axis2MessageContext, new Gson().toJson(metadata), true, true);
            axis2MessageContext.setProperty(Constants.Configuration.MESSAGE_TYPE,
                    APIConstants.APPLICATION_JSON_MEDIA_TYPE);
            axis2MessageContext.setProperty(Constants.Configuration.CONTENT_TYPE,
                    APIConstants.APPLICATION_JSON_MEDIA_TYPE);
            axis2MessageContext.setProperty(APIMgtGatewayConstants.HTTP_SC, HttpStatus.SC_OK);
            axis2MessageContext.setProperty(NhttpConstants.HTTP_SC, HttpStatus.SC_OK);
            messageContext.setProperty(APIConstants.CUSTOM_HTTP_STATUS_CODE, null);
            messageContext.setProperty(APIConstants.CUSTOM_ERROR_CODE, null);
            messageContext.setProperty(APIConstants.CUSTOM_ERROR_MESSAGE, null);
            axis2MessageContext.removeProperty(APIConstants.NO_ENTITY_BODY);
            return true;
        } catch (AxisFault e) {
            log.error("Error while generating MCP oauth-protected-resource metadata " +
                    axis2MessageContext.getLogIDString(), e);
            return false;
        }
    }

}
