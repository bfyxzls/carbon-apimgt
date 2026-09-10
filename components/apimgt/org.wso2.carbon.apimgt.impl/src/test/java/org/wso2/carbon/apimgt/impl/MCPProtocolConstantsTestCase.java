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

package org.wso2.carbon.apimgt.impl;

import org.junit.Assert;
import org.junit.Test;

/**
 * Unit tests for MCP dual-era protocol constants and helpers.
 */
public class MCPProtocolConstantsTestCase {

    @Test
    public void testSupportedVersionsIncludeLegacyAndModern() {
        Assert.assertTrue(APIConstants.MCP.SUPPORTED_PROTOCOL_VERSIONS
                .contains(APIConstants.MCP.PROTOCOL_VERSION_2025_JUNE));
        Assert.assertTrue(APIConstants.MCP.SUPPORTED_PROTOCOL_VERSIONS
                .contains(APIConstants.MCP.PROTOCOL_VERSION_2026_JULY));
        Assert.assertEquals(2, APIConstants.MCP.SUPPORTED_PROTOCOL_VERSIONS.size());
    }

    @Test
    public void testAllowedMethodsIncludeServerDiscover() {
        Assert.assertTrue(APIConstants.MCP.ALLOWED_METHODS.contains(APIConstants.MCP.METHOD_SERVER_DISCOVER));
        Assert.assertTrue(APIConstants.MCP.ALLOWED_METHODS.contains(APIConstants.MCP.METHOD_INITIALIZE));
    }

    @Test
    public void testEraResolution() {
        Assert.assertEquals(APIConstants.MCP.PROTOCOL_ERA_LEGACY,
                APIConstants.MCP.resolveProtocolEra(APIConstants.MCP.PROTOCOL_VERSION_2025_JUNE));
        Assert.assertEquals(APIConstants.MCP.PROTOCOL_ERA_MODERN,
                APIConstants.MCP.resolveProtocolEra(APIConstants.MCP.PROTOCOL_VERSION_2026_JULY));
        Assert.assertEquals(APIConstants.MCP.PROTOCOL_ERA_LEGACY,
                APIConstants.MCP.resolveProtocolEra(null));
        Assert.assertTrue(APIConstants.MCP.isLegacyProtocol("anything-else"));
        Assert.assertTrue(APIConstants.MCP.isModernProtocol(APIConstants.MCP.PROTOCOL_VERSION_2026_JULY));
    }

    @Test
    public void testModernHeadersDefined() {
        Assert.assertEquals("Mcp-Method", APIConstants.MCP.HEADER_MCP_METHOD);
        Assert.assertEquals("Mcp-Name", APIConstants.MCP.HEADER_MCP_NAME);
        Assert.assertEquals("_meta", APIConstants.MCP.META_KEY);
        Assert.assertEquals("io.modelcontextprotocol/protocolVersion",
                APIConstants.MCP.META_PROTOCOL_VERSION_KEY);
        Assert.assertEquals("io.modelcontextprotocol/clientCapabilities",
                APIConstants.MCP.META_CLIENT_CAPABILITIES_KEY);
        Assert.assertEquals("io.modelcontextprotocol/clientInfo",
                APIConstants.MCP.META_CLIENT_INFO_KEY);
    }
}
