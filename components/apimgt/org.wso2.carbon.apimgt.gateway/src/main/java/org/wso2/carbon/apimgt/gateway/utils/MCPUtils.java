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
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.Mediator;
import org.apache.synapse.MessageContext;
import org.apache.synapse.SynapseConstants;
import org.apache.synapse.commons.json.JsonUtil;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.wso2.carbon.apimgt.api.model.subscription.URLMapping;
import org.wso2.carbon.apimgt.gateway.APIMgtGatewayConstants;
import org.wso2.carbon.apimgt.gateway.handlers.Utils;
import org.wso2.carbon.apimgt.api.model.APIOperationMapping;
import org.wso2.carbon.apimgt.api.model.BackendOperation;
import org.wso2.carbon.apimgt.api.model.BackendOperationMapping;
import org.wso2.carbon.apimgt.gateway.mcp.McpException;
import org.wso2.carbon.apimgt.gateway.mcp.McpExceptionWithId;
import org.wso2.carbon.apimgt.gateway.mcp.request.McpRequest;
import org.wso2.carbon.apimgt.gateway.mcp.request.Params;
import org.wso2.carbon.apimgt.gateway.mcp.response.McpResponseDto;
import org.wso2.carbon.apimgt.gateway.mcp.transformer.exception.MCPRequestResolverException;
import org.wso2.carbon.apimgt.gateway.mcp.transformer.impl.PrefixBasedSchemaMappingParser;
import org.wso2.carbon.apimgt.gateway.mcp.transformer.impl.RequestResolver;
import org.wso2.carbon.apimgt.gateway.mcp.transformer.model.ResolvedRequest;
import org.wso2.carbon.apimgt.gateway.mcp.transformer.model.SchemaMapping;
import org.wso2.carbon.apimgt.keymgt.model.entity.API;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.wso2.carbon.apimgt.impl.APIConstants.APPLICATION_JSON_MEDIA_TYPE;
import static org.wso2.carbon.apimgt.impl.APIConstants.RESOURCE_METHOD;

public class MCPUtils {

    public static final String PROTOCOL_VERSION_REQUESTED = "requested";

    public static final String PROTOCOL_VERSION_SUPPORTED = "supported";

    public static final int INVALID_REQUEST_CODE = -32600;

    public static final String INVALID_REQUEST_MESSAGE = "Invalid Request";

    public static final String JSON_RPC_VERSION = "2.0";

    public static final int METHOD_NOT_FOUND_CODE = -32601;

    public static final String METHOD_NOT_FOUND_MESSAGE = "Method not found";

    public static final String MCP_DEFAULT_FEATURE_TYPE = "TOOL";

    public static final String MCP_DEFAULT_BACKEND_NAME = "Default Backend";

    public static final String MCP_ENABLED = "MCP_ENABLED";

    public static final String METHOD_INITIALIZE = "initialize";

    public static final String METHOD_TOOL_LIST = "tools/list";

    public static final String METHOD_TOOL_CALL = "tools/call";

    public static final String METHOD_PING = "ping";

    public static final String METHOD_NOTIFICATION_INITIALIZED = "notifications/initialized";

    public static final String METHOD_RESOURCES_LIST = "resources/list";

    public static final String METHOD_RESOURCE_TEMPLATE_LIST = "resources/templates/list";

    public static final String METHOD_PROMPTS_LIST = "prompts/list";

    public static final List<String> ALLOWED_METHODS = Arrays.asList(METHOD_INITIALIZE, METHOD_TOOL_LIST,
            METHOD_TOOL_CALL, METHOD_PING, METHOD_NOTIFICATION_INITIALIZED, METHOD_RESOURCES_LIST, METHOD_PROMPTS_LIST,
            METHOD_RESOURCE_TEMPLATE_LIST);

    public static final String PROTOCOL_VERSION_2025_JUNE = "2025-06-18";

    public static final List<String> SUPPORTED_PROTOCOL_VERSIONS = Arrays.asList(PROTOCOL_VERSION_2025_JUNE);

    public static final String JSON_RPC = "jsonrpc";

    public static final String METHOD = "method";

    public static final String ID = "id";

    public static final int PARSE_ERROR_CODE = -32700;

    public static final int INVALID_PARAMS_CODE = -32602;

    public static final int INTERNAL_ERROR_CODE = -32603;

    public static final int CONNECTION_CLOSED = -32000;

    public static final String PARSE_ERROR_MESSAGE = "Parse error";

    public static final String INVALID_PARAMS_MESSAGE = "Invalid params";

