/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
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
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.apimgt.internal.service;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import org.wso2.carbon.apimgt.impl.subscription.cache.SubscriptionDataCacheService;
import org.wso2.carbon.apimgt.impl.utils.APIMgtDBUtil;

import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

/**
 * Readiness probe for subscription validation Redis cache warm state on the control plane.
 */
@Path("/subscription-cache-health")
@Api(value = "Subscription Cache Health", tags = {"Health"})
public class SubscriptionCacheHealthApi {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Subscription cache health", response = Map.class)
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Cache ready or cache disabled with DB reachable"),
            @ApiResponse(code = 503, message = "Cache not ready")
    })
    public Response getSubscriptionCacheHealth() {
        Map<String, Object> body = new LinkedHashMap<>();
        SubscriptionDataCacheService cacheService = SubscriptionDataCacheService.getInstance();
        if (!cacheService.isEnabled()) {
            try (Connection connection = APIMgtDBUtil.getConnection()) {
                body.put("status", connection != null && !connection.isClosed() ? "UP" : "DOWN");
                body.put("cacheEnabled", false);
                if (!"UP".equals(body.get("status"))) {
                    return Response.status(Response.Status.SERVICE_UNAVAILABLE).entity(body).build();
                }
                return Response.ok(body).build();
            } catch (Exception e) {
                body.put("status", "DOWN");
                body.put("cacheEnabled", false);
                body.put("reason", e.getMessage());
                return Response.status(Response.Status.SERVICE_UNAVAILABLE).entity(body).build();
            }
        }
        boolean healthy = cacheService.isCacheHealthy();
        body.put("status", healthy ? "UP" : "DOWN");
        body.put("cacheEnabled", true);
        if (!healthy) {
            body.put("reason", "Redis unreachable or subscription cache not warm");
            return Response.status(Response.Status.SERVICE_UNAVAILABLE).entity(body).build();
        }
        return Response.ok(body).build();
    }
}
