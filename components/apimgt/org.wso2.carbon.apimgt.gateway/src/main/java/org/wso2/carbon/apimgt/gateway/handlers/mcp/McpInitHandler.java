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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import org.apache.axiom.om.OMElement;
import org.apache.axiom.soap.SOAPEnvelope;
import org.apache.axis2.AxisFault;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.ManagedLifecycle;
import org.apache.synapse.MessageContext;
import org.apache.synapse.commons.json.JsonUtil;
import org.apache.synapse.core.SynapseEnvironment;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.rest.AbstractHandler;
import org.apache.synapse.transport.passthru.util.RelayUtils;
import org.wso2.carbon.apimgt.api.model.APIOperationMapping;
import org.wso2.carbon.apimgt.api.model.BackendOperation;
import org.wso2.carbon.apimgt.api.model.BackendOperationMapping;
import org.wso2.carbon.apimgt.api.model.subscription.URLMapping;
import org.wso2.carbon.apimgt.gateway.exception.McpException;
import org.wso2.carbon.apimgt.gateway.mcp.request.MCPRequestDeserializer;
import org.wso2.carbon.apimgt.gateway.mcp.request.McpRequest;
import org.wso2.carbon.apimgt.gateway.mcp.request.Params;
import org.wso2.carbon.apimgt.gateway.mcp.request.ParamsDeserializer;
import org.wso2.carbon.apimgt.gateway.mcp.response.McpResponseDto;
import org.wso2.carbon.apimgt.gateway.handlers.Utils;
import org.apache.http.HttpStatus;
import org.wso2.carbon.apimgt.gateway.utils.GatewayUtils;
import org.wso2.carbon.apimgt.gateway.utils.MCPUtils;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.keymgt.model.entity.API;

import javax.xml.stream.XMLStreamException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.wso2.carbon.apimgt.impl.APIConstants.DigestAuthConstants.HTTP_METHOD;
import static org.wso2.carbon.apimgt.impl.APIConstants.MCP.HEADER_MCP_SESSION_ID;
import static org.wso2.carbon.apimgt.impl.APIConstants.MCP.METHOD_INITIALIZE;
import static org.wso2.carbon.apimgt.impl.APIConstants.MCP.METHOD_NOTIFICATION_INITIALIZED;
import static org.wso2.carbon.apimgt.impl.APIConstants.MCP.METHOD_PING;
import static org.wso2.carbon.apimgt.impl.APIConstants.MCP.METHOD_PROMPTS_LIST;
import static org.wso2.carbon.apimgt.impl.APIConstants.MCP.METHOD_RESOURCES_LIST;
import static org.wso2.carbon.apimgt.impl.APIConstants.MCP.METHOD_RESOURCE_TEMPLATE_LIST;
import static org.wso2.carbon.apimgt.impl.APIConstants.MCP.METHOD_TOOL_CALL;
import static org.wso2.carbon.apimgt.impl.APIConstants.MCP.METHOD_TOOL_LIST;
import static org.wso2.carbon.apimgt.impl.APIConstants.MCP.RpcConstants.INVALID_REQUEST_CODE;
import static org.wso2.carbon.apimgt.impl.APIConstants.MCP.RpcConstants.INVALID_REQUEST_MESSAGE;
import static org.wso2.carbon.apimgt.impl.APIConstants.MCP.RpcConstants.METHOD_NOT_FOUND_CODE;
import static org.wso2.carbon.apimgt.impl.APIConstants.MCP.RpcConstants.METHOD_NOT_FOUND_MESSAGE;

public class McpInitHandler extends AbstractHandler implements ManagedLifecycle {

    /**
     * MCP related Constants
     */
    public static final String MCP_METHOD = "api.ut.MCP_METHOD";

    public static final String MCP_REQUEST_BODY = "MCP_REQUEST_BODY";

    public static final String MCP_NO_AUTH_REQUEST = "MCP_NO_AUTH_REQUEST";

    public static final String MCP_RESOURCE = "/mcp";

    public static final String MCP_WELL_KNOWN_RESOURCE = "/.well-known/oauth-protected-resource";

    public static final String MCP_AUTH_CLAIM = "MCP_AUTHENTICATED";