    public static final String INTERNAL_ERROR_MESSAGE = "Internal error";

    public static final String CONNECTION_CLOSED_MESSAGE = "Connection closed";

    public static final String PROTOCOL_MISMATCH_ERROR = "Unsupported protocol version";

    public static final String API_SUBTYPE_DIRECT_BACKEND = "DIRECT_BACKEND";

    public static final String API_SUBTYPE_EXISTING_API = "EXISTING_API";

    public static final String MCP_PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version";

    public static final String HEADER_CONTENT_TYPE = "Content-Type";

    public static final String HEADER_ACCEPT = "Accept";

    public static final String HEADER_MCP_SESSION_ID = "Mcp-Session-Id";

    public static final String ACCEPT_JSON_AND_SSE = "application/json, text/event-stream";

    public static final String MCP_FAILURE_HANDLER = "_mcp_failure_handler_";

    public static final String RECEIVED_MCP_ID = "RECEIVED_MCP_ID";

    private static final Log log = LogFactory.getLog(MCPUtils.class);

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
            throw new McpException(INVALID_REQUEST_CODE,
                    INVALID_REQUEST_MESSAGE, "Missing jsonrpc field");
        }
        if (!JSON_RPC_VERSION.equals(jsonRpcVersion)) {
            throw new McpException(INVALID_REQUEST_CODE,
                    INVALID_REQUEST_MESSAGE, "Invalid JSON-RPC version");
        }

        String method = request.getMethod();
        if (StringUtils.isEmpty(method)) {
            throw new McpException(INVALID_REQUEST_CODE,
                    INVALID_REQUEST_MESSAGE, "Missing method field");
        }
        if (!ALLOWED_METHODS.contains(method)) {
            throw new McpException(METHOD_NOT_FOUND_CODE,
                    METHOD_NOT_FOUND_MESSAGE, "Method " + method + " not found");
        }

        Object id = request.getId();
        if (id == null && !METHOD_NOTIFICATION_INITIALIZED.equals(method)) {
            throw new McpException(INVALID_REQUEST_CODE,
                    INVALID_REQUEST_MESSAGE, "Missing id field");
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
                case METHOD_INITIALIZE:
                    validateInitializeRequest(id, requestObject);
                    return handleMcpInitialize(id, matchedMcpApi);
                case METHOD_TOOL_LIST:
                    return handleMcpToolList(id, matchedMcpApi, false);
                case METHOD_TOOL_CALL:
                    validateToolsCallRequest(requestObject, matchedMcpApi);
                    return handleMcpToolsCall(messageContext, id, matchedMcpApi, requestObject);
                case METHOD_PING:
                    return handleMcpPing(id);
                case METHOD_RESOURCES_LIST:
                    return new McpResponseDto(MCPPayloadGenerator.generateResourceListResponse(id), 200, null);
                case METHOD_RESOURCE_TEMPLATE_LIST:
                    return new McpResponseDto(MCPPayloadGenerator.generateResourceTemplateListResponse(id), 200, null);
                case METHOD_PROMPTS_LIST:
                    return new McpResponseDto(MCPPayloadGenerator.generatePromptListResponse(id), 200, null);
                case METHOD_NOTIFICATION_INITIALIZED:
                    // We don't need to send a reply when it's a notification
                    return null;
                default:
                    throw new McpException(METHOD_NOT_FOUND_CODE,
                            METHOD_NOT_FOUND_MESSAGE, "Method not found");
            }
        } catch (McpException e) {
            return new McpResponseDto(e.toJsonRpcErrorPayload(), 200, null);
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
                if (!SUPPORTED_PROTOCOL_VERSIONS.contains(protocolVersion)) {
                    throw new McpExceptionWithId(id, INVALID_PARAMS_CODE,
                            PROTOCOL_MISMATCH_ERROR,
                            MCPPayloadGenerator.getInitializeErrorBody(protocolVersion));
                }
            } else {
                throw new McpException(INVALID_REQUEST_CODE,
                        INVALID_REQUEST_MESSAGE, "Missing protocolVersion field");
            }
        } else {
            throw new McpException(INVALID_REQUEST_CODE,
                    INVALID_REQUEST_MESSAGE, "Missing params field");
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
                throw new McpException(INVALID_REQUEST_CODE,
                        INVALID_REQUEST_MESSAGE, "Missing toolName field");
            } else {
                if (!validateToolName(toolName, matchedApi)) {
                    throw new McpException(INVALID_REQUEST_CODE,
                            INVALID_REQUEST_MESSAGE, "The requested tool does not exist");
                }
            }
        } else {
            throw new McpException(INVALID_REQUEST_CODE,
                    INVALID_REQUEST_MESSAGE, "Missing params field");
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
     * @param id         id of the request
     * @param matchedApi matched API in the gateway
     * @return the response payload as a String
     */
    public static McpResponseDto handleMcpInitialize(Object id, API matchedApi) {
        String name = matchedApi.getName();
        String version = matchedApi.getVersion();
        String description = "This is an MCP Server";
        return new McpResponseDto(MCPPayloadGenerator
                .getInitializeResponse(id, name, version, description, false),
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
            if (API_SUBTYPE_EXISTING_API.equals(subType)) {
                APIOperationMapping apiOperationMapping = extendedOperation.getApiOperationMapping();
                if (apiOperationMapping != null) {
                    backendOperation = apiOperationMapping.getBackendOperation();
                }
            } else if (API_SUBTYPE_DIRECT_BACKEND.equals(subType)) {
                BackendOperationMapping backendOperationMapping = extendedOperation.getBackendOperationMapping();
                if (backendOperationMapping != null) {
                    backendOperation = backendOperationMapping.getBackendOperation();
                }
            }

            if (backendOperation != null) {
                //process schema
                String schemaDefinition = extendedOperation.getSchemaDefinition();
                if (StringUtils.isEmpty(schemaDefinition)) {
                    throw new McpException(INTERNAL_ERROR_CODE,
                            INTERNAL_ERROR_MESSAGE,
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
                    messageContext.setProperty(RECEIVED_MCP_ID, id);
                } catch (MCPRequestResolverException e) {
                    throw new McpExceptionWithId(id, INTERNAL_ERROR_CODE,
                            INTERNAL_ERROR_MESSAGE, e.getMessage());
                }
            } else {
                throw new McpExceptionWithId(id, INTERNAL_ERROR_CODE,
                        INTERNAL_ERROR_MESSAGE, "No matched tool found");
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
                    throw new McpException(INVALID_PARAMS_CODE,
                            INVALID_PARAMS_MESSAGE,
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
        axis2MessageContext.setProperty(RESOURCE_METHOD, httpMethod.toString().toUpperCase());
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
            if (ct.startsWith(APPLICATION_JSON_MEDIA_TYPE)) {
                try {
                    JsonUtil.removeJsonPayload(axis2MessageContext);
                    JsonObject safePayload = (payload != null) ? payload : new JsonObject();
                    JsonUtil.getNewJsonPayload(axis2MessageContext, new Gson().toJson(safePayload), true, true);
                    axis2MessageContext.setProperty(Constants.Configuration.MESSAGE_TYPE,
                            APPLICATION_JSON_MEDIA_TYPE);
                    axis2MessageContext.setProperty(Constants.Configuration.CONTENT_TYPE,
                            APPLICATION_JSON_MEDIA_TYPE);
                } catch (AxisFault e) {
                    throw new McpException(INTERNAL_ERROR_CODE,
                            INTERNAL_ERROR_MESSAGE,
                            "Failed to process JSON request body: " + e.getMessage());
                }
            } else {
                throw new McpException(INTERNAL_ERROR_CODE,
                        INTERNAL_ERROR_MESSAGE,
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
        throw new McpException(INVALID_REQUEST_CODE,
                INVALID_REQUEST_MESSAGE, "Missing jsonrpc field");
    }

    /**
     * Throws an error for an invalid jsonrpc version.
     *
     * @throws McpException always
     */
    private static void throwMissingIdError() throws McpException {
        throw new McpException(INVALID_REQUEST_CODE,
                INVALID_REQUEST_MESSAGE, "Missing id field");
    }

    /**
     * Throws an error for a missing method field.
     *
     * @throws McpException always
     */
    private static void throwMissingMethodError() throws McpException {
        throw new McpException(INVALID_REQUEST_CODE,
                INVALID_REQUEST_MESSAGE, "Missing method field");
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
        Mediator sequence = messageContext.getSequence(MCP_FAILURE_HANDLER);
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
            axis2MC.setProperty(Constants.Configuration.MESSAGE_TYPE, APPLICATION_JSON_MEDIA_TYPE);
            axis2MC.setProperty(Constants.Configuration.CONTENT_TYPE, APPLICATION_JSON_MEDIA_TYPE);
        } catch (AxisFault e) {
            log.warn("Failed to set MCP error JSON payload", e);
        }
        Utils.sendFault(messageContext, responseDto.getStatusCode());
    }


}
