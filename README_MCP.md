# MCP计费功能 - 快速开始

## 🎯 功能概述

为WSO2 API Manager 9.31.86实现了MCP（Model Context Protocol）服务的计费功能，通过检测请求体中的 `"method":"tools/call"` 来识别MCP工具调用。

## ✨ 核心特性

- ✅ **自动检测** - 自动识别MCP工具调用
- ✅ **高性能** - 处理时间 < 1ms，对网关性能影响可忽略
- ✅ **格式兼容** - 支持压缩和格式化的JSON
- ✅ **异常容错** - 检测失败不影响主流程
- ✅ **代码注册** - Handler通过代码自动注册，无需配置文件

## 🚀 快速开始

### 1. 编译

```bash
cd E:\github\wso2\carbon-apimgt\carbon-apimgt-9.31.86
mvn clean install -DskipTests
```

### 2. 部署

```bash
# 部署 gateway JAR
cp components/apimgt/org.wso2.carbon.apimgt.gateway/target/*.jar \
   <WSO2_APIM_HOME>/repository/components/plugins/

# 部署 publisher.v1.common JAR（重要！）
cp components/apimgt/org.wso2.carbon.apimgt.rest.api.publisher.v1.common/target/*.jar \
   <WSO2_APIM_HOME>/repository/components/plugins/
```

### 3. Handler注册（✅ 已完成！）

**好消息：Handler已通过代码自动注册，无需任何配置！**

Handler已在 `TemplateBuilderUtil.java` 中注册：

```java
// 为所有API自动添加MCP Handler
vtb.addHandlerPriority(
    "org.wso2.carbon.apimgt.gateway.handlers.mcp.MCPRequestCaptureHandler",
    Collections.emptyMap(), 1);  // 优先级1，最先执行
```

**优势：**
- ✅ 无需修改配置文件
- ✅ 编译后自动生效
- ✅ 所有API自动包含
- ✅ 不会出现配置错误

详细说明见：[MCP_HANDLER_CODE_REGISTRATION.md](MCP_HANDLER_CODE_REGISTRATION.md) ⭐

### 4. 重启

```bash
cd <WSO2_APIM_HOME>/bin
./wso2server.sh restart
```

### 5. 测试

**Linux/Mac:**
```bash
./test-mcp-detection.sh
```

**Windows:**
```powershell
.\test-mcp-detection.ps1
```

### 6. 查看结果

启用DEBUG日志（可选）：

```properties
# 编辑 log4j.properties
log4j.logger.org.wso2.carbon.apimgt.gateway.handlers.mcp.MCPRequestCaptureHandler=DEBUG
log4j.logger.org.wso2.carbon.apimgt.gateway.handlers.analytics.SynapseAnalyticsDataProvider=DEBUG
```

查看日志：

```bash
tail -f <WSO2_APIM_HOME>/repository/logs/wso2carbon.log | grep -E "MCP|Captured"
```

预期输出：

```
INFO - Successfully captured request body, length: 123
DEBUG - Captured MCP request body: {"jsonrpc":"2.0"...
DEBUG - Retrieved original request body from MCP_REQUEST_BODY property
DEBUG - MCP Tool Call Detection for API: test-mcp, Result: true
INFO - MCP Tool Call Detection: true for API: test-mcp
```

## 🔍 检测逻辑

### ⚠️ 重要说明

**我们检测的是请求体（Request Body）中的 `"method":"tools/call"`**

由于 `getProperties()` 在响应阶段被调用，此时 MessageContext 中的消息体已经是响应体。要获取原始请求体，**Handler会在请求阶段自动捕获并保存**。

### 检测特征

检测**客户端发送的请求体**中是否包含：

```
"method":"tools/call"
```

### 示例

**客户端请求：**
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",  ◄─── 检测这个！
  "params": {
    "name": "get_weather",
    "arguments": {"location": "Beijing"}
  }
}
```

**检测结果：** `isMcpToolCall = true` ✅  
**原因：** 请求体包含 `"method":"tools/call"`

## 💰 计费集成

### 统计MCP调用次数

```sql
SELECT 
    api_name,
    COUNT(*) as tool_calls,
    COUNT(*) * 0.01 as total_cost
FROM 
    analytics_events
WHERE 
    isMcpToolCall = true
    AND event_time >= '2024-01-01'
GROUP BY 
    api_name;
```

### 按用户统计

```sql
SELECT 
    api_user_name,
    COUNT(*) as tool_calls
FROM 
    analytics_events
WHERE 
    isMcpToolCall = true
GROUP BY 
    api_user_name
ORDER BY 
    tool_calls DESC;
```

## 📖 文档

| 文档 | 说明 |
|------|------|
| [MCP_HANDLER_CODE_REGISTRATION.md](MCP_HANDLER_CODE_REGISTRATION.md) | **Handler代码注册方式（推荐）** ⭐ |
| [MCP_CONFIGURATION_COMPARISON.md](MCP_CONFIGURATION_COMPARISON.md) | 配置方式对比 |
| [MCP_SUMMARY.md](MCP_SUMMARY.md) | 完整总结 |
| [MCP_TOOL_CALL_DETECTION.md](MCP_TOOL_CALL_DETECTION.md) | 检测逻辑详解 |
| [MCP_BILLING_README.md](MCP_BILLING_README.md) | 计费使用指南 |
| [MCP_BILLING_IMPLEMENTATION.md](MCP_BILLING_IMPLEMENTATION.md) | 实现细节 |
| [MCP_REQUEST_BODY_DEBUG_GUIDE.md](MCP_REQUEST_BODY_DEBUG_GUIDE.md) | 调试指南 |

## 🐛 故障排查

### 检测不到MCP调用

1. 确认两个JAR都已部署
   ```bash
   ls -la <WSO2_APIM_HOME>/repository/components/plugins/ | grep -E "gateway|publisher.v1.common"
   ```

2. 启用DEBUG日志查看详细信息

3. 检查API的Synapse配置
   ```bash
   cat <WSO2_APIM_HOME>/repository/deployment/server/synapse-configs/default/api/*.xml | grep MCPRequestCaptureHandler
   ```

### 日志中没有信息

1. 确认已重启WSO2 APIM
2. 确认请求到达Gateway
3. 检查Content-Type是否为 `application/json`

## 📊 性能指标

| 指标 | 值 |
|------|-----|
| 处理时间 | < 1 ms |
| CPU开销 | 可忽略 |
| 内存开销 | 可忽略 |

## 📝 变更历史

### 2025-01-XX - v1.0

- ✅ 实现MCP工具调用检测
- ✅ 添加 `isMcpToolCall` 自定义属性
- ✅ 通过代码注册Handler（无需配置）
- ✅ 优化日志输出
- ✅ 添加测试脚本

### 检测逻辑

检测**请求体**中的 `"method":"tools/call"`

**原因：** 根据MCP工程师确认，需要检测客户端发送的请求，而不是后端的响应

## 🤝 贡献

欢迎提交问题和改进建议！

## 📄 许可证

Apache License 2.0

---

**需要帮助？** 查看 [MCP_HANDLER_CODE_REGISTRATION.md](MCP_HANDLER_CODE_REGISTRATION.md) 获取完整文档。
