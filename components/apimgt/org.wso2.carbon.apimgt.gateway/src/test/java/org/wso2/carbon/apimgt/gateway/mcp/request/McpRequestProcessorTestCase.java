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

package org.wso2.carbon.apimgt.gateway.mcp.request;

import org.apache.synapse.MessageContext;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.wso2.carbon.apimgt.gateway.APIMgtGatewayConstants;
import org.wso2.carbon.apimgt.gateway.mcp.response.McpResponseDto;
import org.wso2.carbon.apimgt.gateway.utils.MCPUtils;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.keymgt.model.entity.API;

/**
 * Ensures local MCP handling uses negotiated {@code MCP_METHOD} (header-first), not body alone.
 */
@RunWith(PowerMockRunner.class)
@PrepareForTest({MCPUtils.class})
public class McpRequestProcessorTestCase {

    @Test
    public void testProcessRequestPrefersNegotiatedMcpMethodOverBody() {
        PowerMockito.mockStatic(MCPUtils.class);
        MessageContext messageContext = Mockito.mock(MessageContext.class);
        Mockito.when(messageContext.getProperty(APIMgtGatewayConstants.MCP_METHOD))
                .thenReturn(APIConstants.MCP.METHOD_SERVER_DISCOVER);

        McpRequest requestBody = new McpRequest(1);
        requestBody.setMethod(APIConstants.MCP.METHOD_TOOL_LIST);

        API api = new API();
        api.setName("mcp-api");
        api.setVersion("1.0.0");

        McpResponseDto expected = new McpResponseDto("{}", 200, null);
        PowerMockito.when(MCPUtils.processInternalRequest(
                Mockito.eq(messageContext), Mockito.eq(api), Mockito.eq(requestBody),
                Mockito.eq(APIConstants.MCP.METHOD_SERVER_DISCOVER))).thenReturn(expected);

        McpResponseDto actual = McpRequestProcessor.processRequest(messageContext, api, requestBody);
        Assert.assertSame(expected, actual);

        ArgumentCaptor<String> methodCaptor = ArgumentCaptor.forClass(String.class);
        PowerMockito.verifyStatic(MCPUtils.class);
        MCPUtils.processInternalRequest(Mockito.eq(messageContext), Mockito.eq(api),
                Mockito.eq(requestBody), methodCaptor.capture());
        Assert.assertEquals(APIConstants.MCP.METHOD_SERVER_DISCOVER, methodCaptor.getValue());
    }

    @Test
    public void testProcessRequestFallsBackToBodyMethod() {
        PowerMockito.mockStatic(MCPUtils.class);
        MessageContext messageContext = Mockito.mock(MessageContext.class);
        Mockito.when(messageContext.getProperty(APIMgtGatewayConstants.MCP_METHOD)).thenReturn(null);

        McpRequest requestBody = new McpRequest(2);
        requestBody.setMethod(APIConstants.MCP.METHOD_SERVER_DISCOVER);

        API api = new API();
        api.setName("mcp-api");
        api.setVersion("1.0.0");

        McpResponseDto expected = new McpResponseDto("{}", 200, null);
        PowerMockito.when(MCPUtils.processInternalRequest(
                Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.eq(APIConstants.MCP.METHOD_SERVER_DISCOVER))).thenReturn(expected);

        Assert.assertSame(expected,
                McpRequestProcessor.processRequest(messageContext, api, requestBody));
    }
}
