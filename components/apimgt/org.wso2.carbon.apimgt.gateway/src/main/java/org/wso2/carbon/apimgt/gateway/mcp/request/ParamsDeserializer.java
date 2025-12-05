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

import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ParamsDeserializer implements JsonDeserializer<Params> {
    private static final Log log = LogFactory.getLog(ParamsDeserializer.class);

    @Override
    public Params deserialize(JsonElement json, Type type, JsonDeserializationContext context)
            throws JsonParseException {
        Params params = new Params();

        if (json != null && !json.isJsonNull()) {
            if (!json.isJsonObject()) {
                throw new JsonParseException("params must be a JSON object");
            }
            JsonObject obj = json.getAsJsonObject();
            JsonElement argsElement = obj.get("arguments");

            // Iterate through the json object and convert to native Java types
            if (argsElement == null || argsElement.isJsonNull()) {
                params.setArguments(new HashMap<>());
            } else if (!argsElement.isJsonObject()) {
                throw new JsonParseException("'arguments' must be a JSON object");
            } else {
                Map<String, Object> arguments = convertJsonObjectToMap(argsElement.getAsJsonObject());
                params.setArguments(arguments);
            }

            // Let Gson handle the rest
            params.setProtocolVersion(context.deserialize(obj.get("protocolVersion"), String.class));
            params.setCapabilities(context.deserialize(obj.get("capabilities"), Params.Capabilities.class));
            params.setClientInfo(context.deserialize(obj.get("clientInfo"), Params.ClientInfo.class));
            params.setToolName(context.deserialize(obj.get("name"), String.class));
            params.setCursor(context.deserialize(obj.get("cursor"), String.class));
        }
        return params;
    }

    /**
     * Recursively converts a JsonObject to a Map with native Java types.
     *
     * @param jsonObject the JsonObject to convert
     * @return a Map containing native Java types
     */
    private Map<String, Object> convertJsonObjectToMap(JsonObject jsonObject) {
        Map<String, Object> map = new HashMap<>();
        for (String key : jsonObject.keySet()) {
            JsonElement value = jsonObject.get(key);
            map.put(key, convertJsonElementToObject(value));
        }
        return map;
    }

    /**
     * Recursively converts a JsonArray to a List with native Java types.
     *
     * @param jsonArray the JsonArray to convert
     * @return a List containing native Java types
     */
    private List<Object> convertJsonArrayToList(JsonArray jsonArray) {
        List<Object> list = new ArrayList<>();
        for (JsonElement element : jsonArray) {
            list.add(convertJsonElementToObject(element));
        }
        return list;
    }

    /**
     * Converts a JsonElement to a native Java object.
     * Handles primitives, arrays, objects, and null values recursively.
     *
     * @param element the JsonElement to convert
     * @return a native Java object (String, Number, Boolean, List, Map, or null)
     */
    private Object convertJsonElementToObject(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        } else if (element.isJsonPrimitive()) {
            JsonPrimitive prim = element.getAsJsonPrimitive();
            if (prim.isString()) {
                return prim.getAsString();
            } else if (prim.isBoolean()) {
                return prim.getAsBoolean();
            } else if (prim.isNumber()) {
                // Distinguish between int and double
                Number num = prim.getAsNumber();
                if (num.doubleValue() == num.longValue()) {
                    return num.longValue();
                } else {
                    return num.doubleValue();
                }
            }
            return prim.getAsString();
        } else if (element.isJsonArray()) {
            return convertJsonArrayToList(element.getAsJsonArray());
        } else if (element.isJsonObject()) {
            return convertJsonObjectToMap(element.getAsJsonObject());
        }
        return null;
    }
}
