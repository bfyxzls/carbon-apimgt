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
import org.wso2.carbon.apimgt.gateway.mcp.McpException;
import org.wso2.carbon.apimgt.gateway.mcp.request.MCPRequestDeserializer;
import org.wso2.carbon.apimgt.gateway.mcp.request.McpRequest;
import org.wso2.carbon.apimgt.gateway.mcp.request.Params;
import org.wso2.carbon.apimgt.gateway.mcp.request.ParamsDeserializer;
import org.wso2.carbon.apimgt.gateway.mcp.response.McpResponseDto;
import org.wso2.carbon.apimgt.gateway.utils.GatewayUtils;
import org.wso2.carbon.apimgt.gateway.utils.MCPUtils;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.keymgt.model.entity.API;

import javax.xml.stream.XMLStreamException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.wso2.carbon.apimgt.gateway.handlers.streaming.sse.SseApiConstants.SSE_CONTENT_TYPE;
import static org.wso2.carbon.apimgt.gateway.utils.MCPUtils.HEADER_MCP_SESSION_ID;
import static org.wso2.carbon.apimgt.gateway.utils.MCPUtils.INVALID_REQUEST_CODE;
import static org.wso2.carbon.apimgt.gateway.utils.MCPUtils.INVALID_REQUEST_MESSAGE;
import static org.wso2.carbon.apimgt.gateway.utils.MCPUtils.METHOD_INITIALIZE;
import static org.wso2.carbon.apimgt.gateway.utils.MCPUtils.METHOD_NOTIFICATION_INITIALIZED;
import static org.wso2.carbon.apimgt.gateway.utils.MCPUtils.METHOD_NOT_FOUND_CODE;
import static org.wso2.carbon.apimgt.gateway.utils.MCPUtils.METHOD_NOT_FOUND_MESSAGE;
import static org.wso2.carbon.apimgt.gateway.utils.MCPUtils.METHOD_PING;
import static org.wso2.carbon.apimgt.gateway.utils.MCPUtils.METHOD_PROMPTS_LIST;
import static org.wso2.carbon.apimgt.gateway.utils.MCPUtils.METHOD_RESOURCES_LIST;
import static org.wso2.carbon.apimgt.gateway.utils.MCPUtils.METHOD_RESOURCE_TEMPLATE_LIST;
import static org.wso2.carbon.apimgt.gateway.utils.MCPUtils.METHOD_TOOL_CALL;
import static org.wso2.carbon.apimgt.gateway.utils.MCPUtils.METHOD_TOOL_LIST;
import static org.wso2.carbon.apimgt.impl.APIConstants.API_ELECTED_RESOURCE;
import static org.wso2.carbon.apimgt.impl.APIConstants.DigestAuthConstants.HTTP_METHOD;

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

    static ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void init(SynapseEnvironment synapseEnvironment) {
        log.info("Initializing MCP Init Handler instance");

    }

    @Override
    public void destroy() {

    }

    @Override
    public boolean handleRequest(MessageContext messageContext) {
        try {
            String path = (String) messageContext.getProperty(API_ELECTED_RESOURCE);
            String httpMethod = (String) messageContext.getProperty(HTTP_METHOD);
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
            } else if (StringUtils.startsWith(path, MCP_RESOURCE) &&
                    StringUtils.equals(APIConstants.HTTP_GET, httpMethod)) {
                // No JSON-RPC payload in GET requests to /mcp resource, hence hardcoding no auth to false
                messageContext.setProperty(MCP_NO_AUTH_REQUEST, false);
            } else {
                boolean isNoAuthMCPRequest = isNoAuthMCPRequest(buildMCPRequest(messageContext));
                messageContext.setProperty(MCP_NO_AUTH_REQUEST, isNoAuthMCPRequest);
            }
        } catch (McpException e) {
            log.error("MCP init failed: " + String.valueOf(e.getData()), e);
            MCPUtils.handleMCPFailure(messageContext, new McpResponseDto(e.getErrorMessage(), e.getErrorCode(), null));
            return false;
        }
        return true;
    }

    @Override
    public boolean handleResponse(MessageContext messageContext) {
        log.info("McpInitHandler handleResponse called");
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
     * Fixes Content-Type header for MCP responses.
     * <p>
     * PassThrough Transport combines TRANSPORT_HEADERS Content-Type with Axis2 Configuration.CONTENT_TYPE,
     * causing issues like: "text/event-stream,text/event-stream; charset=UTF-8"
     * <p>
     * This method ensures the Content-Type is set to only "text/event-stream" for MCP SSE responses
     * by removing it from transport headers completely - PassThrough will use only Axis2 properties.
     *
     * @param axis2MessageContext The Axis2 message context
     * @param headers             The transport headers map
     */
    private void synchronizeContentTypeHeaders(org.apache.axis2.context.MessageContext axis2MessageContext,
                                               Map headers) {
        Object transportContentType = headers.get(APIConstants.HEADER_CONTENT_TYPE);
        // Check if this is a SSE response
        boolean isSseResponse = transportContentType != null &&
                transportContentType.toString().contains("text/event-stream");

        if (isSseResponse) {
            // Remove Content-Type from transport headers completely to prevent duplication
            // PassThrough Transport will use only Axis2 properties
            headers.remove(APIConstants.HEADER_CONTENT_TYPE);
            headers.remove("content-type");  // Also try lowercase variant
            headers.remove("Content-type");  // Mixed case

            // Set Axis2 properties to exactly "text/event-stream" without any charset
            axis2MessageContext.setProperty(org.apache.axis2.Constants.Configuration.CONTENT_TYPE,
                    SSE_CONTENT_TYPE);
            axis2MessageContext.setProperty(org.apache.axis2.Constants.Configuration.MESSAGE_TYPE,
                    SSE_CONTENT_TYPE);

            // Remove CHARACTER_SET_ENCODING to prevent charset=UTF-8 from being appended
            axis2MessageContext.removeProperty(org.apache.axis2.Constants.Configuration.CHARACTER_SET_ENCODING);

            // Set this flag to tell PassThrough Transport not to determine content type automatically
            axis2MessageContext.setProperty("TRANSPORT_IN_CONTENT_TYPE", SSE_CONTENT_TYPE);
        }
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

                Gson gson = new GsonBuilder()
                        .registerTypeAdapter(McpRequest.class, new MCPRequestDeserializer())
                        .registerTypeAdapter(Params.class, new ParamsDeserializer())
                        .create();

                McpRequest request = gson.fromJson(messageBody, McpRequest.class);

                if (!MCPUtils.validateRequest(request)) {
                    throw new McpException(INVALID_REQUEST_CODE,
                            INVALID_REQUEST_MESSAGE, "Invalid Request");
                }

                method = request.getMethod();
                messageContext.setProperty(MCP_METHOD, method);
                // Use the original messageBody instead of re-serializing to avoid Gson/Jackson incompatibility
                messageContext.setProperty(MCP_REQUEST_BODY, messageBody);

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
            case METHOD_INITIALIZE:
            case METHOD_PING:
            case METHOD_NOTIFICATION_INITIALIZED:
            case METHOD_RESOURCES_LIST:
            case METHOD_RESOURCE_TEMPLATE_LIST:
            case METHOD_PROMPTS_LIST:
                return true;

            case METHOD_TOOL_LIST:
            case METHOD_TOOL_CALL:
                return false;

            case "NO_MCP":
                // Not an MCP request, skip MCP processing
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

            // Build message if not already built
            RelayUtils.buildMessage(axis2MessageContext);

            String responseBody = null;

            // First, try to get response body from JSON payload
            if (JsonUtil.hasAJsonPayload(axis2MessageContext)) {
                responseBody = JsonUtil.jsonPayloadToString(axis2MessageContext);
                if (log.isDebugEnabled()) {
                    log.debug("MCP response body retrieved from JSON payload");
                }
            } else {
                // Fallback: try to get response body from SOAPEnvelope
                SOAPEnvelope envelope = messageContext.getEnvelope();
                if (envelope != null && envelope.getBody() != null) {
                    OMElement firstElement = envelope.getBody().getFirstElement();
                    if (firstElement != null) {
                        try {
                            // Try to convert XML/OMElement to JSON string
                            responseBody = JsonUtil.toJsonString(firstElement).toString();
                            if (log.isDebugEnabled()) {
                                log.debug("MCP response body retrieved from SOAPEnvelope and converted to JSON");
                            }
                        } catch (AxisFault e) {
                            // If conversion fails, try to get text content directly
                            String textContent = firstElement.getText();
                            if (!StringUtils.isEmpty(textContent)) {
                                responseBody = textContent;
                                if (log.isDebugEnabled()) {
                                    log.debug("MCP response body retrieved from SOAPEnvelope as text content");
                                }
                            } else {
                                // Last resort: get the entire body as string
                                responseBody = firstElement.toString();
                                if (log.isDebugEnabled()) {
                                    log.debug("MCP response body retrieved from SOAPEnvelope as string");
                                }
                            }
                        }
                    }
                }

                if (responseBody == null) {
                    if (log.isDebugEnabled()) {
                        log.debug(
                                "MCP response body could not be retrieved from JSON payload or SOAPEnvelope, skipping isError extraction");
                    }
                    return;
                }
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

            // Parse JSON response using Gson
            Gson gson = new GsonBuilder().create();
            com.google.gson.JsonObject jsonObject = null;

            // Try to parse as JSON object first
            try {
                jsonObject = gson.fromJson(responseBody, com.google.gson.JsonObject.class);
            } catch (JsonSyntaxException e) {
                // If parsing fails, it might be a streamable HTTP format (multiple JSON lines)
                if (log.isDebugEnabled()) {
                    log.debug("Failed to parse as single JSON object, trying streamableHttp format");
                }
            }

            // Extract isError from result
            com.google.gson.JsonObject resultObject = null;

            // Handle SSE (Server-Sent Events) format response
            // Response format: {"text": "event: message\r\ndata: {...}\r\n\r\n"}
            if (jsonObject != null && jsonObject.has("text")) {
                String textContent = jsonObject.get("text").getAsString();
                if (log.isDebugEnabled()) {
                    log.debug("MCP response contains text field (SSE format), extracting data");
                }

                // Extract JSON from "data: {...}" line in SSE format
                String[] lines = textContent.split("\r\n");
                for (String line : lines) {
                    if (line.startsWith("data: ")) {
                        String dataJson = line.substring(6); // Remove "data: " prefix
                        try {
                            com.google.gson.JsonObject dataObject =
                                    gson.fromJson(dataJson, com.google.gson.JsonObject.class);
                            if (dataObject != null && dataObject.has("result")) {
                                resultObject = dataObject.getAsJsonObject("result");
                                if (log.isDebugEnabled()) {
                                    log.debug("Extracted result object from SSE data field");
                                }
                                break;
                            }
                        } catch (JsonSyntaxException e) {
                            log.warn("Failed to parse data field from SSE format: " + e.getMessage());
                        }
                    }
                }
            }
            // Handle streamableHttp format response
            // Response format: {"streamableHttp": {...}} or multiple JSON lines
            else if (jsonObject != null && jsonObject.has("streamableHttp")) {
                com.google.gson.JsonObject streamableHttpObject = jsonObject.getAsJsonObject("streamableHttp");
                if (log.isDebugEnabled()) {
                    log.debug("MCP response contains streamableHttp field, extracting result");
                }

                // Try to extract result from streamableHttp object
                if (streamableHttpObject != null && streamableHttpObject.has("result")) {
                    resultObject = streamableHttpObject.getAsJsonObject("result");
                } else if (streamableHttpObject != null && streamableHttpObject.has("data")) {
                    // Handle nested structure: streamableHttp.data.result
                    com.google.gson.JsonObject dataObject = streamableHttpObject.getAsJsonObject("data");
                    if (dataObject != null && dataObject.has("result")) {
                        resultObject = dataObject.getAsJsonObject("result");
                    }
                }
            }
            // Handle streamable HTTP format (multiple JSON lines - NDJSON format)
            // Each line is a separate JSON object
            else if (responseBody != null && responseBody.contains("\n")) {
                if (log.isDebugEnabled()) {
                    log.debug(
                            "MCP response appears to be in streamableHttp format (multiple lines), parsing each line");
                }

                // Split by newlines and try to parse each line as JSON
                String[] jsonLines = responseBody.split("\n");
                for (String jsonLine : jsonLines) {
                    if (StringUtils.isEmpty(jsonLine.trim())) {
                        continue;
                    }
                    try {
                        com.google.gson.JsonObject lineObject =
                                gson.fromJson(jsonLine.trim(), com.google.gson.JsonObject.class);
                        if (lineObject != null) {
                            // Check if this line contains result
                            if (lineObject.has("result")) {
                                resultObject = lineObject.getAsJsonObject("result");
                                if (log.isDebugEnabled()) {
                                    log.debug("Extracted result object from streamableHttp line");
                                }
                                break;
                            } else if (lineObject.has("data") && lineObject.getAsJsonObject("data").has("result")) {
                                resultObject = lineObject.getAsJsonObject("data").getAsJsonObject("result");
                                if (log.isDebugEnabled()) {
                                    log.debug("Extracted result object from streamableHttp data.result");
                                }
                                break;
                            }
                        }
                    } catch (JsonSyntaxException e) {
                        // Skip invalid JSON lines
                        if (log.isDebugEnabled()) {
                            log.debug("Skipping invalid JSON line in streamableHttp format: " + jsonLine);
                        }
                    }
                }
            }
            // Handle standard JSON-RPC format: {"jsonrpc": "2.0", "id": 2, "result": {...}}
            else if (jsonObject != null && jsonObject.has("result")) {
                resultObject = jsonObject.getAsJsonObject("result");
                if (log.isDebugEnabled()) {
                    log.debug("MCP response contains result field (standard JSON-RPC format)");
                }
            }
            // Handle nested data.result format: {"data": {"result": {...}}}
            else if (jsonObject != null && jsonObject.has("data")) {
                com.google.gson.JsonObject dataObject = jsonObject.getAsJsonObject("data");
                if (dataObject != null && dataObject.has("result")) {
                    resultObject = dataObject.getAsJsonObject("result");
                    if (log.isDebugEnabled()) {
                        log.debug("MCP response contains data.result field");
                    }
                }
            }

            // Extract isError from result object
            if (resultObject != null && resultObject.has("isError")) {
                boolean isError = resultObject.get("isError").getAsBoolean();
                messageContext.setProperty(MCP_RESULT_IS_ERROR, isError);
                if (log.isDebugEnabled()) {
                    log.debug("Extracted MCP result isError: " + isError);
                }
            } else {
                if (log.isDebugEnabled()) {
                    log.debug("MCP response does not contain isError field in result, skipping isError extraction");
                }
            }
        } catch (JsonSyntaxException e) {
            log.warn("Failed to parse MCP response JSON: " + e.getMessage(), e);
        } catch (IOException | XMLStreamException e) {
            log.warn("Failed to read MCP response body: " + e.getMessage(), e);
        } catch (Exception e) {
            log.warn("Unexpected error while parsing MCP response: " + e.getMessage(), e);
        }
    }
}
