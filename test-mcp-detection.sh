#!/bin/bash

# MCP工具调用检测测试脚本
# 用于验证MCP工具调用检测功能是否正常工作

# 配置
GATEWAY_URL="${GATEWAY_URL:-https://localhost:8243}"
API_CONTEXT="${API_CONTEXT:-/mcp/test/1.0.0}"
ACCESS_TOKEN="${ACCESS_TOKEN:-your_access_token_here}"

echo "========================================"
echo "MCP Tool Call Detection Test"
echo "========================================"
echo "Gateway URL: $GATEWAY_URL"
echo "API Context: $API_CONTEXT"
echo ""

# 测试用例1：标准格式的工具列表响应
echo "Test 1: Standard format with tools list"
echo "----------------------------------------"
curl -X POST "$GATEWAY_URL$API_CONTEXT/tools/list" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "tools/list"
  }' \
  -w "\nHTTP Status: %{http_code}\n" \
  -s
echo ""
echo "Expected: isMcpToolCall = true"
echo ""

# 测试用例2：格式化的工具列表响应
echo "Test 2: Formatted JSON with tools list"
echo "----------------------------------------"
curl -X POST "$GATEWAY_URL$API_CONTEXT/tools/list" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 2,
    "method": "tools/list"
  }' \
  -w "\nHTTP Status: %{http_code}\n" \
  -s
echo ""
echo "Expected: isMcpToolCall = true"
echo ""

# 测试用例3：不包含工具列表的响应
echo "Test 3: Response without tools list"
echo "----------------------------------------"
curl -X POST "$GATEWAY_URL$API_CONTEXT/other" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 3,
    "method": "other/method"
  }' \
  -w "\nHTTP Status: %{http_code}\n" \
  -s
echo ""
echo "Expected: isMcpToolCall = false"
echo ""

# 测试用例4：工具调用（不是工具列表）
echo "Test 4: Tool call (not tools list)"
echo "----------------------------------------"
curl -X POST "$GATEWAY_URL$API_CONTEXT/tools/call" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 4,
    "method": "tools/call",
    "params": {
      "name": "get_weather",
      "arguments": {
        "location": "Beijing"
      }
    }
  }' \
  -w "\nHTTP Status: %{http_code}\n" \
  -s
echo ""
echo "Expected: isMcpToolCall = false (unless response contains tools list)"
echo ""

echo "========================================"
echo "Test completed!"
echo "========================================"
echo ""
echo "To view the detection results, check the logs:"
echo ""
echo "# Enable DEBUG logging (if not already enabled)"
echo "vi <WSO2_APIM_HOME>/repository/conf/log4j.properties"
echo "# Add: log4j.logger.org.wso2.carbon.apimgt.gateway.handlers.analytics.SynapseAnalyticsDataProvider=DEBUG"
echo ""
echo "# View logs"
echo "tail -f <WSO2_APIM_HOME>/repository/logs/wso2carbon.log | grep 'MCP Tool Call'"
echo ""
echo "You should see lines like:"
echo "DEBUG - MCP Tool Call Detection for API: test-mcp, Result: true"
echo "DEBUG - MCP Tool Call Detection for API: test-mcp, Result: false"