    public static final Long MCP_AUTH_TOKEN_EXPIRATION_TIME = 6000L;

    public static final String MCP_TOOL_PARAMS = "MCP_TOOL_PARAMS";

    public static final String MCP_RESULT_IS_ERROR = "MCP_RESULT_IS_ERROR";

    private static final Log log = LogFactory.getLog(McpInitHandler.class);

    private static final Gson MCP_REQUEST_GSON = new GsonBuilder()
            .registerTypeAdapter(McpRequest.class, new MCPRequestDeserializer())
            .registerTypeAdapter(Params.class, new ParamsDeserializer())
            .create();

    private static final Gson MCP_RESPONSE_GSON = new Gson();

    static ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void init(SynapseEnvironment synapseEnvironment) {
        log.debug("Initializing MCP Init Handler instance");

    }

    @Override
    public void destroy() {

    }

    @Override
    public boolean handleRequest(MessageContext messageContext) {
        try {
            String path = Utils.getMcpRequestPath(messageContext);
            String httpMethod = (String) messageContext.getProperty(HTTP_METHOD);

            // If path or httpMethod is null, try to get from axis2 context
            org.apache.axis2.context.MessageContext axis2MC =
                    ((Axis2MessageContext) messageContext).getAxis2MessageContext();
            if (StringUtils.isEmpty(httpMethod)) {
                httpMethod = (String) axis2MC.getProperty(
                        org.apache.axis2.Constants.Configuration.HTTP_METHOD);
            }

            messageContext.setProperty("isMcp", 1);
            String httpsPort = System.getProperty("https.nio.port");
            if (!StringUtils.isEmpty(httpsPort)) {
                messageContext.setProperty("uri.var.httpsPort", httpsPort);
            } else {
                if (log.isDebugEnabled()) {
                    log.debug("https.nio.port could not be resolved from System properties, hence default " +
                            "value was set");
                }
                messageContext.setProperty("uri.var.httpsPort", "8243");
            }

            if (StringUtils.startsWith(path, MCP_WELL_KNOWN_RESOURCE) &&
                    StringUtils.equals(APIConstants.HTTP_GET, httpMethod)) {
                messageContext.setProperty(MCP_NO_AUTH_REQUEST, true);
            } else if (Utils.isMcpStreamableHttpGetRequest(path, httpMethod)) {
                // Streamable HTTP clients (e.g. WorkBuddy) open GET .../mcp for an SSE channel before POST JSON-RPC.
                messageContext.setProperty(MCP_NO_AUTH_REQUEST, true);
                messageContext.setProperty("MCP_HTTP_METHOD", APIConstants.HTTP_GET);
                messageContext.setProperty("MCP_API_ELECTED_RESOURCE", MCP_RESOURCE);
            } else {
                boolean isNoAuthMCPRequest = isNoAuthMCPRequest(buildMCPRequest(messageContext));
                messageContext.setProperty(MCP_NO_AUTH_REQUEST, isNoAuthMCPRequest);
            }
        } catch (McpException e) {
            // Use HTTP 200 for JSON-RPC errors as per JSON-RPC 2.0 specification
            // The error details are included in the JSON response body
            MCPUtils.handleMCPFailure(messageContext, new McpResponseDto(e.toJsonRpcErrorPayload(), 401, null));
            return false;
        }
        return true;
    }

