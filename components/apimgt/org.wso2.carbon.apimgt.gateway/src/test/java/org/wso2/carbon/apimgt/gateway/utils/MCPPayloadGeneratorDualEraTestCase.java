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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Assert;
import org.junit.Test;
import org.wso2.carbon.apimgt.impl.APIConstants;

public class MCPPayloadGeneratorDualEraTestCase {

    @Test
    public void testInitializeResponseEchoesNegotiatedVersion() {
        String legacy = MCPPayloadGenerator.getInitializeResponse(1, "svc", "1.0", "desc", false,
                APIConstants.MCP.PROTOCOL_VERSION_2025_JUNE);
        JsonObject legacyRoot = JsonParser.parseString(legacy).getAsJsonObject();
        Assert.assertEquals(APIConstants.MCP.PROTOCOL_VERSION_2025_JUNE,
                legacyRoot.getAsJsonObject("result").get("protocolVersion").getAsString());

        String modern = MCPPayloadGenerator.getInitializeResponse(1, "svc", "1.0", "desc", false,
                APIConstants.MCP.PROTOCOL_VERSION_2026_JULY);
        JsonObject modernRoot = JsonParser.parseString(modern).getAsJsonObject();
        Assert.assertEquals(APIConstants.MCP.PROTOCOL_VERSION_2026_JULY,
                modernRoot.getAsJsonObject("result").get("protocolVersion").getAsString());
    }

    @Test
    public void testServerDiscoverResponse() {
        String payload = MCPPayloadGenerator.getServerDiscoverResponse(10, "mcp-server", "2.0", "desc", false);
        JsonObject root = JsonParser.parseString(payload).getAsJsonObject();
        JsonObject result = root.getAsJsonObject("result");
        Assert.assertEquals(APIConstants.MCP.PROTOCOL_VERSION_2026_JULY,
                result.get("protocolVersion").getAsString());
        Assert.assertEquals("mcp-server", result.getAsJsonObject("serverInfo").get("name").getAsString());
        Assert.assertTrue(result.has("capabilities"));
    }

    @Test
    public void testInitializeErrorListsBothSupportedVersions() {
        JsonObject data = MCPPayloadGenerator.getInitializeErrorBody("1999-01-01");
        Assert.assertEquals("1999-01-01", data.get("requested").getAsString());
        Assert.assertEquals(2, data.getAsJsonArray("supported").size());
        Assert.assertTrue(data.getAsJsonArray("supported").toString()
                .contains(APIConstants.MCP.PROTOCOL_VERSION_2025_JUNE));
        Assert.assertTrue(data.getAsJsonArray("supported").toString()
                .contains(APIConstants.MCP.PROTOCOL_VERSION_2026_JULY));
    }
}
