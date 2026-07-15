/*
 * Copyright (c) 2025, WSO2 LLC (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 LLC licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * you may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.apimgt.impl;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.Header;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.json.JSONObject;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.ExceptionCodes;
import org.wso2.carbon.apimgt.api.FileSizeLimitExceededException;
import org.wso2.carbon.apimgt.api.SizeLimitedInputStream;
import org.wso2.carbon.apimgt.impl.internal.ServiceReferenceHolder;
import org.wso2.carbon.apimgt.impl.utils.APIUtil;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Initializes an MCP server and fetches the available tools via JSON-RPC.
 */
public class MCPInitializerAndToolFetcher {

    private static final Log log = LogFactory.getLog(MCPInitializerAndToolFetcher.class);

    private final String mcpServerUrl;
    private final String authHeaderName;
    private final String authHeaderValue;
    private final boolean secure;

    public MCPInitializerAndToolFetcher(String mcpServerURL, String header, String value, boolean isSecure) {

        this.mcpServerUrl = mcpServerURL;
        this.authHeaderName = header;
        this.authHeaderValue = value;
        this.secure = isSecure;
    }

    /**
     * Initializes the server and returns its tool list.
     *
     * @return non-null array of tools (empty if none)
     * @throws APIManagementException on URL, I/O, or protocol errors
     */
    public JSONObject initializeAndFetchTools() throws APIManagementException {

        URL endpoint;
        try {
            endpoint = new URL(mcpServerUrl);
        } catch (MalformedURLException e) {
            throw new APIManagementException("Invalid MCP server URL: " + mcpServerUrl, e);
        }

        try (CloseableHttpClient httpClient =
                     (CloseableHttpClient) APIUtil.getHttpClient(endpoint.getPort(), endpoint.getProtocol())) {

            String resolvedEndpoint = resolveMcpEndpoint(mcpServerUrl);
            if (log.isDebugEnabled()) {
                log.debug("Resolved MCP endpoint: " + resolvedEndpoint + " (from: " + mcpServerUrl + ")");
            }

            // 1) initialize
            JSONObject initializePayload = buildInitializePayload();
            JSONObject initializeResponse =
                    sendJsonRpcRequest(httpClient, resolvedEndpoint, initializePayload, null, false);
            JSONObject initializeResult =
                    parseJsonRpcResult(initializeResponse.getString(APIConstants.MCP.BODY_KEY));

            if (initializeResult == null) {
                throw new APIManagementException("Failed to initialize MCP server: result is null");
            }

            String sessionId = initializeResponse.optString(APIConstants.MCP.SESSION_ID_KEY, null);
            if (log.isDebugEnabled()) {
                log.debug("MCP initialization succeeded; sessionId=" + sessionId);
            }

            // 2) notifications/initialized (required by MCP lifecycle before normal operations)
            JSONObject initializedNotification = buildInitializedNotificationPayload();
            sendJsonRpcRequest(httpClient, resolvedEndpoint, initializedNotification, sessionId, true);

            // 3) tools/list
            JSONObject toolsPayload = buildToolsListPayload();
            JSONObject toolsResponse =
                    sendJsonRpcRequest(httpClient, resolvedEndpoint, toolsPayload, sessionId, false);

            return parseJsonRpcResult(toolsResponse.getString(APIConstants.MCP.BODY_KEY));
        } catch (APIManagementException e) {
            throw e;
        } catch (Exception e) {
            throw new APIManagementException("Error during MCP interaction: " + e.getMessage(), e);
        }
    }

    /**
     * Resolves the MCP JSON-RPC endpoint.
     * Accepts either a base URL ({@code https://host}) or a full endpoint ({@code https://host/mcp})
     * so {@code /mcp} is not appended twice when the caller already includes it.
     */
    private String resolveMcpEndpoint(String targetUrl) {

        String trimmed = StringUtils.removeEnd(StringUtils.trimToEmpty(targetUrl), "/");
        if (trimmed.toLowerCase(Locale.ROOT).endsWith(APIConstants.MCP.MCP_RESOURCES_MCP)) {
            return trimmed;
        }
        return trimmed + APIConstants.MCP.MCP_RESOURCES_MCP;
    }