    @Override
    public boolean handleResponse(MessageContext messageContext) {
        org.apache.axis2.context.MessageContext axis2MessageContext =
                ((Axis2MessageContext) messageContext).getAxis2MessageContext();
        Map headers = (Map) axis2MessageContext.getProperty(org.apache.axis2.context.MessageContext.TRANSPORT_HEADERS);
        if (headers == null) {
            headers = new HashMap<>();
            axis2MessageContext.setProperty(org.apache.axis2.context.MessageContext.TRANSPORT_HEADERS, headers);
        }

        Object exposeHeadersList = headers.get(APIConstants.CORSHeaders.ACCESS_CONTROL_EXPOSE_HEADERS);
        String exposeHeaders = HEADER_MCP_SESSION_ID;
        if (exposeHeadersList instanceof String) {
            exposeHeaders = (String) exposeHeadersList;
            if (!StringUtils.isEmpty(exposeHeaders) && !exposeHeaders.contains(HEADER_MCP_SESSION_ID)) {
                exposeHeaders += "," + HEADER_MCP_SESSION_ID;
            }
        }
        headers.put(APIConstants.CORSHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, exposeHeaders);
        if (messageContext.getProperty("isMcp") != null) {
            int responseStatusCode = MCPUtils.resolveResponseStatusCode(messageContext);
            if (responseStatusCode == HttpStatus.SC_UNAUTHORIZED) {
                MCPUtils.setMcpWwwAuthenticateHeader(messageContext, HttpStatus.SC_UNAUTHORIZED, null, null);
            }
        }
        // Fix duplicate Content-Type issue: PassThrough Transport combines TRANSPORT_HEADERS Content-Type
        // with Axis2 Configuration.CONTENT_TYPE property, causing duplicates like
        // "text/event-stream,text/event-stream; charset=UTF-8"
        // Solution: Remove the Axis2 CONTENT_TYPE property so only TRANSPORT_HEADERS Content-Type is used
        synchronizeContentTypeHeaders(axis2MessageContext, headers);
        // Parse MCP response and extract isError from result
        parseMcpResponseAndExtractIsError(messageContext, axis2MessageContext);

        return true;
    }

    /**
     * Fixes duplicate Content-Type header for MCP responses.
     * <p>
     * PassThrough Transport combines TRANSPORT_HEADERS Content-Type with Axis2 Configuration.CONTENT_TYPE,
     * causing duplicates like: "text/event-stream,text/event-stream; charset=UTF-8"
     * or "application/json,application/json; charset=UTF-8"
     * <p>
     * This method removes Content-Type from transport headers and only uses Axis2 properties
     * to ensure a single Content-Type value in the response.
     *
     * @param axis2MessageContext The Axis2 message context
     * @param headers             The transport headers map
     */
    private void synchronizeContentTypeHeaders(org.apache.axis2.context.MessageContext axis2MessageContext,
                                               Map headers) {
        Object transportContentType = headers.get(APIConstants.HEADER_CONTENT_TYPE);

        if (transportContentType != null) {
            String contentTypeStr = transportContentType.toString();

            // Extract the base media type (e.g., "text/event-stream" or "application/json")
            String baseMediaType = extractBaseMediaType(contentTypeStr);

            if (baseMediaType != null) {
                // Remove Content-Type from transport headers completely to prevent duplication
                headers.remove(APIConstants.HEADER_CONTENT_TYPE);
                headers.remove("content-type");  // Also try lowercase variant
                headers.remove("Content-type");  // Mixed case

                // Set Axis2 properties to the base media type without any charset
                axis2MessageContext.setProperty(org.apache.axis2.Constants.Configuration.CONTENT_TYPE,
                        baseMediaType);
                axis2MessageContext.setProperty(org.apache.axis2.Constants.Configuration.MESSAGE_TYPE,
                        baseMediaType);

                // Remove CHARACTER_SET_ENCODING to prevent charset=UTF-8 from being appended
                axis2MessageContext.removeProperty(org.apache.axis2.Constants.Configuration.CHARACTER_SET_ENCODING);

                // Set this flag to tell PassThrough Transport not to determine content type automatically
                axis2MessageContext.setProperty("TRANSPORT_IN_CONTENT_TYPE", baseMediaType);
            }
        }
    }

    /**
     * Extracts the base media type from a potentially duplicated Content-Type string.
     * <p>
     * Examples:
     * - "text/event-stream,text/event-stream; charset=UTF-8" -> "text/event-stream"
     * - "application/json,application/json; charset=UTF-8" -> "application/json"
     * - "application/json; charset=UTF-8" -> "application/json"
     * - "text/event-stream" -> "text/event-stream"
     *
     * @param contentType The Content-Type string which may contain duplicates
     * @return The base media type without duplicates or charset
     */
    private String extractBaseMediaType(String contentType) {
        if (StringUtils.isEmpty(contentType)) {
            return null;
        }

        // If contains comma, it might be duplicated (e.g., "text/event-stream,text/event-stream; charset=UTF-8")
        if (contentType.contains(",")) {
            // Take the first part before comma
            String firstPart = contentType.split(",")[0].trim();
            // Remove any parameters (like charset) from the first part
            if (firstPart.contains(";")) {
                return firstPart.split(";")[0].trim();
            }
            return firstPart;
        }

        // If contains semicolon, extract the media type part (e.g., "application/json; charset=UTF-8")
        if (contentType.contains(";")) {
            return contentType.split(";")[0].trim();
        }

        // Return as-is
        return contentType.trim();
    }

