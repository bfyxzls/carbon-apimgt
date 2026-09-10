/*
 * Copyright (c) 2025, WSO2 LLC. (http://www.wso2.com).
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

package org.wso2.carbon.apimgt.gateway.mcp.request;


import com.google.gson.annotations.SerializedName;
import org.wso2.carbon.apimgt.impl.APIConstants.MCP;

import java.util.Map;

/**
 * This class is used to represent the MCP request.
 */
public class McpRequest {
    @SerializedName("jsonrpc")
    private final String jsonRpcVersion = MCP.RpcConstants.JSON_RPC_VERSION;
    @SerializedName("id")
    private Object id;
    @SerializedName("method")
    private String method;
    @SerializedName("params")
    private Params params;
    @SerializedName("_meta")
    private Map<String, Object> meta;

    public Params getParams() {
        return params;
    }

    public void setParams(Params params) {
        this.params = params;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public McpRequest(Object id) {
        this.id = id;
    }

    public String getJsonRpcVersion() {
        return jsonRpcVersion;
    }

    public Object getId() {
        return id;
    }

    public void setId(Object id) {
        this.id = id;
    }

    public Map<String, Object> getMeta() {
        return meta;
    }

    public void setMeta(Map<String, Object> meta) {
        this.meta = meta;
    }

    /**
     * Returns protocol version from {@code _meta}, preferring the MCP 2.0 namespaced key
     * {@code io.modelcontextprotocol/protocolVersion}, then the legacy plain {@code protocolVersion}.
     */
    public String getMetaProtocolVersion() {
        if (meta == null) {
            return null;
        }
        Object namespaced = meta.get(MCP.META_PROTOCOL_VERSION_KEY);
        if (namespaced != null) {
            return String.valueOf(namespaced);
        }
        Object value = meta.get(MCP.PROTOCOL_VERSION_KEY);
        return value != null ? String.valueOf(value) : null;
    }
}
