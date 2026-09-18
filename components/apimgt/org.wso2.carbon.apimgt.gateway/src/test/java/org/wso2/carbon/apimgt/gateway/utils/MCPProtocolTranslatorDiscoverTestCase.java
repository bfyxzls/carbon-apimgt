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

import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.wso2.carbon.apimgt.gateway.APIMgtGatewayConstants;
import org.wso2.carbon.apimgt.gateway.mcp.request.McpRequest;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.keymgt.model.entity.API;

import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;

/**
 * Unit tests for {@link MCPProtocolTranslator} helpers (meta enrichment, tools/list filter,
 * and legacy southbound shaping utilities).
 * <p>
 * {@code SERVER_PROXY} traffic is a transparent proxy in {@code McpMediator} and no longer
 * invokes {@code prepareSouthboundRequest} / response translation; these cases remain to
 * cover the translator utilities themselves.
 * </p>
 */
public class MCPProtocolTranslatorDiscoverTestCase {

    @Test
    public void testModernSouthboundDiscoverIsProxiedWithRoutingHeaders() {
        Map<String, Object> headers = new HashMap<>();
        Axis2MessageContext messageContext = mockContext(headers);
        messageContext.setProperty(APIMgtGatewayConstants.MCP_PROTOCOL_ERA_KEY,
                APIConstants.MCP.PROTOCOL_ERA_MODERN);
        messageContext.setProperty(APIMgtGatewayConstants.MCP_BACKEND_PROTOCOL_ERA_KEY,
                APIConstants.MCP.PROTOCOL_ERA_MODERN);
        messageContext.setProperty(APIMgtGatewayConstants.MCP_BACKEND_PROTOCOL_VERSION_KEY,
                APIConstants.MCP.PROTOCOL_VERSION_2026_JULY);
        messageContext.setProperty(APIMgtGatewayConstants.MCP_METHOD,
                APIConstants.MCP.METHOD_SERVER_DISCOVER);

        McpRequest request = new McpRequest(1);
        request.setMethod(APIConstants.MCP.METHOD_SERVER_DISCOVER);
        API api = new API();

        Assert.assertTrue(MCPProtocolTranslator.prepareSouthboundRequest(messageContext, api, request));
        Assert.assertEquals(APIConstants.MCP.METHOD_SERVER_DISCOVER,
                headers.get(APIConstants.MCP.HEADER_MCP_METHOD));
        Assert.assertEquals(APIConstants.MCP.PROTOCOL_VERSION_2026_JULY,
                headers.get(APIConstants.MCP.MCP_PROTOCOL_VERSION_HEADER));
        Assert.assertFalse(headers.containsKey(APIConstants.MCP.HEADER_MCP_SESSION_ID));
    }

    @Test
    public void testHeaderDiscoverAlignsBodyMethodBeforeProxy() {
        Map<String, Object> headers = new HashMap<>();
        Axis2MessageContext messageContext = mockContext(headers);
        messageContext.setProperty(APIMgtGatewayConstants.MCP_PROTOCOL_ERA_KEY,
                APIConstants.MCP.PROTOCOL_ERA_MODERN);
        messageContext.setProperty(APIMgtGatewayConstants.MCP_BACKEND_PROTOCOL_ERA_KEY,
                APIConstants.MCP.PROTOCOL_ERA_MODERN);
        messageContext.setProperty(APIMgtGatewayConstants.MCP_BACKEND_PROTOCOL_VERSION_KEY,
                APIConstants.MCP.PROTOCOL_VERSION_2026_JULY);
        // Negotiated method from Mcp-Method header while body still says tools/list.
        messageContext.setProperty(APIMgtGatewayConstants.MCP_METHOD,
                APIConstants.MCP.METHOD_SERVER_DISCOVER);

        McpRequest request = new McpRequest(2);
        request.setMethod(APIConstants.MCP.METHOD_TOOL_LIST);

        Assert.assertTrue(MCPProtocolTranslator.prepareSouthboundRequest(messageContext, new API(), request));
        Assert.assertEquals(APIConstants.MCP.METHOD_SERVER_DISCOVER, request.getMethod());
        Assert.assertEquals(APIConstants.MCP.METHOD_SERVER_DISCOVER,
                headers.get(APIConstants.MCP.HEADER_MCP_METHOD));
    }

