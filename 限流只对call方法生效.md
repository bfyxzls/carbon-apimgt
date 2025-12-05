# Throttle request statistics for MCP service
_Exported on 6/4/2026 at 16:13:13 GMT+8 from Cursor (3.5.38)_

---

**User**

@org.wso2.carbon.apimgt.gateway 目前在apigmt项目中，对于throttle请求限流的统计中，mcp的服务，我希望只对tools/call方法进行统计，像工具列表，初始化这些mcp方法，不进行统计，请帮我检查一下，当前项目对于mcp这块是这样的吗

---

**Cursor**

正在检查 apimgt 项目中 MCP 服务的限流统计逻辑，确认是否仅统计 `tools/call`。


结论：**是的，当前项目在 MCP 限流统计上已经按你的预期实现——只对 `tools/call` 计入限流，其他 MCP 方法（initialize、tools/list、ping 等）不参与限流统计。**

## 限流逻辑（`ThrottleHandler`）

核心判断在 `ThrottleHandler.handleRequest()`：当 API 类型为 MCP 时，只有 `tools/call` 会进入限流流程，其余方法直接跳过。

```673:681:components/apimgt/org.wso2.carbon.apimgt.gateway/src/main/java/org/wso2/carbon/apimgt/gateway/handlers/throttling/ThrottleHandler.java
        String apiType = (String) messageContext.getProperty(APIMgtGatewayConstants.API_TYPE);
        if (APIConstants.API_TYPE_MCP.equalsIgnoreCase(apiType)) {
            String mcpMethod = (String) messageContext.getProperty(APIMgtGatewayConstants.MCP_METHOD);
            if (!APIConstants.MCP.METHOD_TOOL_CALL.equalsIgnoreCase(mcpMethod)) {
                if (log.isDebugEnabled()) {
                    log.debug("Skipping MCP call request throttling.");
                }
                return true;
            }
        }
```

Blocking 条件检查同样排除了非 `tools/call` 的 MCP 方法（注释里称为 "handshake method"）：

```252:258:components/apimgt/org.wso2.carbon.apimgt.gateway/src/main/java/org/wso2/carbon/apimgt/gateway/handlers/throttling/ThrottleHandler.java
            if (synCtx.getPropertyKeySet().contains(MCP_METHOD)
                    && !APIConstants.MCP.METHOD_TOOL_CALL.equals(synCtx.getProperty(MCP_METHOD))) {
                if (log.isDebugEnabled()) {
                    log.debug("Skipping blocking checks for MCP handshake method: "
                            + synCtx.getProperty(MCP_METHOD));
                }
                isBlockedRequest = false;
```

## MCP 方法识别流程

Handler 链顺序（`TemplateBuilderUtil`）：

1. `McpInitHandler` — 解析 JSON-RPC，设置 `MCP_METHOD`
2. `APIAuthenticationHandler` — 认证
3. `ThrottleHandler` — 限流

`McpInitHandler` 会从请求体解析 `method` 并写入 message context：

```310:311:components/apimgt/org.wso2.carbon.apimgt.gateway/src/main/java/org/wso2/carbon/apimgt/gateway/handlers/mcp/McpInitHandler.java
                method = request.getMethod();
                messageContext.setProperty(MCP_METHOD, method);
```

## 各 MCP 方法的限流行为

| MCP 方法 | 是否计入限流 |
|---|---|
| `tools/call` | ✅ 是 |
| `initialize` | ❌ 否 |
| `tools/list` | ❌ 否 |
| `ping` | ❌ 否 |
| `notifications/initialized` | ❌ 否 |
| `resources/list` | ❌ 否 |
| `resources/templates/list` | ❌ 否 |
| `prompts/list` | ❌ 否 |
| Streamable HTTP `GET /mcp`（SSE 通道） | ❌ 否（未设置 `MCP_METHOD`，同样被跳过） |

常量定义在 `APIConstants.MCP`：

```3678:3688:components/apimgt/org.wso2.carbon.apimgt.impl/src/main/java/org/wso2/carbon/apimgt/impl/APIConstants.java
        public static final String METHOD_INITIALIZE = "initialize";
        public static final String METHOD_TOOL_LIST = "tools/list";
        public static final String METHOD_TOOL_CALL = "tools/call";
        public static final String METHOD_PING = "ping";
        public static final String METHOD_NOTIFICATION_INITIALIZED = "notifications/initialized";
        public static final String METHOD_RESOURCES_LIST = "resources/list";
        public static final String METHOD_RESOURCE_TEMPLATE_LIST = "resources/templates/list";
        public static final String METHOD_PROMPTS_LIST = "prompts/list";
        public static final List<String> ALLOWED_METHODS = Arrays.asList(METHOD_INITIALIZE, METHOD_TOOL_LIST,
                METHOD_TOOL_CALL, METHOD_PING, METHOD_NOTIFICATION_INITIALIZED, METHOD_RESOURCES_LIST, METHOD_PROMPTS_LIST,
                METHOD_RESOURCE_TEMPLATE_LIST);
```