    /**
     * Builds the JSON-RPC initialize payload.
     */
    private JSONObject buildInitializePayload() {

        JSONObject payload = new JSONObject();
        payload.put(APIConstants.MCP.RpcConstants.JSON_RPC, APIConstants.MCP.RpcConstants.JSON_RPC_VERSION);
        payload.put(APIConstants.MCP.RpcConstants.ID, 1);
        payload.put(APIConstants.MCP.RpcConstants.METHOD, APIConstants.MCP.METHOD_INITIALIZE);

        JSONObject params = new JSONObject();
        params.put(APIConstants.MCP.PROTOCOL_VERSION_KEY, APIConstants.MCP.PROTOCOL_VERSION_2025_JUNE);

        JSONObject capabilities = new JSONObject();
        JSONObject roots = new JSONObject().put(APIConstants.MCP.LIST_CHANGED_KEY, true);
        capabilities.put(APIConstants.MCP.ROOTS_KEY, roots);
        capabilities.put(APIConstants.MCP.SAMPLING_KEY, new JSONObject());
        params.put(APIConstants.MCP.CAPABILITIES_KEY, capabilities);

        JSONObject clientInfo = new JSONObject();
        clientInfo.put(APIConstants.MCP.CLIENT_NAME_KEY, APIConstants.MCP.CLIENT_NAME);
        clientInfo.put(APIConstants.MCP.CLIENT_VERSION_KEY, APIConstants.MCP.CLIENT_VERSION);
        params.put(APIConstants.MCP.CLIENT_INFO_KEY, clientInfo);

        payload.put(APIConstants.MCP.PARAMS_KEY, params);
        return payload;
    }

    /**
     * Builds the JSON-RPC {@code notifications/initialized} payload.
     */
    private JSONObject buildInitializedNotificationPayload() {

        JSONObject payload = new JSONObject();
        payload.put(APIConstants.MCP.RpcConstants.JSON_RPC, APIConstants.MCP.RpcConstants.JSON_RPC_VERSION);
        payload.put(APIConstants.MCP.RpcConstants.METHOD, APIConstants.MCP.METHOD_NOTIFICATION_INITIALIZED);
        return payload;
    }

    /**
     * Builds the JSON-RPC tools/list payload.
     */
    private JSONObject buildToolsListPayload() {

        JSONObject payload = new JSONObject();
        payload.put(APIConstants.MCP.RpcConstants.JSON_RPC, APIConstants.MCP.RpcConstants.JSON_RPC_VERSION);
        payload.put(APIConstants.MCP.RpcConstants.ID, 2);
        payload.put(APIConstants.MCP.RpcConstants.METHOD, APIConstants.MCP.METHOD_TOOL_LIST);
        payload.put(APIConstants.MCP.PARAMS_KEY, new JSONObject());
        return payload;
    }

