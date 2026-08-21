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

import org.junit.Assert;
import org.junit.Test;
import org.wso2.carbon.apimgt.impl.APIConstants;

public class MCPSessionCacheTestCase {

    @Test
    public void testPutGetAndExpire() throws Exception {
        MCPSessionCache cache = new MCPSessionCache(50L);
        cache.put("api1:client", "session-abc");
        Assert.assertEquals("session-abc", cache.get("api1:client"));
        Thread.sleep(80L);
        Assert.assertNull(cache.get("api1:client"));
    }

    @Test
    public void testInvalidate() {
        MCPSessionCache cache = new MCPSessionCache(60_000L);
        cache.put("k", "v");
        cache.invalidate("k");
        Assert.assertNull(cache.get("k"));
    }

    @Test
    public void testIssueSessionId() {
        String id1 = MCPProtocolTranslator.issueNorthboundSessionId();
        String id2 = MCPProtocolTranslator.issueNorthboundSessionId();
        Assert.assertNotNull(id1);
        Assert.assertNotNull(id2);
        Assert.assertNotEquals(id1, id2);
    }

    @Test
    public void testProtocolEraHelpers() {
        Assert.assertTrue(APIConstants.MCP.isLegacyProtocol(APIConstants.MCP.PROTOCOL_VERSION_2025_JUNE));
        Assert.assertTrue(APIConstants.MCP.isModernProtocol(APIConstants.MCP.PROTOCOL_VERSION_2026_JULY));
        Assert.assertTrue(APIConstants.MCP.isSupportedProtocolVersion(APIConstants.MCP.PROTOCOL_VERSION_2026_JULY));
        Assert.assertFalse(APIConstants.MCP.isSupportedProtocolVersion("1999-01-01"));
    }
}