## 需要区分的点：Analytics ≠ Throttle

**限流（Throttle）**：已按上述逻辑，仅 `tools/call` 计入。

**Analytics（用量分析）**：`SynapseAnalyticsDataProvider` 仍会记录所有 MCP 请求，并在 custom properties 中写入 `mcpMethod` 字段，**不会**过滤掉 initialize、tools/list 等。若你关心的是 Analytics 面板上的请求计数，那和限流统计是两套逻辑。

---

**总结**：当前 gateway 对 MCP 的 **throttle 限流统计** 已符合你的需求——只对 `tools/call` 限流，`initialize`、`tools/list` 等握手/元数据方法均不计入。若你实际观察到其他行为（例如 initialize 也触发了 429），可以说一下具体场景，我可以帮你进一步排查。

---

**User**

这块除了标准的mcp服务以外，还是rest api中分类为mcp的服务，也是需要在限流时只对tools/call来做限制的

---

**Cursor**

正在检查 REST API 中分类为 MCP 的服务是否也仅对 `tools/call` 限流。


确认存在缺口：REST API 若分类为 MCP 会挂载 `McpInitHandler`，但 `ThrottleHandler.handleRequest()` 仅检查 `API_TYPE_MCP`，导致 `initialize`、`tools/list` 等仍会被限流。正在修复，使其与 blocking 逻辑一致，基于 `MCP_METHOD` 判断。


你的判断是对的：**在修复前，REST API 分类为 MCP 的服务并没有和标准 MCP 服务一样，只对 `tools/call` 做限流。**

## 修复前的行为差异

部署时，`TemplateBuilderUtil` 对两类 API 都会挂载 `McpInitHandler`：

```269:274:components/apimgt/org.wso2.carbon.apimgt.rest.api.publisher.v1.common/src/main/java/org/wso2/carbon/apimgt/rest/api/publisher/v1/common/TemplateBuilderUtil.java
        if (APIConstants.API_TYPE_MCP.equals(api.getType())  ||
                (api.getApiCategories() != null
                        && api.getApiCategories().stream()
                        .anyMatch(o -> "MCP".equals(o.getName())))) {
            vtb.addHandler("org.wso2.carbon.apimgt.gateway.handlers.mcp.McpInitHandler",
                    Collections.emptyMap());
```

但 `ThrottleHandler.handleRequest()` 原先只判断 `apiType == MCP`：

| 场景 | `McpInitHandler` | `MCP_METHOD` 已设置 | 原先限流行为 |
|---|---|---|---|
| 标准 MCP 服务 | ✅ | ✅ | 仅 `tools/call` 限流 |
| REST API + MCP 分类 | ✅ | ✅ | ❌ **所有 MCP 方法都限流** |
| 普通 REST API | ❌ | ❌ | 正常限流 |

也就是说，REST + MCP 分类虽然会解析 JSON-RPC 并设置 `MCP_METHOD`，但限流入口仍按普通 REST 处理，`initialize`、`tools/list` 等也会被计入配额。

Blocking 检查那块倒是已经按 `MCP_METHOD` 做了区分（不依赖 `API_TYPE_MCP`），所以 blocking 和主限流逻辑之前是不一致的。

## 已做的修复

已将 `handleRequest()` 改为与 blocking 逻辑一致，基于 `McpInitHandler` 设置的属性判断，不再依赖 `API_TYPE_MCP`：

1. 存在 `MCP_METHOD` 且不是 `tools/call` → 跳过限流（覆盖标准 MCP + REST/MCP 分类）
2. 存在 `isMcp` 但没有 `MCP_METHOD`（如 Streamable HTTP 的 `GET /mcp`）→ 跳过限流
3. `MCP_METHOD == tools/call` → 正常限流

修复后两类 API 行为一致：

| MCP 方法 | 是否限流 |
|---|---|
| `tools/call` | ✅ 是 |
| `initialize` / `tools/list` / `ping` 等 | ❌ 否 |
| `GET /mcp`（SSE 通道） | ❌ 否 |

如果你还需要把 `Utils.isMCPRequest()` 等认证相关逻辑也扩展到 REST/MCP 分类场景，可以说一下，我可以继续排查。

