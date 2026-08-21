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
import org.wso2.carbon.apimgt.gateway.mcp.request.Params;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.keymgt.model.entity.API;

import java.util.HashMap;
import java.util.Map;

public class MCPProtocolNegotiatorTestCase {

    @Test
    public void testNegotiateLegacyFromInitialize() {
        Axis2MessageContext messageContext = mockContext(new HashMap<>());
        McpRequest request = new McpRequest(1);
        request.setMethod(APIConstants.MCP.METHOD_INITIALIZE);
        Params params = new Params();
        params.setProtocolVersion(APIConstants.MCP.PROTOCOL_VERSION_2025_JUNE);
        request.setParams(params);

        String version = MCPProtocolNegotiator.negotiateAndStore(messageContext, request, null);
        Assert.assertEquals(APIConstants.MCP.PROTOCOL_VERSION_2025_JUNE, version);
        Assert.assertEquals(APIConstants.MCP.PROTOCOL_ERA_LEGACY,
                messageContext.getProperty(APIMgtGatewayConstants.MCP_PROTOCOL_ERA_KEY));
        Assert.assertEquals(APIConstants.MCP.METHOD_INITIALIZE,
                messageContext.getProperty(APIMgtGatewayConstants.MCP_METHOD));
    }

    @Test
    public void testNegotiateModernFromHeaderAndMethod() {
        Map<String, Object> headers = new HashMap<>();
        headers.put(APIConstants.MCP.MCP_PROTOCOL_VERSION_HEADER, APIConstants.MCP.PROTOCOL_VERSION_2026_JULY);
        headers.put(APIConstants.MCP.HEADER_MCP_METHOD, APIConstants.MCP.METHOD_TOOL_LIST);
        Axis2MessageContext messageContext = mockContext(headers);

        McpRequest request = new McpRequest(2);
        request.setMethod(APIConstants.MCP.METHOD_TOOL_LIST);

        String version = MCPProtocolNegotiator.negotiateAndStore(messageContext, request, null);
        Assert.assertEquals(APIConstants.MCP.PROTOCOL_VERSION_2026_JULY, version);
        Assert.assertEquals(APIConstants.MCP.PROTOCOL_ERA_MODERN,
                messageContext.getProperty(APIMgtGatewayConstants.MCP_PROTOCOL_ERA_KEY));
        Assert.assertEquals(APIConstants.MCP.METHOD_TOOL_LIST,
                messageContext.getProperty(APIMgtGatewayConstants.MCP_METHOD));
    }

    @Test
    public void testNegotiateModernFromServerDiscover() {
        Axis2MessageContext messageContext = mockContext(new HashMap<>());
        McpRequest request = new McpRequest(3);
        request.setMethod(APIConstants.MCP.METHOD_SERVER_DISCOVER);

        String version = MCPProtocolNegotiator.negotiateAndStore(messageContext, request, null);
        Assert.assertEquals(APIConstants.MCP.PROTOCOL_VERSION_2026_JULY, version);
        Assert.assertTrue(MCPProtocolNegotiator.isModernNorthbound(messageContext));
    }

    @Test
    public void testPreferMcpMethodHeaderOverBody() {
        Map<String, Object> headers = new HashMap<>();
        headers.put(APIConstants.MCP.HEADER_MCP_METHOD, APIConstants.MCP.METHOD_TOOL_CALL);
        Axis2MessageContext messageContext = mockContext(headers);
        McpRequest request = new McpRequest(4);
        request.setMethod(APIConstants.MCP.METHOD_TOOL_LIST);

        Assert.assertEquals(APIConstants.MCP.METHOD_TOOL_CALL,
                MCPProtocolNegotiator.resolveMethod(messageContext, request));
    }

    @Test
    public void testBackendProtocolFromApiPropertyAndNeedsTranslation() {
        Map<String, Object> headers = new HashMap<>();
        headers.put(APIConstants.MCP.MCP_PROTOCOL_VERSION_HEADER, APIConstants.MCP.PROTOCOL_VERSION_2026_JULY);
        Axis2MessageContext messageContext = mockContext(headers);

        API api = new API();
        api.setProtocolVersion(APIConstants.MCP.PROTOCOL_VERSION_2025_JUNE);

        McpRequest request = new McpRequest(5);
        request.setMethod(APIConstants.MCP.METHOD_TOOL_LIST);

        MCPProtocolNegotiator.negotiateAndStore(messageContext, request, api);
        Assert.assertEquals(APIConstants.MCP.PROTOCOL_VERSION_2025_JUNE,
                messageContext.getProperty(APIMgtGatewayConstants.MCP_BACKEND_PROTOCOL_VERSION_KEY));
        Assert.assertTrue(MCPProtocolNegotiator.needsTranslation(messageContext));
    }

    @Test
    public void testResolveBackendDefaultsToLegacy() {
        Assert.assertEquals(APIConstants.MCP.PROTOCOL_VERSION_2025_JUNE,
                MCPProtocolNegotiator.resolveBackendProtocolVersion(null));
        API api = new API();
        Map<String, String> props = new HashMap<>();
        props.put(APIConstants.MCP.PROTOCOL_VERSION_KEY, APIConstants.MCP.PROTOCOL_VERSION_2026_JULY);
        api.setApiProperties(props);
        Assert.assertEquals(APIConstants.MCP.PROTOCOL_VERSION_2026_JULY,
                MCPProtocolNegotiator.resolveBackendProtocolVersion(api));
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