    @Test
    public void testModernToLegacyDiscoverStaysLocal() {
        Map<String, Object> headers = new HashMap<>();
        Axis2MessageContext messageContext = mockContext(headers);
        messageContext.setProperty(APIMgtGatewayConstants.MCP_PROTOCOL_ERA_KEY,
                APIConstants.MCP.PROTOCOL_ERA_MODERN);
        messageContext.setProperty(APIMgtGatewayConstants.MCP_BACKEND_PROTOCOL_ERA_KEY,
                APIConstants.MCP.PROTOCOL_ERA_LEGACY);
        messageContext.setProperty(APIMgtGatewayConstants.MCP_BACKEND_PROTOCOL_VERSION_KEY,
                APIConstants.MCP.PROTOCOL_VERSION_2025_JUNE);
        messageContext.setProperty(APIMgtGatewayConstants.MCP_METHOD,
                APIConstants.MCP.METHOD_SERVER_DISCOVER);

        McpRequest request = new McpRequest(3);
        request.setMethod(APIConstants.MCP.METHOD_SERVER_DISCOVER);

        Assert.assertFalse("Legacy southbound must not receive server/discover",
                MCPProtocolTranslator.prepareSouthboundRequest(messageContext, new API(), request));
    }

    @Test
    public void testLegacyClientToModernBackendDiscoverIsProxied() {
        Map<String, Object> headers = new HashMap<>();
        Axis2MessageContext messageContext = mockContext(headers);
        messageContext.setProperty(APIMgtGatewayConstants.MCP_PROTOCOL_ERA_KEY,
                APIConstants.MCP.PROTOCOL_ERA_LEGACY);
        messageContext.setProperty(APIMgtGatewayConstants.MCP_BACKEND_PROTOCOL_ERA_KEY,
                APIConstants.MCP.PROTOCOL_ERA_MODERN);
        messageContext.setProperty(APIMgtGatewayConstants.MCP_BACKEND_PROTOCOL_VERSION_KEY,
                APIConstants.MCP.PROTOCOL_VERSION_2026_JULY);
        messageContext.setProperty(APIMgtGatewayConstants.MCP_METHOD,
                APIConstants.MCP.METHOD_SERVER_DISCOVER);

        McpRequest request = new McpRequest(4);
        request.setMethod(APIConstants.MCP.METHOD_SERVER_DISCOVER);

        Assert.assertTrue(MCPProtocolTranslator.prepareSouthboundRequest(messageContext, new API(), request));
        Assert.assertEquals(APIConstants.MCP.METHOD_SERVER_DISCOVER,
                headers.get(APIConstants.MCP.HEADER_MCP_METHOD));
        Assert.assertEquals(APIConstants.MCP.PROTOCOL_VERSION_2026_JULY,
                headers.get(APIConstants.MCP.MCP_PROTOCOL_VERSION_HEADER));
    }

    @Test
    public void testEnrichMetaPreservesClientUiResourceUri() {
        JsonObject meta = new JsonObject();
        JsonObject ui = new JsonObject();
        ui.addProperty(APIConstants.MCP.META_UI_RESOURCE_URI_KEY, "ui://weather/view.html");
        meta.add(APIConstants.MCP.META_UI_KEY, ui);
        meta.addProperty(APIConstants.MCP.META_UI_RESOURCE_URI_LEGACY_KEY, "ui://weather/legacy.html");

        MCPProtocolTranslator.enrichRequiredMetaKeys(meta, APIConstants.MCP.PROTOCOL_VERSION_2026_JULY);

        Assert.assertEquals("ui://weather/view.html",
                meta.getAsJsonObject(APIConstants.MCP.META_UI_KEY)
                        .get(APIConstants.MCP.META_UI_RESOURCE_URI_KEY).getAsString());
        Assert.assertEquals("ui://weather/legacy.html",
                meta.get(APIConstants.MCP.META_UI_RESOURCE_URI_LEGACY_KEY).getAsString());
        Assert.assertEquals(APIConstants.MCP.PROTOCOL_VERSION_2026_JULY,
                meta.get(APIConstants.MCP.META_PROTOCOL_VERSION_KEY).getAsString());
        Assert.assertTrue(meta.has(APIConstants.MCP.META_CLIENT_CAPABILITIES_KEY));
        Assert.assertTrue(meta.has(APIConstants.MCP.META_CLIENT_INFO_KEY));
    }