    /**
     * This method is used to set API related parameters to the message context.
     *
     * @param messageContext The message context to which the parameters are set
     * @return MCP Method involved with the request
     */
    private String buildMCPRequest(MessageContext messageContext) throws McpException {
        if (log.isDebugEnabled()) {
            log.debug("Handling MCP request");
        }
        String messageBody;
        String method;
        try {
            org.apache.axis2.context.MessageContext axis2MC = ((Axis2MessageContext) messageContext)
                    .getAxis2MessageContext();
            RelayUtils.buildMessage(axis2MC);
            if (JsonUtil.hasAJsonPayload(axis2MC)) {
                messageBody = JsonUtil.jsonPayloadToString(axis2MC);

                if (log.isDebugEnabled()) {
                    log.debug("MCP request body: " + messageBody);
                }
                McpRequest request = MCP_REQUEST_GSON.fromJson(messageBody, McpRequest.class);
                if (!MCPUtils.validateRequest(request)) {
                    throw new McpException(INVALID_REQUEST_CODE,
                            INVALID_REQUEST_MESSAGE, "Invalid Request");
                }

                method = request.getMethod();
                messageContext.setProperty(MCP_METHOD, method);
                // Store the parsed McpRequest object (not the raw JSON string)
                // McpMediator expects McpRequest type for this property
                messageContext.setProperty(MCP_REQUEST_BODY, request);

                if (StringUtils.equals(method, METHOD_TOOL_CALL)) {
                    Params params = request.getParams();
                    String toolName = params.getToolName();
                    API api = GatewayUtils.getAPI(messageContext);
                    URLMapping extendedOperation = api.getUrlMappings()
                            .stream()
                            .filter(operation -> operation.getUrlPattern().equals(toolName))
                            .findFirst()
                            .orElse(null);

                    BackendOperation backendOperation = null;
                    if (extendedOperation != null) { //direct_endpoint
                        BackendOperationMapping backendAPIOperationMapping =
                                extendedOperation.getBackendOperationMapping();
                        if (backendAPIOperationMapping != null) {
                            backendOperation = backendAPIOperationMapping.getBackendOperation();
                        } else { //existing_api
                            APIOperationMapping existingAPIOperationMapping =
                                    extendedOperation.getApiOperationMapping();
                            if (existingAPIOperationMapping != null) {
                                backendOperation = existingAPIOperationMapping.getBackendOperation();
                            }
                        }
                        if (backendOperation != null) {
                            messageContext.setProperty("MCP_HTTP_METHOD", backendOperation.getVerb());
                            messageContext.setProperty("MCP_API_ELECTED_RESOURCE", backendOperation.getTarget());
                        }
                    }
                } else if (StringUtils.equals(method, METHOD_INITIALIZE) || StringUtils.equals(method, METHOD_TOOL_LIST)) {
                    // tools/list is served on POST /mcp; map auth/throttle to that resource, not /* or tool paths.
                    messageContext.setProperty("MCP_HTTP_METHOD", APIConstants.HTTP_POST);
                    messageContext.setProperty("MCP_API_ELECTED_RESOURCE", MCP_RESOURCE);
                }
            } else {
                throw new McpException(INVALID_REQUEST_CODE,
                        INVALID_REQUEST_MESSAGE, "No JSON-RPC payload found");
            }
        } catch (McpException e) {
            // Re-throw McpException as-is
            throw e;
        } catch (JsonSyntaxException e) {
            log.error("JSON syntax error while parsing MCP request", e);
            throw new McpException(INVALID_REQUEST_CODE,
                    INVALID_REQUEST_MESSAGE, "Invalid JSON-RPC payload: " + e.getMessage());
        } catch (IOException | XMLStreamException e) {
            log.error("Error while processing MCP request", e);
            throw new McpException(INVALID_REQUEST_CODE,
                    INVALID_REQUEST_MESSAGE, "Error processing JSON-RPC payload: " + e.getMessage());
        }
        return method;
    }

