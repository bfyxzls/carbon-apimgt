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

import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Test;

/**
 * Verifies MCP 2.0 Publisher tool-refresh payloads place namespaced fields under {@code params._meta}.
 */
public class MCPInitializerAndToolFetcherModernMetaTestCase {

    @Test
    public void modernToolsListPutsNamespacedMetaUnderParams() {
        MCPInitializerAndToolFetcher fetcher = new MCPInitializerAndToolFetcher(
                "https://example.com/mcp", null, null, false,
                APIConstants.MCP.PROTOCOL_VERSION_2026_JULY);

        JSONObject payload = fetcher.buildModernToolsListPayload();

        Assert.assertFalse("root-level _meta must not be used for MCP 2.0",
                payload.has(APIConstants.MCP.META_KEY));
        Assert.assertTrue(payload.has(APIConstants.MCP.PARAMS_KEY));
        JSONObject params = payload.getJSONObject(APIConstants.MCP.PARAMS_KEY);
        Assert.assertTrue(params.has(APIConstants.MCP.META_KEY));
        JSONObject meta = params.getJSONObject(APIConstants.MCP.META_KEY);
        Assert.assertEquals(APIConstants.MCP.PROTOCOL_VERSION_2026_JULY,
                meta.getString(APIConstants.MCP.META_PROTOCOL_VERSION_KEY));
        Assert.assertTrue(meta.has(APIConstants.MCP.META_CLIENT_CAPABILITIES_KEY));
        Assert.assertTrue(meta.getJSONObject(APIConstants.MCP.META_CLIENT_CAPABILITIES_KEY).length() >= 0);
        Assert.assertTrue(meta.has(APIConstants.MCP.META_CLIENT_INFO_KEY));
        Assert.assertFalse(meta.has(APIConstants.MCP.PROTOCOL_VERSION_KEY));
    }

    @Test
    public void modernServerDiscoverPutsNamespacedMetaUnderParams() {
        MCPInitializerAndToolFetcher fetcher = new MCPInitializerAndToolFetcher(
                "https://example.com/mcp", null, null, false,
                APIConstants.MCP.PROTOCOL_VERSION_2026_JULY);

        JSONObject payload = fetcher.buildServerDiscoverPayload();
        JSONObject meta = payload.getJSONObject(APIConstants.MCP.PARAMS_KEY)
                .getJSONObject(APIConstants.MCP.META_KEY);
        Assert.assertEquals(APIConstants.MCP.PROTOCOL_VERSION_2026_JULY,
                meta.getString(APIConstants.MCP.META_PROTOCOL_VERSION_KEY));
        Assert.assertTrue(meta.has(APIConstants.MCP.META_CLIENT_CAPABILITIES_KEY));
    }
}
