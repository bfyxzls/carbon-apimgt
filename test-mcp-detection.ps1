# MCP工具调用检测测试脚本 (PowerShell版本)
# 用于验证MCP工具调用检测功能是否正常工作

# 配置
$GATEWAY_URL = if ($env:GATEWAY_URL) { $env:GATEWAY_URL } else { "https://localhost:8243" }
$API_CONTEXT = if ($env:API_CONTEXT) { $env:API_CONTEXT } else { "/mcp/test/1.0.0" }
$ACCESS_TOKEN = if ($env:ACCESS_TOKEN) { $env:ACCESS_TOKEN } else { "your_access_token_here" }

Write-Host "========================================"
Write-Host "MCP Tool Call Detection Test"
Write-Host "========================================"
Write-Host "Gateway URL: $GATEWAY_URL"
Write-Host "API Context: $API_CONTEXT"
Write-Host ""

# 忽略SSL证书验证（仅用于测试）
if (-not ([System.Management.Automation.PSTypeName]'ServerCertificateValidationCallback').Type) {
    $certCallback = @"
    using System;
    using System.Net;
    using System.Net.Security;
    using System.Security.Cryptography.X509Certificates;
    public class ServerCertificateValidationCallback
    {
        public static void Ignore()
        {
            if(ServicePointManager.ServerCertificateValidationCallback ==null)
            {
                ServicePointManager.ServerCertificateValidationCallback += 
                    delegate
                    (
                        Object obj, 
                        X509Certificate certificate, 
                        X509Chain chain, 
                        SslPolicyErrors errors
                    )
                    {
                        return true;
                    };
            }
        }
    }
"@
    Add-Type $certCallback
}
[ServerCertificateValidationCallback]::Ignore()

# 测试用例1：标准格式的工具列表响应
Write-Host "Test 1: Standard format with tools list"
Write-Host "----------------------------------------"
$body1 = @{
    jsonrpc = "2.0"
    id = 1
    method = "tools/list"
} | ConvertTo-Json

try {
    $response1 = Invoke-RestMethod -Uri "$GATEWAY_URL$API_CONTEXT/tools/list" `
        -Method Post `
        -Headers @{
            "Authorization" = "Bearer $ACCESS_TOKEN"
            "Content-Type" = "application/json"
        } `
        -Body $body1
    Write-Host "Response: $($response1 | ConvertTo-Json -Compress)"
} catch {
    Write-Host "Error: $($_.Exception.Message)"
    Write-Host "Status: $($_.Exception.Response.StatusCode.value__)"
}
Write-Host ""
Write-Host "Expected: isMcpToolCall = true"
Write-Host ""

# 测试用例2：格式化的工具列表响应
Write-Host "Test 2: Formatted JSON with tools list"
Write-Host "----------------------------------------"
$body2 = @"
{
    "jsonrpc": "2.0",
    "id": 2,
    "method": "tools/list"
}
"@

try {
    $response2 = Invoke-RestMethod -Uri "$GATEWAY_URL$API_CONTEXT/tools/list" `
        -Method Post `
        -Headers @{
            "Authorization" = "Bearer $ACCESS_TOKEN"
            "Content-Type" = "application/json"
        } `
        -Body $body2
    Write-Host "Response: $($response2 | ConvertTo-Json -Compress)"
} catch {
    Write-Host "Error: $($_.Exception.Message)"
    Write-Host "Status: $($_.Exception.Response.StatusCode.value__)"
}
Write-Host ""
Write-Host "Expected: isMcpToolCall = true"
Write-Host ""

# 测试用例3：不包含工具列表的响应
Write-Host "Test 3: Response without tools list"
Write-Host "----------------------------------------"
$body3 = @{
    jsonrpc = "2.0"
    id = 3
    method = "other/method"
} | ConvertTo-Json

try {
    $response3 = Invoke-RestMethod -Uri "$GATEWAY_URL$API_CONTEXT/other" `
        -Method Post `
        -Headers @{
            "Authorization" = "Bearer $ACCESS_TOKEN"
            "Content-Type" = "application/json"
        } `
        -Body $body3
    Write-Host "Response: $($response3 | ConvertTo-Json -Compress)"
} catch {
    Write-Host "Error: $($_.Exception.Message)"
    Write-Host "Status: $($_.Exception.Response.StatusCode.value__)"
}
Write-Host ""
Write-Host "Expected: isMcpToolCall = false"
Write-Host ""

# 测试用例4：工具调用（不是工具列表）
Write-Host "Test 4: Tool call (not tools list)"
Write-Host "----------------------------------------"
$body4 = @{
    jsonrpc = "2.0"
    id = 4
    method = "tools/call"
    params = @{
        name = "get_weather"
        arguments = @{
            location = "Beijing"
        }
    }
} | ConvertTo-Json -Depth 10

try {
    $response4 = Invoke-RestMethod -Uri "$GATEWAY_URL$API_CONTEXT/tools/call" `
        -Method Post `
        -Headers @{
            "Authorization" = "Bearer $ACCESS_TOKEN"
            "Content-Type" = "application/json"
        } `
        -Body $body4
    Write-Host "Response: $($response4 | ConvertTo-Json -Compress)"
} catch {
    Write-Host "Error: $($_.Exception.Message)"
    Write-Host "Status: $($_.Exception.Response.StatusCode.value__)"
}
Write-Host ""
Write-Host "Expected: isMcpToolCall = false (unless response contains tools list)"
Write-Host ""

Write-Host "========================================"
Write-Host "Test completed!"
Write-Host "========================================"
Write-Host ""
Write-Host "To view the detection results, check the logs:"
Write-Host ""
Write-Host "# Enable DEBUG logging (if not already enabled)"
Write-Host "# Edit: <WSO2_APIM_HOME>\repository\conf\log4j.properties"
Write-Host "# Add: log4j.logger.org.wso2.carbon.apimgt.gateway.handlers.analytics.SynapseAnalyticsDataProvider=DEBUG"
Write-Host ""
Write-Host "# View logs"
Write-Host "Get-Content <WSO2_APIM_HOME>\repository\logs\wso2carbon.log -Wait -Tail 50 | Select-String 'MCP Tool Call'"
Write-Host ""
Write-Host "You should see lines like:"
Write-Host "DEBUG - MCP Tool Call Detection for API: test-mcp, Result: true"
Write-Host "DEBUG - MCP Tool Call Detection for API: test-mcp, Result: false"

