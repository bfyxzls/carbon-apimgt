# arguments参数的反序列化

### 解决方案
添加了三个递归转换方法，将所有 Gson 类型转换为原生 Java 类型：

1. **`convertJsonObjectToMap(JsonObject)`** - 将 `JsonObject` 递归转换为 `Map<String, Object>`

2. **`convertJsonArrayToList(JsonArray)`** - 将 `JsonArray` 递归转换为 `List<Object>`

3. **`convertJsonElementToObject(JsonElement)`** - 核心转换方法，处理所有类型：
    - `null` / `JsonNull` → `null`
    - `String` → `String`
    - `Boolean` → `Boolean`
    - `Number` → `Long` 或 `Double`（根据是否有小数部分）
    - `JsonArray` → `List<Object>`（递归调用）
    - `JsonObject` → `Map<String, Object>`（递归调用）

### 处理示例

你的 MCP 请求：
```json
{
  "arguments": {
    "param": {
      "userlaw": [{"title": "民法典", "article_number": "第二十一条"}],
      "answerlaw": [{"title": "民法典", "article_number": "第二十一条", "text": "test"}]
    }
  }
}
```

现在会被转换为：
```java
Map<String, Object> arguments = {
  "param" -> Map<String, Object> {
    "userlaw" -> List<Object> [
      Map<String, Object> {"title" -> "民法典", "article_number" -> "第二十一条"}
    ],
    "answerlaw" -> List<Object> [
      Map<String, Object> {"title" -> "民法典", "article_number" -> "第二十一条", "text" -> "test"}
    ]
  }
}
```

所有值都是原生 Java 类型（`Map`、`List`、`String` 等），Jackson 可以正常序列化它们。

```java
} else if (value != null && (value.isJsonObject() || value.isJsonArray() || value.isJsonNull())) {
    // For complex types, store the JsonElement itself
    arguments.put(key, value);
}
```

```json
{
  "arguments": {
    "param": {
      "userlaw": [{"title": "民法典", "article_number": "第二十一条"}],
      "answerlaw": [{"title": "民法典", "article_number": "第二十一条", "text": "test"}]
    }
  }
}
```

```java
Map<String, Object> arguments = {
  "param" -> Map<String, Object> {
    "userlaw" -> List<Object> [
      Map<String, Object> {"title" -> "民法典", "article_number" -> "第二十一条"}
    ],
    "answerlaw" -> List<Object> [
      Map<String, Object> {"title" -> "民法典", "article_number" -> "第二十一条", "text" -> "test"}
    ]
  }
}
```

---

**User**

这块还需要再进行优化一下，因为我这个arguments字段，它会有对象嵌套的情况，你需要考虑一下我下面的一种复杂的情况，也需要支持一下
"arguments": {
"param": {
"userlaw": [
{
"title": "民法典",
"article_number": "第二十一条"
}
],
"answerlaw": [
{
"title": "民法典",
"article_number": "第二十一条",
"text": "test"
}
]
}
}
},

---

**Cursor**

我刚才的修改已经支持了你描述的这种复杂嵌套情况。让我解释一下为什么它可以正常工作：

你的数据结构是：
```
arguments (Map)
  └── param (Map)
        ├── userlaw (List)
        │     └── [0] (Map)
        │           ├── title: "民法典"
        │           └── article_number: "第二十一条"
        └── answerlaw (List)
              └── [0] (Map)
                    ├── title: "民法典"
                    ├── article_number: "第二十一条"
                    └── text: "test"
```

我的代码中 `convertJsonElementToObject` 方法是**递归调用**的：

```57:70:components/apimgt/org.wso2.carbon.apimgt.gateway/src/main/java/org/wso2/carbon/apimgt/gateway/mcp/request/ParamsDeserializer.java
            } else {
                Map<String, Object> arguments = convertJsonObjectToMap(argsElement.getAsJsonObject());
                params.setArguments(arguments);
            }
```

处理流程：

1. **`arguments`** → `convertJsonObjectToMap()` → 遍历 key `"param"`
2. **`param`** 是 JsonObject → `convertJsonElementToObject()` → `convertJsonObjectToMap()` → 遍历 keys `"userlaw"`, `"answerlaw"`
3. **`userlaw`** 是 JsonArray → `convertJsonElementToObject()` → `convertJsonArrayToList()` → 遍历每个元素
4. **数组中的对象** `{"title":"民法典",...}` → `convertJsonElementToObject()` → `convertJsonObjectToMap()` → 遍历 keys `"title"`, `"article_number"`
5. **`"title"`** 是 String → `convertJsonElementToObject()` → 返回 `"民法典"`

每一层都会递归调用，无论嵌套多深都能正确转换。

让我验证一下当前的代码是否完整：

```plaintext
arguments (Map)
  └── param (Map)
        ├── userlaw (List)
        │     └── [0] (Map)
        │           ├── title: "民法典"
        │           └── article_number: "第二十一条"
        └── answerlaw (List)
              └── [0] (Map)
                    ├── title: "民法典"
                    ├── article_number: "第二十一条"
                    └── text: "test"
```

```java
            } else {
                Map<String, Object> arguments = convertJsonObjectToMap(argsElement.getAsJsonObject());
                params.setArguments(arguments);
            }
```

代码已经是完整的递归实现了。它会将你的复杂嵌套结构转换为：

```java
Map<String, Object> arguments = {
    "param" -> Map<String, Object> {
        "userlaw" -> List<Object> [
            Map<String, Object> {
                "title" -> "民法典",
                "article_number" -> "第二十一条"
            }
        ],
        "answerlaw" -> List<Object> [
            Map<String, Object> {
                "title" -> "民法典",
                "article_number" -> "第二十一条",
                "text" -> "test"
            }
        ]
    }
}
```

所有类型都是原生 Java 类型：
- `Map<String, Object>` 替代 `JsonObject`
- `List<Object>` 替代 `JsonArray`
- `String`、`Long`、`Double`、`Boolean` 替代 `JsonPrimitive`

这样 Jackson 就可以正常序列化了，之前的错误应该不会再出现。你可以重新构建并测试一下。

```java
Map<String, Object> arguments = {
    "param" -> Map<String, Object> {
        "userlaw" -> List<Object> [
            Map<String, Object> {
                "title" -> "民法典",
                "article_number" -> "第二十一条"
            }
        ],
        "answerlaw" -> List<Object> [
            Map<String, Object> {
                "title" -> "民法典",
                "article_number" -> "第二十一条",
                "text" -> "test"
            }
        ]
    }
}
```