    @Test
    public void testMergeClientMetaRestoresUiWhenMissingOnBody() {
        JsonObject meta = new JsonObject();
        Map<String, Object> clientMeta = new HashMap<>();
        Map<String, Object> ui = new HashMap<>();
        ui.put(APIConstants.MCP.META_UI_RESOURCE_URI_KEY, "ui://case/form");
        clientMeta.put(APIConstants.MCP.META_UI_KEY, ui);

        MCPProtocolTranslator.mergeClientMetaIntoJson(meta, clientMeta);

        Assert.assertEquals("ui://case/form",
                meta.getAsJsonObject(APIConstants.MCP.META_UI_KEY)
                        .get(APIConstants.MCP.META_UI_RESOURCE_URI_KEY).getAsString());
    }

    @Test
    public void testFilterToolsListPreservesUpstreamMetaUiResourceUri() {
        API api = new API();
        org.wso2.carbon.apimgt.api.model.subscription.URLMapping published =
                new org.wso2.carbon.apimgt.api.model.subscription.URLMapping();
        published.setUrlPattern("list_law_versions");
        published.setHttpMethod("TOOL");
        api.addResource(published);

        String upstream = "{"
                + "\"jsonrpc\":\"2.0\",\"id\":1,"
                + "\"result\":{\"tools\":["
                + "{\"name\":\"list_law_versions\",\"description\":\"versions\","
                + "\"inputSchema\":{\"type\":\"object\"},"
                + "\"_meta\":{\"ui\":{\"resourceUri\":\"ui://pkulaw-statute-history/law-versions.html\"},"
                + "\"openai/outputTemplate\":\"ui://pkulaw-statute-history/law-versions.html\"}},"
                + "{\"name\":\"secret_internal_tool\",\"description\":\"hidden\","
                + "\"inputSchema\":{\"type\":\"object\"}}"
                + "]}}";

        String filtered = MCPProtocolTranslator.filterToolsListToPublishedCatalog(upstream, api);
        JsonObject result = com.google.gson.JsonParser.parseString(filtered).getAsJsonObject()
                .getAsJsonObject("result");
        Assert.assertEquals(1, result.getAsJsonArray("tools").size());
        JsonObject tool = result.getAsJsonArray("tools").get(0).getAsJsonObject();
        Assert.assertEquals("list_law_versions", tool.get("name").getAsString());
        Assert.assertEquals("ui://pkulaw-statute-history/law-versions.html",
                tool.getAsJsonObject("_meta").getAsJsonObject("ui").get("resourceUri").getAsString());
        Assert.assertEquals("ui://pkulaw-statute-history/law-versions.html",
                tool.getAsJsonObject("_meta").get("openai/outputTemplate").getAsString());
    }

    private Axis2MessageContext mockContext(Map<String, Object> headers) {
        Axis2MessageContext messageContext = Mockito.mock(Axis2MessageContext.class);
        org.apache.axis2.context.MessageContext axis2 =
                Mockito.mock(org.apache.axis2.context.MessageContext.class);
        Mockito.when(messageContext.getAxis2MessageContext()).thenReturn(axis2);
        Mockito.when(axis2.getProperty(org.apache.axis2.context.MessageContext.TRANSPORT_HEADERS)).thenReturn(headers);

        Map<String, Object> props = new HashMap<>();
        Mockito.doAnswer(invocation -> {
            props.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(messageContext).setProperty(Mockito.anyString(), Mockito.any());
        Mockito.when(messageContext.getProperty(Mockito.anyString()))
                .thenAnswer(invocation -> props.get(invocation.getArgument(0)));
        return messageContext;
    }
}
