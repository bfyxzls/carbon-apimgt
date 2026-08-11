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
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.apimgt.api.model.subscription.URLMapping;
import org.wso2.carbon.apimgt.gateway.mcp.response.InitializeResult;
import org.wso2.carbon.apimgt.gateway.mcp.response.McpError;
import org.wso2.carbon.apimgt.gateway.mcp.response.McpErrorResponse;
import org.wso2.carbon.apimgt.gateway.mcp.response.McpResponse;
import org.wso2.carbon.apimgt.gateway.mcp.response.ToolCallResult;
import org.wso2.carbon.apimgt.gateway.mcp.response.ToolListResult;
import org.wso2.carbon.apimgt.impl.APIConstants;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MCPPayloadGenerator {

    private static final Log log = LogFactory.getLog(MCPPayloadGenerator.class);

    private static final Gson gson = new GsonBuilder().serializeNulls().setPrettyPrinting().create();

    private static final String[] SCHEMA_PARAM_PREFIXES = {"query_", "header_", "path_"};

    public static String getErrorResponse(Object id, int code, String message, Object data) {
        McpError error = new McpError(code, message, data);
        McpErrorResponse errorResponse = new McpErrorResponse(id, error);
        return gson.toJson(errorResponse);
    }

    public static String getErrorResponse(int code, String message, Object data) {
        McpError error = new McpError(code, message, data);
        McpErrorResponse errorResponse = new McpErrorResponse(null, error);
        return gson.toJson(errorResponse);
    }

    public static String getInitializeResponse(Object id, String serverName, String serverVersion,
                                               String serverDescription, boolean toolListChangeNotified) {
        // Create the response object as specified in
        // https://modelcontextprotocol.io/specification/2025-03-26/basic/lifecycle#initialization
        McpResponse<InitializeResult> initializeResponse = new McpResponse<>(id);
        InitializeResult result = new InitializeResult();
        result.setProtocolVersion(APIConstants.MCP.PROTOCOL_VERSION_2025_JUNE);

        InitializeResult.ServerInfo serverInfo = new InitializeResult.ServerInfo();
        serverInfo.setName(serverName);
        serverInfo.setVersion(serverVersion);
        serverInfo.setDescription(serverDescription);
        result.setServerInfo(serverInfo);

        InitializeResult.Capabilities capabilities = getCapabilities(toolListChangeNotified);

        result.setCapabilities(capabilities);
        initializeResponse.setResult(result);

        return gson.toJson(initializeResponse);
    }

    private static InitializeResult.Capabilities getCapabilities(boolean toolListChangeNotified) {
        InitializeResult.Capabilities capabilities = new InitializeResult.Capabilities();
        InitializeResult.Capabilities.Tools tools = new InitializeResult.Capabilities.Tools();
        tools.setListChanged(toolListChangeNotified);
        capabilities.setTools(tools);
        return capabilities;
    }

    /**
     * Generates the error body for the initialize method when the requested protocol version is not supported.
     *
     * @param requestedVersion The requested protocol version
     * @return The error body as a JSON string
     */
    public static JsonObject getInitializeErrorBody(String requestedVersion) {
        JsonObject data = new JsonObject();
        data.addProperty(APIConstants.MCP.PROTOCOL_VERSION_REQUESTED, requestedVersion);
        JsonArray supportedVersions = new JsonArray();
        for (String version : APIConstants.MCP.SUPPORTED_PROTOCOL_VERSIONS) {
            supportedVersions.add(version);
        }
        data.add(APIConstants.MCP.PROTOCOL_VERSION_SUPPORTED, supportedVersions);
        return data;
    }

    //write a method to generate the tool list payload
    public static String generateToolListPayload(Object id, List<URLMapping> extendedOperations, boolean isThirdParty) {
        McpResponse<ToolListResult> toolListResponse = new McpResponse<>(id);
        ToolListResult toolListResult = new ToolListResult();
        List<ToolListResult.ToolInfo> toolInfoList = new ArrayList<>();
        int mappingCount = extendedOperations != null ? extendedOperations.size() : 0;

        if (extendedOperations != null) {
            for (URLMapping extendedOperation : extendedOperations) {
                if (extendedOperation == null) {
                    log.warn("MCP tools/list serialize: skipping null URLMapping");
                    continue;
                }
                String toolName = extendedOperation.getUrlPattern();
                String httpMethod = extendedOperation.getHttpMethod();
                ToolListResult.ToolInfo tool = new ToolListResult.ToolInfo();
                tool.setName(toolName);
                tool.setDescription(extendedOperation.getDescription());
                String schema = extendedOperation.getSchemaDefinition();
                if (schema != null) {
                    try {
                        ToolListResult.JsonSchema schemaObject =
                                gson.fromJson(schema, ToolListResult.JsonSchema.class);
                        if (schemaObject == null) {
                            log.warn("MCP tools/list serialize: Gson.fromJson returned null for tool=" + toolName
                                    + ", rawSchema=" + schema);
                        } else if (!isThirdParty) {
                            ToolListResult.JsonSchema sanitized = sanitizeInputSchema(schemaObject);
                            String afterSanitize = gson.toJson(sanitized);
                            tool.setInputSchema(sanitized);
                        } else {
                            // For third-party tools, we do not sanitize the input schema
                            tool.setInputSchema(schemaObject);
                        }
                    } catch (JsonParseException e) {
                        log.error("MCP tools/list serialize FAILED (JSON parse) for tool=" + toolName
                                + ", httpMethod=" + httpMethod
                                + ", rawSchemaDefinition=" + schema, e);
                        // Keep tool without inputSchema so remaining tools still return; failure is visible in logs.
                    } catch (RuntimeException e) {
                        log.error("MCP tools/list serialize FAILED (unexpected) for tool=" + toolName
                                + ", httpMethod=" + httpMethod
                                + ", rawSchemaDefinition=" + schema, e);
                    }
                }
                toolInfoList.add(tool);
            }
        }

        toolListResult.setTools(toolInfoList);
        toolListResponse.setResult(toolListResult);
        try {
            String payload = gson.toJson(toolListResponse);
            return payload;
        } catch (RuntimeException e) {
            log.error("MCP tools/list serialize FAILED while writing final JSON-RPC payload, toolCount="
                    + toolInfoList.size(), e);
            throw e;
        }
    }

    private static ToolListResult.JsonSchema sanitizeInputSchema(ToolListResult.JsonSchema inputSchema) {
        if (inputSchema == null) {
            // Return an empty object schema if the input schema is null
            ToolListResult.JsonSchema emptySchema = new ToolListResult.JsonSchema();
            emptySchema.setType("object");
            emptySchema.setProperties(new HashMap<>());
            return emptySchema;
        }
        inputSchema.removeProperty("contentType");

        // Only strip OpenAPI-derived location prefixes (query_/header_/path_).
        // Do NOT split on the first '_' — that corrupts legitimate MCP param names
        // like decision_date_start / case_type / courthouse_name.
        List<String> requiredProperties = inputSchema.getRequired();
        List<String> sanitizedRequiredProperties = new ArrayList<>();
        if (requiredProperties != null && !requiredProperties.isEmpty()) {
            for (String requiredProperty : requiredProperties) {
                if ("requestBody".equalsIgnoreCase(requiredProperty)) {
                    sanitizedRequiredProperties.add(requiredProperty);
                } else {
                    sanitizedRequiredProperties.add(stripSchemaParamPrefix(requiredProperty));
                }
            }
        }
        inputSchema.setRequired(sanitizedRequiredProperties);

        Map<String, Object> properties = inputSchema.getProperties();
        Map<String, Object> sanitizedProperties = new HashMap<>();
        if (properties != null && !properties.isEmpty()) {
            for (Map.Entry<String, Object> entry : properties.entrySet()) {
                String key = entry.getKey();
                if ("requestBody".equalsIgnoreCase(key)) {
                    sanitizedProperties.put("requestBody", entry.getValue());
                    continue;
                }
                sanitizedProperties.put(stripSchemaParamPrefix(key), entry.getValue());
            }
        }
        inputSchema.setProperties(sanitizedProperties);
        return inputSchema;
    }

    /**
     * Strips OpenAPI parameter location prefixes used when APIM maps REST ops to MCP tools.
     * Leaves other names (including those with underscores) unchanged.
     */
    private static String stripSchemaParamPrefix(String name) {
        if (name == null) {
            return null;
        }
        for (String prefix : SCHEMA_PARAM_PREFIXES) {
            if (name.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return name.substring(prefix.length());
            }
        }
        return name;
    }

    public static String generateMCPResponsePayload(Object id, boolean isError, String body) {
        McpResponse<ToolCallResult> mcpResponse = new McpResponse<>(id);
        ToolCallResult toolCallResult = new ToolCallResult();

        toolCallResult.setError(isError);
        List<ToolCallResult.ContentItem> contentItems = new ArrayList<>();
        ToolCallResult.ContentItem contentItem = new ToolCallResult.ContentItem();
        contentItem.setType("text");
        contentItem.setText(body);
        contentItems.add(contentItem);
        toolCallResult.setContent(contentItems);
        mcpResponse.setResult(toolCallResult);

        return gson.toJson(mcpResponse);
    }

    public static String generatePingResponse(Object id) {
        return generateEmptyResult(id);
    }

    public static String generateResourceListResponse(Object id) {
        // Resources are not supported at the moment
        return generateEmptyResult(id);
    }

    public static String generateResourceTemplateListResponse(Object id) {
        // Resource templates are not supported at the moment
        return generateEmptyResult(id);
    }

    public static String generatePromptListResponse(Object id) {
        // Prompts are not supported at the moment
        return generateEmptyResult(id);
    }

    private static String generateEmptyResult(Object id) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        if (id instanceof Number) {
            response.addProperty("id", (Number) id);
        } else {
            response.addProperty("id", String.valueOf(id));
        }
        response.add("result", new JsonObject());
        return gson.toJson(response);
    }
}
