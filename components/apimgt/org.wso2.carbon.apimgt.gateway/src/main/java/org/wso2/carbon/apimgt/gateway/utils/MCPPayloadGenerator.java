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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.apache.commons.lang3.StringUtils;
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
        return getInitializeResponse(id, serverName, serverVersion, serverDescription, toolListChangeNotified,
                APIConstants.MCP.PROTOCOL_VERSION_2025_JUNE);
    }

    public static String getInitializeResponse(Object id, String serverName, String serverVersion,
                                               String serverDescription, boolean toolListChangeNotified,
                                               String protocolVersion) {
        McpResponse<InitializeResult> initializeResponse = new McpResponse<>(id);
        InitializeResult result = new InitializeResult();
        String negotiatedVersion = APIConstants.MCP.isSupportedProtocolVersion(protocolVersion)
                ? protocolVersion : APIConstants.MCP.PROTOCOL_VERSION_2025_JUNE;
        result.setProtocolVersion(negotiatedVersion);

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

    /**
     * Generates a {@code server/discover} response for MCP 2.0 clients.
     */
    public static String getServerDiscoverResponse(Object id, String serverName, String serverVersion,
                                                   String serverDescription, boolean toolListChangeNotified) {
        return getServerDiscoverResponse(id, serverName, serverVersion, serverDescription, toolListChangeNotified,
                APIConstants.MCP.PROTOCOL_VERSION_2026_JULY);
    }

    /**
     * Generates a {@code server/discover} response with an explicit protocol version.
     */
    public static String getServerDiscoverResponse(Object id, String serverName, String serverVersion,
                                                   String serverDescription, boolean toolListChangeNotified,
                                                   String protocolVersion) {
        JsonObject result = new JsonObject();
        String version = StringUtils.isNotEmpty(protocolVersion)
                ? protocolVersion : APIConstants.MCP.PROTOCOL_VERSION_2026_JULY;
        result.addProperty(APIConstants.MCP.PROTOCOL_VERSION_KEY, version);

        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty(APIConstants.MCP.CLIENT_NAME_KEY, serverName);
        serverInfo.addProperty(APIConstants.MCP.CLIENT_VERSION_KEY, serverVersion);
        if (StringUtils.isNotEmpty(serverDescription)) {
            serverInfo.addProperty("description", serverDescription);
        }
        result.add(APIConstants.MCP.SERVER_INFO_KEY, serverInfo);

        InitializeResult.Capabilities capabilities = getCapabilities(toolListChangeNotified);
        result.add(APIConstants.MCP.CAPABILITIES_KEY, gson.toJsonTree(capabilities));

        McpResponse<JsonObject> response = new McpResponse<>(id);
        response.setResult(result);
        return gson.toJson(response);
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
                        JsonElement parsed = JsonParser.parseString(schema);
                        if (parsed == null || !parsed.isJsonObject()) {
                            log.warn("MCP tools/list serialize: schema is not a JSON object for tool=" + toolName
                                    + ", rawSchema=" + schema);
                        } else {
                            JsonObject schemaObject = parsed.getAsJsonObject();
                            // Preserve $defs / $ref / title / additionalProperties. The old JsonSchema
                            // POJO only kept type/properties/required, which dropped $defs and left
                            // dangling refs such as {"caseInput":{"$ref":"#/$defs/CaseInputModel"}}.
                            if (!isThirdParty) {
                                schemaObject = sanitizeInputSchema(schemaObject);
                            }
                            tool.setInputSchema(inlineSingleDefsRef(schemaObject));
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

    private static JsonObject sanitizeInputSchema(JsonObject inputSchema) {
        if (inputSchema == null) {
            JsonObject emptySchema = new JsonObject();
            emptySchema.addProperty("type", "object");
            emptySchema.add("properties", new JsonObject());
            return emptySchema;
        }
        if (inputSchema.has("properties") && inputSchema.get("properties").isJsonObject()) {
            JsonObject properties = inputSchema.getAsJsonObject("properties");
            properties.remove("contentType");
            JsonObject sanitizedProperties = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : properties.entrySet()) {
                String key = entry.getKey();
                if ("requestBody".equalsIgnoreCase(key)) {
                    sanitizedProperties.add(key, entry.getValue());
                } else {
                    sanitizedProperties.add(stripSchemaParamPrefix(key), entry.getValue());
                }
            }
            inputSchema.add("properties", sanitizedProperties);
        }
        if (inputSchema.has("required") && inputSchema.get("required").isJsonArray()) {
            JsonArray required = inputSchema.getAsJsonArray("required");
            JsonArray sanitizedRequired = new JsonArray();
            for (JsonElement requiredEl : required) {
                if (!requiredEl.isJsonPrimitive() || !requiredEl.getAsJsonPrimitive().isString()) {
                    sanitizedRequired.add(requiredEl);
                    continue;
                }
                String requiredProperty = requiredEl.getAsString();
                if ("requestBody".equalsIgnoreCase(requiredProperty)) {
                    sanitizedRequired.add(requiredProperty);
                } else {
                    sanitizedRequired.add(stripSchemaParamPrefix(requiredProperty));
                }
            }
            inputSchema.add("required", sanitizedRequired);
        }
        return inputSchema;
    }

    /**
     * If the only property is a {@code $ref} into {@code $defs}, inline that definition so clients
     * see real fields (title, caseGrade, ...) instead of a dangling nested wrapper.
     */
    private static JsonObject inlineSingleDefsRef(JsonObject inputSchema) {
        if (inputSchema == null || !inputSchema.has("properties") || !inputSchema.get("properties").isJsonObject()) {
            return inputSchema;
        }
        JsonObject properties = inputSchema.getAsJsonObject("properties");
        if (properties.size() != 1 || !inputSchema.has("$defs") || !inputSchema.get("$defs").isJsonObject()) {
            return inputSchema;
        }
        Map.Entry<String, JsonElement> onlyProp = properties.entrySet().iterator().next();
        if (onlyProp.getValue() == null || !onlyProp.getValue().isJsonObject()) {
            return inputSchema;
        }
        JsonObject propSchema = onlyProp.getValue().getAsJsonObject();
        if (!propSchema.has("$ref") || !propSchema.get("$ref").isJsonPrimitive()) {
            return inputSchema;
        }
        String ref = propSchema.get("$ref").getAsString();
        String defsPrefix = "#/$defs/";
        if (ref == null || !ref.startsWith(defsPrefix)) {
            return inputSchema;
        }
        String defName = ref.substring(defsPrefix.length());
        JsonObject defs = inputSchema.getAsJsonObject("$defs");
        if (!defs.has(defName) || !defs.get(defName).isJsonObject()) {
            return inputSchema;
        }
        JsonObject inlined = defs.getAsJsonObject(defName).deepCopy();
        if (!inlined.has("title") && inputSchema.has("title")) {
            inlined.add("title", inputSchema.get("title"));
        }
        JsonObject remainingDefs = defs.deepCopy();
        remainingDefs.remove(defName);
        if (remainingDefs.size() > 0) {
            inlined.add("$defs", remainingDefs);
        }
        return inlined;
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

    /**
     * Generates a stub {@code resources/read} response. Resource content is not backed yet,
     * so the result contains an empty {@code contents} array.
     *
     * @param id JSON-RPC request id
     * @return JSON-RPC success payload
     */
    public static String generateResourceReadResponse(Object id) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        if (id instanceof Number) {
            response.addProperty("id", (Number) id);
        } else {
            response.addProperty("id", String.valueOf(id));
        }
        JsonObject result = new JsonObject();
        result.add("contents", new JsonArray());
        response.add("result", result);
        return gson.toJson(response);
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