    /**
     * Sends a JSON-RPC request/notification; returns wrapper with raw body and optional session id.
     *
     * @param notification if true, empty/202 responses are accepted without requiring a JSON-RPC result
     */
    private JSONObject sendJsonRpcRequest(CloseableHttpClient httpClient, String targetUrl, JSONObject jsonBody,
                                          String sessionId, boolean notification) throws Exception {

        HttpPost request = new HttpPost(targetUrl);
        request.setHeader(APIConstants.MCP.HEADER_CONTENT_TYPE,
                ContentType.APPLICATION_JSON.withCharset(StandardCharsets.UTF_8).toString());
        request.setHeader(APIConstants.MCP.HEADER_ACCEPT, APIConstants.MCP.ACCEPT_JSON_AND_SSE);
        // Required on all HTTP requests after initialize (also harmless on the initialize call itself).
        request.setHeader(APIConstants.MCP.MCP_PROTOCOL_VERSION_HEADER, APIConstants.MCP.PROTOCOL_VERSION_2025_JUNE);

        if (secure && authHeaderName != null && !authHeaderName.isEmpty()) {
            request.setHeader(authHeaderName, authHeaderValue == null ? StringUtils.EMPTY : authHeaderValue);
        }
        if (sessionId != null && !sessionId.isEmpty()) {
            request.setHeader(APIConstants.MCP.HEADER_MCP_SESSION_ID, sessionId);
        }

        request.setEntity(new StringEntity(jsonBody.toString(), StandardCharsets.UTF_8));

        String maxFileSizeStr = ServiceReferenceHolder.getInstance().getAPIManagerConfigurationService()
                .getAPIManagerConfiguration()
                .getFirstProperty(org.wso2.carbon.apimgt.api.APIConstants.API_PUBLISHER_IMPORT_MCP_FILE_SIZE_LIMIT);
        if (maxFileSizeStr == null || maxFileSizeStr.trim().isEmpty()) {
            maxFileSizeStr = org.wso2.carbon.apimgt.api.APIConstants.API_PUBLISHER_IMPORT_MCP_FILE_SIZE_LIMIT_DEFAULT_MB;
        }
        final long maxBytes = Long.parseLong(maxFileSizeStr) * 1024L * 1024L;

        try (CloseableHttpResponse response = httpClient.execute(request)) {
            final int status = response.getStatusLine() != null ? response.getStatusLine().getStatusCode() : 0;
            if (status < 200 || status >= 300) {
                String reason = response.getStatusLine() != null ?
                        response.getStatusLine().getReasonPhrase() : "Unknown";
                String bodySnippet = StringUtils.EMPTY;
                if (response.getEntity() != null) {
                    try (InputStream rawStream = response.getEntity().getContent();
                            SizeLimitedInputStream limitedStream = new SizeLimitedInputStream(rawStream, maxBytes)) {
                        bodySnippet = IOUtils.toString(limitedStream, StandardCharsets.UTF_8);
                    } catch (FileSizeLimitExceededException e) {
                        log.error(
                                "MCP error response body exceeds the maximum allowed size of " + maxFileSizeStr + " MB. Truncating body snippet.",
                                e);
                        bodySnippet = "[Response body too large to display]";
                    } catch (IOException e) {
                        log.error("Failed to read MCP error response body.", e);
                    }
                }
                throw new APIManagementException("MCP request failed: HTTP " + status + " " + reason + " Body: "
                        + bodySnippet);
            }
            String body = StringUtils.EMPTY;
            if (response.getEntity() != null) {
                try (InputStream rawStream = response.getEntity().getContent();
                        SizeLimitedInputStream limitedStream = new SizeLimitedInputStream(rawStream, maxBytes)) {
                    body = IOUtils.toString(limitedStream, StandardCharsets.UTF_8);
                } catch (FileSizeLimitExceededException e) {
                    throw new APIManagementException(
                            "MCP response body exceeds the maximum allowed size of " + maxFileSizeStr + " MB.",
                            ExceptionCodes.FILE_TOO_LARGE);
                } catch (IOException e) {
                    throw new APIManagementException("Failed to read MCP response body.", e);
                }
            }
            // Notifications (e.g. notifications/initialized) may return 202 with an empty body.
            if (notification && StringUtils.isBlank(body)) {
                body = StringUtils.EMPTY;
            }
            Header sessionHeader = response.getFirstHeader(APIConstants.MCP.HEADER_MCP_SESSION_ID);
            String returnedSessionId = sessionHeader != null ? sessionHeader.getValue() : null;

            JSONObject result = new JSONObject();
            result.put(APIConstants.MCP.BODY_KEY, body);
            if (returnedSessionId != null) {
                result.put(APIConstants.MCP.SESSION_ID_KEY, returnedSessionId);
            }
            return result;
        }
    }

    /**
     * Parses a JSON-RPC response and returns the {@code result} object.
     *
     * @throws APIManagementException if the response contains an error or is invalid
     */
    private JSONObject parseJsonRpcResult(String responseText) throws APIManagementException {

        try {
            String candidate = extractJsonRpcResponseCandidate(responseText);
            JSONObject json = new JSONObject(candidate);

            if (json.has(APIConstants.MCP.RESULT_KEY)) {
                return json.getJSONObject(APIConstants.MCP.RESULT_KEY);
            }
            if (json.has(APIConstants.MCP.ERROR_KEY)) {
                Object errorObj = json.get(APIConstants.MCP.ERROR_KEY);
                throw new APIManagementException("MCP server returned error: " + String.valueOf(errorObj));
            }
            String snippet = StringUtils.abbreviate(StringUtils.trimToEmpty(responseText), 500);
            throw new APIManagementException(
                    "Unexpected JSON-RPC format: missing 'result'/'error'. Response snippet: " + snippet);
        } catch (APIManagementException e) {
            throw e;
        } catch (Exception e) {
            String snippet = StringUtils.abbreviate(StringUtils.trimToEmpty(responseText), 500);
            throw new APIManagementException(
                    "Failed to parse JSON-RPC response: " + e.getMessage() + ". Response snippet: " + snippet, e);
        }
    }

