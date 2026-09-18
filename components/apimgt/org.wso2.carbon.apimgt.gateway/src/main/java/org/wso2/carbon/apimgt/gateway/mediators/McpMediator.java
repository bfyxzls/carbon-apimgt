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

package org.wso2.carbon.apimgt.gateway.mediators;

import com.google.gson.Gson;
import org.apache.axis2.AxisFault;
import org.apache.axis2.Constants;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpStatus;
import org.apache.synapse.ManagedLifecycle;
import org.apache.synapse.MessageContext;
import org.apache.synapse.commons.json.JsonUtil;
import org.apache.synapse.core.SynapseEnvironment;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.mediators.AbstractMediator;
import org.apache.synapse.rest.RESTConstants;
import org.apache.synapse.transport.nhttp.NhttpConstants;
import org.apache.synapse.transport.passthru.util.RelayUtils;
import org.wso2.carbon.apimgt.gateway.handlers.Utils;
import org.wso2.carbon.apimgt.api.model.subscription.URLMapping;
import org.wso2.carbon.apimgt.gateway.APIMgtGatewayConstants;
import org.wso2.carbon.apimgt.gateway.dto.OAuthProtectedResourceDTO;
import org.wso2.carbon.apimgt.gateway.exception.McpException;
import org.wso2.carbon.apimgt.gateway.mcp.request.McpRequest;
import org.wso2.carbon.apimgt.gateway.internal.DataHolder;
import org.wso2.carbon.apimgt.gateway.mcp.request.McpRequestProcessor;
import org.wso2.carbon.apimgt.gateway.mcp.response.McpResponseDto;
import org.wso2.carbon.apimgt.gateway.utils.GatewayUtils;
import org.wso2.carbon.apimgt.gateway.utils.MCPPayloadGenerator;
import org.wso2.carbon.apimgt.gateway.utils.MCPProtocolNegotiator;
import org.wso2.carbon.apimgt.gateway.utils.MCPProtocolTranslator;
import org.wso2.carbon.apimgt.gateway.utils.MCPUtils;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.impl.dto.KeyManagerDto;
import org.wso2.carbon.apimgt.impl.factory.KeyManagerHolder;
import org.wso2.carbon.apimgt.impl.utils.APIUtil;
import org.wso2.carbon.apimgt.keymgt.model.entity.API;

import javax.xml.stream.XMLStreamException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Mediator for handling MCP (Model Context Protocol) requests and responses in the API Gateway.
 * <p>
 * {@code SERVER_PROXY} APIs are forwarded to the upstream MCP server without MCP 1.0/2.0 dialect
 * rewriting (upstream auto-negotiates). Other subtypes process initialize / tools locally.
 * </p>
 */
