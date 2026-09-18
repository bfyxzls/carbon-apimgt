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
        Assert.assertEquals(APIConstants.MCP.RESULT_TYPE_COMPLETE, result.get("resultType").getAsString());
        Assert.assertTrue(result.getAsJsonArray("supportedVersions").toString()
                .contains(APIConstants.MCP.PROTOCOL_VERSION_2026_JULY));
        Assert.assertEquals("mcp-server",
                result.getAsJsonObject("_meta")
                        .getAsJsonObject(APIConstants.MCP.META_SERVER_INFO_KEY)
                        .get("name").getAsString());
        Assert.assertTrue(result.has("capabilities"));
        Assert.assertEquals("desc", result.get("instructions").getAsString());
        Assert.assertEquals(APIConstants.MCP.CACHE_SCOPE_PUBLIC, result.get("cacheScope").getAsString());
    }

    @Test
    public void testServerDiscoverResponseUsesExplicitProtocolVersion() {
        String payload = MCPPayloadGenerator.getServerDiscoverResponse(11, "mcp-server", "2.0", "desc", false,
                APIConstants.MCP.PROTOCOL_VERSION_2026_JULY);
        JsonObject result = JsonParser.parseString(payload).getAsJsonObject().getAsJsonObject("result");
        Assert.assertEquals(APIConstants.MCP.PROTOCOL_VERSION_2026_JULY,
                result.get("protocolVersion").getAsString());
        Assert.assertEquals(APIConstants.MCP.RESULT_TYPE_COMPLETE, result.get("resultType").getAsString());
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

    @Test
    public void testToolListPreservesDefsAndInlinesSingleRef() {
        org.wso2.carbon.apimgt.api.model.subscription.URLMapping mapping =
                new org.wso2.carbon.apimgt.api.model.subscription.URLMapping();
        mapping.setUrlPattern("mcp-case.get_case_list");
        mapping.setDescription("查询案例列表");
        mapping.setSchemaDefinition("{"
                + "\"type\":\"object\","
                + "\"properties\":{\"caseInput\":{\"$ref\":\"#/$defs/CaseInputModel\"}},"
                + "\"required\":[\"caseInput\"],"
                + "\"$defs\":{\"CaseInputModel\":{"
                + "\"type\":\"object\","
                + "\"properties\":{"
                + "\"title\":{\"type\":\"string\"},"
                + "\"caseGrade\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}"
                + "}}}}");
        String payload = MCPPayloadGenerator.generateToolListPayload(1,
                java.util.Collections.singletonList(mapping), true);
        JsonObject inputSchema = JsonParser.parseString(payload).getAsJsonObject()
                .getAsJsonObject("result").getAsJsonArray("tools").get(0).getAsJsonObject()
                .getAsJsonObject("inputSchema");
        Assert.assertTrue(inputSchema.getAsJsonObject("properties").has("title"));
        Assert.assertTrue(inputSchema.getAsJsonObject("properties").has("caseGrade"));
        Assert.assertFalse(inputSchema.getAsJsonObject("properties").has("caseInput"));
    }

    @Test
    public void testExtractInputSchemaDefinitionFromEnvelope() {
        String envelope = "{"
                + "\"inputSchema\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}},"
                + "\"title\":\"Search\""
                + "}";
        String extracted = MCPPayloadGenerator.extractInputSchemaDefinition(envelope);
        JsonObject schema = JsonParser.parseString(extracted).getAsJsonObject();
        Assert.assertEquals("object", schema.get("type").getAsString());
        Assert.assertTrue(schema.getAsJsonObject("properties").has("q"));
        Assert.assertFalse(schema.has("title"));
    }

    @Test
    public void testToolListEmitsFullMcp20MetadataFromEnvelope() {
        org.wso2.carbon.apimgt.api.model.subscription.URLMapping mapping =
                new org.wso2.carbon.apimgt.api.model.subscription.URLMapping();
        mapping.setUrlPattern("weather.get");
        mapping.setDescription("Get weather");
        mapping.setSchemaDefinition("{"
                + "\"inputSchema\":{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}},"
                + "\"title\":\"Weather\","
                + "\"outputSchema\":{\"type\":\"object\",\"properties\":{\"temp\":{\"type\":\"number\"}}},"
                + "\"annotations\":{\"readOnlyHint\":true,\"destructiveHint\":false},"
                + "\"icons\":[{\"src\":\"https://example.com/icon.png\"}],"
                + "\"_meta\":{\"vendor\":\"apim\","
                + "\"ui\":{\"resourceUri\":\"ui://weather/view.html\"},"
                + "\"openai/outputTemplate\":\"ui://weather/view.html\"}"
                + "}");
        String payload = MCPPayloadGenerator.generateToolListPayload(2,
                java.util.Collections.singletonList(mapping), true);
        JsonObject tool = JsonParser.parseString(payload).getAsJsonObject()
                .getAsJsonObject("result").getAsJsonArray("tools").get(0).getAsJsonObject();
        Assert.assertEquals("Weather", tool.get("title").getAsString());
        Assert.assertTrue(tool.getAsJsonObject("inputSchema").getAsJsonObject("properties").has("city"));
        Assert.assertTrue(tool.getAsJsonObject("outputSchema").getAsJsonObject("properties").has("temp"));
        Assert.assertTrue(tool.getAsJsonObject("annotations").get("readOnlyHint").getAsBoolean());
        Assert.assertEquals(1, tool.getAsJsonArray("icons").size());
        Assert.assertEquals("apim", tool.getAsJsonObject("_meta").get("vendor").getAsString());
        Assert.assertEquals("ui://weather/view.html",
                tool.getAsJsonObject("_meta").getAsJsonObject("ui").get("resourceUri").getAsString());
        Assert.assertEquals("ui://weather/view.html",
                tool.getAsJsonObject("_meta").get("openai/outputTemplate").getAsString());
        Assert.assertFalse(tool.has("inputSchema") && tool.getAsJsonObject("inputSchema").has("annotations"));
    }

    @Test
    public void testToolCallAddsStructuredContentForJsonBody() {
        String payload = MCPPayloadGenerator.generateMCPResponsePayload(3, false, "{\"ok\":true}");
        JsonObject result = JsonParser.parseString(payload).getAsJsonObject().getAsJsonObject("result");
        Assert.assertTrue(result.getAsJsonObject("structuredContent").get("ok").getAsBoolean());
        Assert.assertEquals("text", result.getAsJsonArray("content").get(0).getAsJsonObject()
                .get("type").getAsString());
    }

    @Test
    public void testPromptGetStubHasMessagesArray() {
        String payload = MCPPayloadGenerator.generatePromptGetResponse(4);
        JsonObject result = JsonParser.parseString(payload).getAsJsonObject().getAsJsonObject("result");
        Assert.assertTrue(result.has("messages"));
        Assert.assertEquals(0, result.getAsJsonArray("messages").size());
    }
}