    private boolean isNoAuthMCPRequest(String method) throws McpException {
        switch (method) {
            case METHOD_PING:
            case METHOD_NOTIFICATION_INITIALIZED:
            case METHOD_RESOURCES_LIST:
            case METHOD_RESOURCE_TEMPLATE_LIST:
            case METHOD_PROMPTS_LIST:
                return true;
            case METHOD_INITIALIZE:
            case METHOD_TOOL_LIST:
            case METHOD_TOOL_CALL:
                return false;

            default:
                throw new McpException(
                        METHOD_NOT_FOUND_CODE,
                        METHOD_NOT_FOUND_MESSAGE,
                        "Method not found"
                );
        }
    }

    /**
     * Parse MCP response and extract isError from result for audit logging.
     *
     * @param messageContext      The Synapse message context
     * @param axis2MessageContext The Axis2 message context
     */
    private void parseMcpResponseAndExtractIsError(MessageContext messageContext,
                                                   org.apache.axis2.context.MessageContext axis2MessageContext) {
        try {
            // Check if this is an MCP request
            if (messageContext.getProperty("isMcp") == null) {
                return;
            }

            // Get MCP method to understand what kind of response we're dealing with
            String mcpMethod = (String) messageContext.getProperty(MCP_METHOD);
            log.debug("MCP handleResponse - mcpMethod: " + mcpMethod);

            // isError extraction is only needed for tools/call backend responses (audit logging).
            if (!APIConstants.MCP.METHOD_TOOL_CALL.equals(mcpMethod)) {
                return;
            }

            // Log Content-Type for debugging
            String contentType = (String) axis2MessageContext.getProperty(
                    org.apache.axis2.Constants.Configuration.CONTENT_TYPE);
            String messageType = (String) axis2MessageContext.getProperty(
                    org.apache.axis2.Constants.Configuration.MESSAGE_TYPE);
            log.debug("MCP response - Content-Type: " + contentType + ", Message-Type: " + messageType);

            // Build message if not already built
            try {
                RelayUtils.buildMessage(axis2MessageContext);
            } catch (Exception e) {
                log.warn("Failed to build message in handleResponse: " + e.getMessage());
            }

            String responseBody = null;

            // Check if this is SSE (text/event-stream) response
            boolean isSseResponse = contentType != null && contentType.contains("text/event-stream");

            // First, try to get response body from JSON payload
            boolean hasJsonPayload = JsonUtil.hasAJsonPayload(axis2MessageContext);
            log.debug("MCP response - hasAJsonPayload: " + hasJsonPayload + ", isSseResponse: " + isSseResponse);

            if (hasJsonPayload) {
                responseBody = JsonUtil.jsonPayloadToString(axis2MessageContext);
                log.debug("MCP response body retrieved from JSON payload");
            } else {
                // For SSE or non-JSON responses, try to get body from SOAPEnvelope
                SOAPEnvelope envelope = messageContext.getEnvelope();
                if (envelope != null && envelope.getBody() != null) {
                    OMElement firstElement = envelope.getBody().getFirstElement();
                    if (firstElement != null) {
                        String localName = firstElement.getLocalName();
                        log.debug("MCP response - SOAPEnvelope firstElement localName: " + localName);

                        // For SSE responses, the content is usually in a text element
                        if (isSseResponse || "text".equals(localName) || "binary".equals(localName)) {
                            // Get raw text content for SSE
                            String textContent = firstElement.getText();
                            if (!StringUtils.isEmpty(textContent)) {
                                responseBody = textContent;
                                log.debug("MCP response body retrieved from SOAPEnvelope text element, length: " +
                                        responseBody.length());
                            }
                        }

                        // If still no response body, try other approaches
                        if (responseBody == null) {
                            try {
                                // Try to convert XML/OMElement to JSON string
                                responseBody = JsonUtil.toJsonString(firstElement).toString();
                                log.debug("MCP response body converted from OMElement to JSON");
                            } catch (AxisFault e) {
                                // If conversion fails, try to get text content directly
                                String textContent = firstElement.getText();
                                if (!StringUtils.isEmpty(textContent)) {
                                    responseBody = textContent;
                                    log.debug("MCP response body retrieved as text content");
                                } else {
                                    // Last resort: get the entire body as string
                                    responseBody = firstElement.toString();
                                    log.debug("MCP response body retrieved as string representation");
                                }
                            }
                        }
                    } else {
                        log.debug("MCP response - SOAPEnvelope body has no first element");
                    }
                } else {
                    log.debug("MCP response - SOAPEnvelope or body is null");
                }

                if (responseBody == null) {
                    log.debug("MCP response body could not be retrieved, skipping isError extraction");
                    return;
                }
            }

            // Log response body for debugging (truncate if too long)
            if (responseBody != null) {
                String logBody = responseBody.length() > 500 ?
                        responseBody.substring(0, 500) + "... (truncated)" : responseBody;
                log.debug("MCP response body: " + logBody);
            }

            if (StringUtils.isEmpty(responseBody)) {
                if (log.isDebugEnabled()) {
                    log.debug("MCP response body is empty, skipping isError extraction");
                }
                return;
            }

            if (log.isDebugEnabled()) {
                log.debug("MCP response body: " + responseBody);
            }

            com.google.gson.JsonObject jsonObject = null;

            // Extract isError from result
            com.google.gson.JsonObject resultObject = null;

            // First, check if this is raw SSE format (starts with "event:" or "data:")
            // SSE format example:
            // event: message
            // data: {"jsonrpc":"2.0","id":1,"result":{"content":[...],"isError":false}}
            if (isSseResponse || responseBody.startsWith("event:") || responseBody.startsWith("data:")) {
                log.debug("MCP response is in SSE format, parsing SSE data lines");

                // Split by different line endings (SSE uses \r\n, \n, or \r)
                String[] lines = responseBody.split("\\r?\\n|\\r");
                for (String line : lines) {
                    line = line.trim();
                    if (line.startsWith("data:")) {
                        // Remove "data:" prefix (with or without space after colon)
                        String dataJson = line.substring(5).trim();
                        if (StringUtils.isEmpty(dataJson)) {
                            continue;
                        }
                        log.debug("MCP SSE data line: " + dataJson);
                        try {
                            com.google.gson.JsonObject dataObject =
                                    MCP_RESPONSE_GSON.fromJson(dataJson, com.google.gson.JsonObject.class);
                            if (dataObject != null) {
                                if (dataObject.has("result")) {
                                    resultObject = dataObject.getAsJsonObject("result");
                                    log.debug("Extracted result object from SSE data field");
                                    break;
                                } else if (dataObject.has("error")) {
                                    // JSON-RPC error response
                                    log.debug("MCP SSE response contains error field");
                                    messageContext.setProperty(MCP_RESULT_IS_ERROR, true);
                                    return;
                                }
                            }
                        } catch (JsonSyntaxException e) {
                            log.warn("Failed to parse SSE data line as JSON: " + e.getMessage());
                        }
                    }
                }
            } else {
                // Try to parse as JSON object
                try {
                    jsonObject = MCP_RESPONSE_GSON.fromJson(responseBody, com.google.gson.JsonObject.class);
                } catch (JsonSyntaxException e) {
                    // If parsing fails, it might be a streamable HTTP format (multiple JSON lines)
                    log.debug("Failed to parse as single JSON object: " + e.getMessage());
                }
            }

            // Handle JSON wrapped SSE format: {"text": "event: message\r\ndata: {...}\r\n\r\n"}
            if (resultObject == null && jsonObject != null && jsonObject.has("text")) {
                String textContent = jsonObject.get("text").getAsString();
                log.debug("MCP response contains text field (wrapped SSE format), extracting data");

                // Extract JSON from "data: {...}" line in SSE format
                String[] lines = textContent.split("\\r?\\n|\\r");
                for (String line : lines) {
                    line = line.trim();
                    if (line.startsWith("data:")) {
                        String dataJson = line.substring(5).trim();
                        if (StringUtils.isEmpty(dataJson)) {
                            continue;
                        }
                        try {
                            com.google.gson.JsonObject dataObject =
                                    MCP_RESPONSE_GSON.fromJson(dataJson, com.google.gson.JsonObject.class);
                            if (dataObject != null && dataObject.has("result")) {
                                resultObject = dataObject.getAsJsonObject("result");
                                log.debug("Extracted result object from wrapped SSE data field");
                                break;
                            }
                        } catch (JsonSyntaxException e) {
                            log.warn("Failed to parse data field from wrapped SSE format: " + e.getMessage());
                        }
                    }
                }
            }
            // If result not yet found, try other JSON formats
            if (resultObject == null && jsonObject != null) {
                // Handle streamableHttp format response
                if (jsonObject.has("streamableHttp")) {
                    com.google.gson.JsonObject streamableHttpObject = jsonObject.getAsJsonObject("streamableHttp");
                    log.debug("MCP response contains streamableHttp field, extracting result");
                    if (streamableHttpObject != null && streamableHttpObject.has("result")) {
                        resultObject = streamableHttpObject.getAsJsonObject("result");
                    } else if (streamableHttpObject != null && streamableHttpObject.has("data")) {
                        com.google.gson.JsonObject dataObject = streamableHttpObject.getAsJsonObject("data");
                        if (dataObject != null && dataObject.has("result")) {
                            resultObject = dataObject.getAsJsonObject("result");
                        }
                    }
                }
                // Handle standard JSON-RPC format: {"jsonrpc": "2.0", "id": 2, "result": {...}}
                else if (jsonObject.has("result")) {
                    resultObject = jsonObject.getAsJsonObject("result");
                    log.debug("MCP response contains result field (standard JSON-RPC format)");
                }
                // Handle nested data.result format: {"data": {"result": {...}}}
                else if (jsonObject.has("data")) {
                    com.google.gson.JsonObject dataObject = jsonObject.getAsJsonObject("data");
                    if (dataObject != null && dataObject.has("result")) {
                        resultObject = dataObject.getAsJsonObject("result");
                        log.debug("MCP response contains data.result field");
                    }
                }
                // Check for error in JSON-RPC response
                else if (jsonObject.has("error")) {
                    log.debug("MCP response contains error field (JSON-RPC error)");
                    messageContext.setProperty(MCP_RESULT_IS_ERROR, true);
                    return;
                }
            }

            // If still no result, try NDJSON format (multiple JSON lines)
            if (resultObject == null && responseBody != null && responseBody.contains("\n") && !isSseResponse) {
                log.debug("MCP response appears to be in NDJSON format, parsing each line");
                String[] jsonLines = responseBody.split("\n");
                for (String jsonLine : jsonLines) {
                    if (StringUtils.isEmpty(jsonLine.trim())) {
                        continue;
                    }
                    try {
                        com.google.gson.JsonObject lineObject =
                                MCP_RESPONSE_GSON.fromJson(jsonLine.trim(), com.google.gson.JsonObject.class);
                        if (lineObject != null) {
                            if (lineObject.has("result")) {
                                resultObject = lineObject.getAsJsonObject("result");
                                log.debug("Extracted result object from NDJSON line");
                                break;
                            } else if (lineObject.has("data") && lineObject.getAsJsonObject("data").has("result")) {
                                resultObject = lineObject.getAsJsonObject("data").getAsJsonObject("result");
                                log.debug("Extracted result object from NDJSON data.result");
                                break;
                            }
                        }
                    } catch (JsonSyntaxException e) {
                        // Skip invalid JSON lines
                        if (log.isDebugEnabled()) {
                            log.debug("Skipping invalid JSON line: " + jsonLine);
                        }
                    }
                }
            }
            // Extract isError from result object
            if (resultObject != null) {
                boolean isError = false;
                if (resultObject.has("isError")) {
                    isError = resultObject.get("isError").getAsBoolean();
                }
                messageContext.setProperty(MCP_RESULT_IS_ERROR, isError);
            } else {
                log.debug("MCP response result object: " + (resultObject != null ? resultObject.toString() : "null") +
                        ", does not contain isError field");
            }
        } catch (JsonSyntaxException e) {
            log.warn("Failed to parse MCP response JSON: " + e.getMessage(), e);
        } catch (Exception e) {
            log.warn("Unexpected error while parsing MCP response: " + e.getMessage(), e);
        }
    }
}