public class McpMediator extends AbstractMediator implements ManagedLifecycle {
    private static final Log log = LogFactory.getLog(McpMediator.class);
    private String mcpDirection = "";
    private static final String MCP_PROCESSED = "MCP_PROCESSED";
    private static final String IN_FLOW = "IN";
    private static final String OUT_FLOW = "OUT";
    private static final Pattern validHostHeaderPattern =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9.-]*(:\\d{1,5})?$");

    @Override
    public void init(SynapseEnvironment synapseEnvironment) {
        if (log.isDebugEnabled()) {
            log.debug("MCPMediator: Initialized.");
        }
    }

    @Override
    public void destroy() {

    }

    public String getMcpDirection() {
        return mcpDirection;
    }

    public void setMcpDirection(String mcpDirection) {
        this.mcpDirection = mcpDirection;
    }

    @Override
    public boolean mediate(MessageContext messageContext) {
        String path = Utils.getMcpRequestPath(messageContext);
        String httpMethod = (String) messageContext.getProperty(APIMgtGatewayConstants.HTTP_METHOD);
        org.apache.axis2.context.MessageContext axis2MessageContext =
                ((Axis2MessageContext) messageContext).getAxis2MessageContext();
        if (StringUtils.isEmpty(httpMethod)) {
            httpMethod = (String) axis2MessageContext.getProperty(Constants.Configuration.HTTP_METHOD);
        }
        API matchedAPI = GatewayUtils.getAPI(messageContext);
        if (matchedAPI == null) {
            log.error("No API matched for the request: " + path + " with method: " + httpMethod);
            return false;
        }
        String subType = matchedAPI.getSubtype();

        if (IN_FLOW.equals(mcpDirection)) {
            if (path != null && path.startsWith(APIMgtGatewayConstants.MCP_WELL_KNOWN_RESOURCE) &&
                    APIConstants.HTTP_GET.equals(httpMethod)) {
                return handleProtectedResourceMetadataResponse(messageContext, matchedAPI);
            }
            if (Utils.isMcpStreamableHttpGetRequest(path, httpMethod)) {
                // Defense in depth: InitHandler should already have rejected GET /mcp.
                MCPUtils.rejectStreamableHttpGetRequest(messageContext);
                return false;
            }
            if (StringUtils.equals(subType, APIConstants.API_SUBTYPE_SERVER_PROXY)) {
                McpRequest requestBody =
                        (McpRequest) messageContext.getProperty(APIMgtGatewayConstants.MCP_REQUEST_BODY);
                // Negotiate for analytics only. Upstream MCP servers auto-negotiate 1.0/2.0 —
                // do not rewrite headers/body or answer initialize/discover locally.
                if (messageContext.getProperty(APIMgtGatewayConstants.MCP_PROTOCOL_ERA_KEY) == null) {
                    MCPProtocolNegotiator.negotiateAndStore(messageContext, requestBody, matchedAPI);
                }
                log.debug("Proxying MCP request for server proxy API: " + matchedAPI.getName() + ":" +
                        matchedAPI.getVersion());
                return true;
            }
            if (path != null && path.startsWith(APIMgtGatewayConstants.MCP_RESOURCE) &&
                    httpMethod.equals(APIConstants.HTTP_POST)) {
                handleMcpRequest(messageContext, matchedAPI);
            }
        } else if (OUT_FLOW.equals(mcpDirection)) {
            if (StringUtils.equals(subType, APIConstants.API_SUBTYPE_SERVER_PROXY)) {
                // Transparent proxy: no dialect translation. Still filter tools/list to published catalog.
                applyServerProxyToolsListFilter(messageContext, matchedAPI);
                return true;
            }

            try {
                handleMcpResponse(messageContext);
            } catch (McpException e) {
                log.error("Error while handling MCP response", e);
                // Use HTTP 200 for JSON-RPC errors as per JSON-RPC 2.0 specification
                MCPUtils.handleMCPFailure(messageContext, new McpResponseDto(e.toJsonRpcErrorPayload(), 200, null));
                return false;
            }
        }
        return true;
    }

    private void applyServerProxyToolsListFilter(MessageContext messageContext, API matchedAPI) {
        String mcpMethod = (String) messageContext.getProperty(APIMgtGatewayConstants.MCP_METHOD);
        if (!StringUtils.equals(APIConstants.MCP.METHOD_TOOL_LIST, mcpMethod)) {
            return;
        }
        org.apache.axis2.context.MessageContext axis2MessageContext =
                ((Axis2MessageContext) messageContext).getAxis2MessageContext();
        try {
            if (!JsonUtil.hasAJsonPayload(axis2MessageContext)) {
                return;
            }
            String body = JsonUtil.jsonPayloadToString(axis2MessageContext);
            String filtered = MCPProtocolTranslator.filterToolsListToPublishedCatalog(body, matchedAPI);
            if (filtered != null && !filtered.equals(body)) {
                JsonUtil.removeJsonPayload(axis2MessageContext);
                JsonUtil.getNewJsonPayload(axis2MessageContext, filtered, true, true);
            }
        } catch (Exception e) {
            log.warn("Failed to filter SERVER_PROXY tools/list to published catalog", e);
        }
    }

    private void handleMcpRequest(MessageContext messageContext, API matchedAPI) {
        McpRequest requestBody = (McpRequest) messageContext.getProperty(APIMgtGatewayConstants.MCP_REQUEST_BODY);
        String mcpMethod = (String) messageContext.getProperty(APIMgtGatewayConstants.MCP_METHOD);
        org.apache.axis2.context.MessageContext axis2MessageContext =
                ((Axis2MessageContext) messageContext).getAxis2MessageContext();

        McpResponseDto mcpResponse = McpRequestProcessor.processRequest(messageContext, matchedAPI, requestBody);
        if (APIConstants.MCP.METHOD_INITIALIZE.equals(mcpMethod)
                || APIConstants.MCP.METHOD_SERVER_DISCOVER.equals(mcpMethod)
                || APIConstants.MCP.METHOD_TOOL_LIST.equals(mcpMethod)
                || APIConstants.MCP.METHOD_PING.equals(mcpMethod)
                || APIConstants.MCP.METHOD_RESOURCES_LIST.equals(mcpMethod)
                || APIConstants.MCP.METHOD_RESOURCES_READ.equals(mcpMethod)
                || APIConstants.MCP.METHOD_RESOURCE_TEMPLATE_LIST.equals(mcpMethod)
                || APIConstants.MCP.METHOD_PROMPTS_LIST.equals(mcpMethod)
                || APIConstants.MCP.METHOD_PROMPTS_GET.equals(mcpMethod)
                || (APIConstants.MCP.METHOD_TOOL_CALL.equals(mcpMethod) && mcpResponse != null)) {
            messageContext.setProperty(MCP_PROCESSED, "true");
            if (mcpResponse != null) {
                try {
                    JsonUtil.removeJsonPayload(axis2MessageContext);
                    JsonUtil.getNewJsonPayload(axis2MessageContext, mcpResponse.getResponse(), true, true);
                    axis2MessageContext.setProperty(Constants.Configuration.MESSAGE_TYPE, APIConstants.APPLICATION_JSON_MEDIA_TYPE);
                    axis2MessageContext.setProperty(Constants.Configuration.CONTENT_TYPE, APIConstants.APPLICATION_JSON_MEDIA_TYPE);
                    axis2MessageContext.setProperty(APIMgtGatewayConstants.HTTP_SC, mcpResponse.getStatusCode());
                    if (mcpResponse.getSessionId() != null) {
                        messageContext.setProperty(APIMgtGatewayConstants.MCP_SESSION_ID_KEY,
                                mcpResponse.getSessionId());
                    }
                } catch (AxisFault e) {
                    log.error("Error while generating mcp payload " + axis2MessageContext.getLogIDString(), e);
                }
            } else {
                // If no response is generated, set the HTTP status to 204 No Content
                axis2MessageContext.setProperty(APIMgtGatewayConstants.HTTP_SC, HttpStatus.SC_NO_CONTENT);
            }
        } else if (StringUtils.equals(mcpMethod, APIConstants.MCP.METHOD_NOTIFICATION_INITIALIZED)) {
            JsonUtil.removeJsonPayload(axis2MessageContext);
            messageContext.setProperty(MCP_PROCESSED, "true");
            axis2MessageContext.setProperty(APIConstants.NO_ENTITY_BODY, true);
            axis2MessageContext.setProperty(APIMgtGatewayConstants.HTTP_SC, HttpStatus.SC_ACCEPTED);
        }
    }

    private boolean handleProtectedResourceMetadataResponse(MessageContext messageContext, API matchedAPI) {
        messageContext.setProperty(MCP_PROCESSED, "true");
        return MCPUtils.writeOAuthProtectedResourceMetadataResponse(messageContext, matchedAPI,
                MCPUtils.getAllScopesFromApi(matchedAPI));
    }

    private void handleMcpResponse(MessageContext messageContext) throws McpException {
        String mcpMethod = (String) messageContext.getProperty(APIMgtGatewayConstants.MCP_METHOD);
        if (APIConstants.MCP.METHOD_TOOL_CALL.equals(mcpMethod)) {
            org.apache.axis2.context.MessageContext axis2MessageContext =
                    ((Axis2MessageContext) messageContext).getAxis2MessageContext();
            String contentType = (String) axis2MessageContext.getProperty(Constants.Configuration.CONTENT_TYPE);
            if (contentType != null && contentType.toLowerCase().contains(APIConstants.APPLICATION_JSON_MEDIA_TYPE)) {
                Object statusCodeObject = axis2MessageContext.getProperty(APIMgtGatewayConstants.HTTP_SC);
                int statusCode = 0;
                if (statusCodeObject instanceof String) {
                    String scString = ((String) statusCodeObject).trim();
                    if (StringUtils.isNumeric(scString)) {
                        statusCode = Integer.parseInt(scString);
                    } else {
                        log.warn("Skipping non-numeric HTTP status in axis2 context: " + scString);
                    }
                } else if (null != statusCodeObject) {
                    statusCode = (Integer) statusCodeObject;
                }
                Object id = messageContext.getProperty(APIConstants.MCP.RECEIVED_MCP_ID);

                try {
                    RelayUtils.buildMessage(axis2MessageContext);
                } catch (XMLStreamException | IOException  e) {
                    throw new McpException(APIConstants.MCP.RpcConstants.INTERNAL_ERROR_CODE, "Error while building " +
                            "message from the axis2 message context", e);
                }

                boolean isError = !isSuccessResponse(statusCode);
                String messageBody;
                if (JsonUtil.hasAJsonPayload(axis2MessageContext)) {
                    messageBody = JsonUtil.jsonPayloadToString(axis2MessageContext);
                } else {
                    messageBody = isError ? "Error occurred during tool call. HTTP Status Code: " + statusCode : "";
                }

                buildMCPResponse(messageContext, id, isError, messageBody);
            } else {
                throw new McpException(APIConstants.MCP.RpcConstants.INVALID_REQUEST_CODE,
                        APIConstants.MCP.RpcConstants.INVALID_REQUEST_MESSAGE,
                        "Unsupported content type in the response. Expected: application/json, Found: " + contentType);
            }
        }
    }

    private boolean isSuccessResponse(int statusCode) {
        return (statusCode >= 200 && statusCode < 300);
    }

    private void buildMCPResponse(MessageContext messageContext, Object id, boolean isError, String messageBody)
            throws McpException {
        org.apache.axis2.context.MessageContext axis2MessageContext = ((Axis2MessageContext) messageContext)
                .getAxis2MessageContext();
        try {
            String mcpResponse = MCPPayloadGenerator.generateMCPResponsePayload(id, isError, messageBody);
            JsonUtil.removeJsonPayload(axis2MessageContext);
            JsonUtil.getNewJsonPayload(axis2MessageContext, mcpResponse, true, true);
            axis2MessageContext.setProperty(Constants.Configuration.MESSAGE_TYPE,
                    APIConstants.APPLICATION_JSON_MEDIA_TYPE);
            axis2MessageContext.setProperty(Constants.Configuration.CONTENT_TYPE,
                    APIConstants.APPLICATION_JSON_MEDIA_TYPE);

            // for JSON-RPC compliance, set HTTP_SC to 200 for all MCP responses
            axis2MessageContext.setProperty(APIMgtGatewayConstants.HTTP_SC, 200);
            axis2MessageContext.removeProperty(APIConstants.NO_ENTITY_BODY);
        } catch (Exception e) {
            throw new McpException(APIConstants.MCP.RpcConstants.INTERNAL_ERROR_CODE, APIConstants.MCP.RpcConstants.
                    INTERNAL_ERROR_MESSAGE, e);
        }
    }

    /**
     * Returns a list of all applicable scopes by concatenating all scopes attached to each URLMapping of the API.
     * @param api The API entity
     * @return List of all scopes
     */
    public static List<String> getAllScopes(API api) {
        return MCPUtils.getAllScopesFromApi(api);
    }
}