    /**
     * Extracts a JSON-RPC response candidate from plain JSON or SSE-style payloads.
     * Prefers an object that contains {@code result} or {@code error}, because Streamable HTTP
     * servers may emit notifications before/after the actual JSON-RPC response.
     */
    private String extractJsonRpcResponseCandidate(String responseText) {

        List<String> candidates = collectJsonCandidates(responseText);
        if (candidates.isEmpty()) {
            return "{}";
        }

        String fallback = candidates.get(candidates.size() - 1);
        for (int i = candidates.size() - 1; i >= 0; i--) {
            String candidate = candidates.get(i);
            try {
                JSONObject json = new JSONObject(candidate);
                if (json.has(APIConstants.MCP.RESULT_KEY) || json.has(APIConstants.MCP.ERROR_KEY)) {
                    return candidate;
                }
            } catch (Exception e) {
                // Keep scanning other candidates
                if (log.isDebugEnabled()) {
                    log.debug("Skipping non-JSON SSE/JSON-RPC candidate: " + e.getMessage());
                }
            }
        }
        return fallback;
    }

    /**
     * Collects possible JSON payloads from a plain JSON body or SSE {@code data:} lines.
     */
    private List<String> collectJsonCandidates(String responseText) {

        List<String> candidates = new ArrayList<>();
        if (responseText == null || responseText.isEmpty()) {
            return candidates;
        }

        String[] lines = responseText.split("\n");
        boolean sawSseData = false;
        StringBuilder multiLineData = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith(APIConstants.MCP.SSE_DATA_PREFIX)) {
                sawSseData = true;
                String data = trimmed.substring(APIConstants.MCP.SSE_DATA_PREFIX.length()).trim();
                if (!data.isEmpty()) {
                    // SSE allows multi-line data; concatenate contiguous data: lines into one event chunk.
                    if (multiLineData.length() > 0) {
                        multiLineData.append('\n');
                    }
                    multiLineData.append(data);
                }
            } else if (sawSseData && trimmed.isEmpty() && multiLineData.length() > 0) {
                // Blank line ends an SSE event.
                candidates.add(multiLineData.toString());
                multiLineData.setLength(0);
            } else if (sawSseData && !trimmed.isEmpty() && !trimmed.startsWith("event:")
                    && !trimmed.startsWith("id:") && !trimmed.startsWith("retry:")) {
                // Non-data SSE field — flush any pending data block first.
                if (multiLineData.length() > 0) {
                    candidates.add(multiLineData.toString());
                    multiLineData.setLength(0);
                }
            }
        }
        if (multiLineData.length() > 0) {
            candidates.add(multiLineData.toString());
        }
        if (!sawSseData) {
            candidates.add(responseText.trim());
        }
        return candidates;
    }

    /**
     * Extract the tools array from a tools/list response.
     *
     * @param toolsJson Raw JSON returned by tools/list
     * @return Non-empty tools array
     * @throws APIManagementException If the JSON is missing or invalid
     */
    public static org.json.JSONArray extractToolsArray(org.json.JSONObject toolsJson)
            throws APIManagementException {

        if (toolsJson == null) {
            throw new APIManagementException("No response received from MCP server (tools/list).",
                    ExceptionCodes.MCP_SERVER_TOOL_LIST_GENERATION_FAILED);
        }
        if (!toolsJson.has(APIConstants.MCP.TOOLS_KEY)) {
            throw new APIManagementException("Missing 'tools' field in tools/list response.",
                    ExceptionCodes.MCP_SERVER_TOOL_LIST_GENERATION_FAILED);
        }

        org.json.JSONArray toolsArray = toolsJson.optJSONArray(APIConstants.MCP.TOOLS_KEY);
        if (toolsArray == null) {
            throw new APIManagementException("Unexpected 'tools' format: expected an array.",
                    ExceptionCodes.MCP_SERVER_TOOL_LIST_GENERATION_FAILED);
        }
        if (toolsArray.length() == 0) {
            if (log.isDebugEnabled()) {
                log.debug("Retrieved 0 tool(s) from MCP server.");
            }
            throw new APIManagementException("MCP server returned an empty tool list.",
                    ExceptionCodes.MCP_SERVER_TOOL_LIST_GENERATION_FAILED);
        }

        return toolsArray;
    }
}
