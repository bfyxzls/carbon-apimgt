/*
 *Copyright (c) 2024, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *WSO2 Inc. licenses this file to you under the Apache License,
 *Version 2.0 (the "License"); you may not use this file except
 *in compliance with the License.
 *You may obtain a copy of the License at
 *
 *http://www.apache.org/licenses/LICENSE-2.0
 *
 *Unless required by applicable law or agreed to in writing,
 *software distributed under the License is distributed on an
 *"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *KIND, either express or implied.  See the License for the
 *specific language governing permissions and limitations
 *under the License.
 */

package org.wso2.carbon.apimgt.impl.handlers;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.identity.base.IdentityRuntimeException;
import org.wso2.carbon.identity.core.bean.context.MessageContext;
import org.wso2.carbon.identity.core.handler.InitConfig;
import org.wso2.carbon.identity.core.util.IdentityTenantUtil;
import org.wso2.carbon.identity.event.IdentityEventConstants;
import org.wso2.carbon.identity.event.IdentityEventException;
import org.wso2.carbon.identity.event.event.Event;
import org.wso2.carbon.identity.event.handler.AbstractEventHandler;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * This handler synchronizes federated users from idn_auth_user table to um_user table
 * when users authenticate via JWT bearer grant type for the first time.
 */
public class FederatedUserSyncHandler extends AbstractEventHandler {

    private static final Log log = LogFactory.getLog(FederatedUserSyncHandler.class);
    private static final int HIGH_PRIORITY = 200;
    private static final String SHARED_DB_JNDI_NAME = "jdbc/SHARED_DB";
    private static final String CHECK_USER_IN_UM_USER_SQL =
            "SELECT UM_USER_NAME FROM UM_USER WHERE UM_USER_NAME = ? AND UM_TENANT_ID = ?";
    private static final String INSERT_USER_TO_UM_USER_SQL =
            "INSERT INTO UM_USER (UM_USER_ID, UM_USER_NAME, UM_USER_PASSWORD, UM_SALT_VALUE, " +
                    "UM_REQUIRE_CHANGE, UM_CHANGED_TIME, UM_TENANT_ID) VALUES (?, ?, ?, ?, ?, ?, ?)";

    @Override
    public String getName() {
        return "federatedUserSyncHandler";
    }

    @Override
    public void handleEvent(Event event) throws IdentityEventException {
        String eventName = event.getEventName();

        // Log all events to debug which events are triggered during JWT bearer grant type authentication
        log.info("FederatedUserSyncHandler handleEvent - Event Name: " + eventName);

        // Log all event properties for debugging
        if (event.getEventProperties() != null) {
            log.info("FederatedUserSyncHandler - Event Properties: " + event.getEventProperties().toString());
        }

        // Process events that contain user information
        // JWT bearer grant type may trigger different events than POST_AUTHENTICATION
        // So we check for any event that contains user information
        boolean shouldProcess = false;

        // Always check if event contains user information
        Object userNameObj = event.getEventProperties() != null ?
                event.getEventProperties().get(IdentityEventConstants.EventProperty.USER_NAME) : null;
        String userName = userNameObj != null ? userNameObj.toString() : null;

        // Also try to get user from other possible event properties (for token generation events)
        // Some events may store user information in different properties
        if (StringUtils.isEmpty(userName) && event.getEventProperties() != null) {
            // Try common property names that might contain username
            for (String propName : new String[]{"username", "user", "subject", "sub"}) {
                Object propValue = event.getEventProperties().get(propName);
                if (propValue != null) {
                    userName = propValue.toString();
                    log.debug("Found user name in property: " + propName);
                    break;
                }
            }
        }

        // Process if we have user information, regardless of event type
        // This ensures we catch JWT bearer grant type token requests
        if (StringUtils.isNotEmpty(userName)) {
            shouldProcess = true;
        } else {
            log.debug("FederatedUserSyncHandler - Event " + eventName + " does not contain user information, skipping");
        }

        if (shouldProcess) {
            try {
                String tenantDomain = (String) event.getEventProperties()
                        .get(IdentityEventConstants.EventProperty.TENANT_DOMAIN);

                if (StringUtils.isEmpty(tenantDomain) || StringUtils.isEmpty(userName)) {
                    if (log.isDebugEnabled()) {
                        log.debug("Tenant domain or user name is empty in " + eventName + " event, skipping");
                    }
                    return;
                }

                if (log.isDebugEnabled()) {
                    log.debug("Processing " + eventName + " event for user: " + userName +
                            " in tenant: " + tenantDomain);
                }

                // Check if user is a federated user (exists in idn_auth_user) and sync to um_user
                syncFederatedUserToUMUser(tenantDomain, userName);
            } catch (Exception e) {
                log.error("Error while synchronizing federated user to UM_USER table", e);
                // Don't throw exception to avoid breaking the authentication flow
            }
        }
    }

    /**
     * Synchronize federated user from idn_auth_user to um_user table
     *
     * @param tenantDomain tenant domain
     * @param userName username (should be the sub claim from JWT, i.e., the federated user ID)
     * @throws IdentityEventException if error occurs during synchronization
     */
    private void syncFederatedUserToUMUser(String tenantDomain, String userName)
            throws IdentityEventException {

        if (StringUtils.isEmpty(tenantDomain) || StringUtils.isEmpty(userName)) {
            if (log.isDebugEnabled()) {
                log.debug("Tenant domain or user name is empty, skipping user sync");
            }
            return;
        }

        Connection sharedDbConnection = null;
        PreparedStatement checkIdnAuthUserStmt = null;
        PreparedStatement checkUmUserStmt = null;
        PreparedStatement insertUmUserStmt = null;
        ResultSet rs = null;

        try {
            int tenantId = IdentityTenantUtil.getTenantId(tenantDomain);

            // Start tenant flow
            PrivilegedCarbonContext.startTenantFlow();
            PrivilegedCarbonContext carbonContext = PrivilegedCarbonContext.getThreadLocalCarbonContext();
            carbonContext.setTenantId(tenantId);
            carbonContext.setTenantDomain(tenantDomain);

            // Get shared database connection
            sharedDbConnection = getSharedDatabaseConnection();

            // Check if user already exists in um_user table
            checkUmUserStmt = sharedDbConnection.prepareStatement(CHECK_USER_IN_UM_USER_SQL);
            checkUmUserStmt.setString(1, userName);
            checkUmUserStmt.setInt(2, tenantId);
            rs = checkUmUserStmt.executeQuery();

            if (rs.next()) {
                if (log.isDebugEnabled()) {
                    log.debug("User " + userName + " already exists in UM_USER table, skipping sync");
                }
                return;
            }

            // Close the result set before inserting
            rs.close();
            rs = null;

            // Insert user into um_user table
            // Generate a unique user ID
            String userId = UUID.randomUUID().toString();
            // Use a default password hash (federated users don't have passwords in WSO2)
            // This is a placeholder password hash that won't be used for authentication
            String defaultPassword = "FEDERATED_USER_PASSWORD_PLACEHOLDER";
            String saltValue = null; // No salt for federated users
            boolean requireChange = false;
            long currentTime = System.currentTimeMillis();

            insertUmUserStmt = sharedDbConnection.prepareStatement(INSERT_USER_TO_UM_USER_SQL);
            insertUmUserStmt.setString(1, userId);
            insertUmUserStmt.setString(2, userName);
            insertUmUserStmt.setString(3, defaultPassword);
            insertUmUserStmt.setString(4, saltValue);
            insertUmUserStmt.setBoolean(5, requireChange);
            insertUmUserStmt.setTimestamp(6, new java.sql.Timestamp(currentTime));
            insertUmUserStmt.setInt(7, tenantId);

            int rowsInserted = insertUmUserStmt.executeUpdate();

            if (rowsInserted > 0) {
                if (log.isInfoEnabled()) {
                    log.info("Successfully synchronized federated user " + userName +
                            " from IDN_AUTH_USER to UM_USER table in tenant: " + tenantDomain);
                }
            } else {
                log.warn("Failed to insert user " + userName + " into UM_USER table");
            }

        } catch (SQLException e) {
            log.error("SQL error while synchronizing user " + userName + " to UM_USER table", e);
            throw new IdentityEventException("Error while synchronizing user to UM_USER table", e);
        } catch (Exception e) {
            log.error("Error while synchronizing user " + userName + " to UM_USER table", e);
            throw new IdentityEventException("Error while synchronizing user to UM_USER table", e);
        } finally {
            closeResultSet(rs);
            closeStatement(checkIdnAuthUserStmt);
            closeStatement(checkUmUserStmt);
            closeStatement(insertUmUserStmt);
            closeConnection(sharedDbConnection);
            PrivilegedCarbonContext.endTenantFlow();
        }
    }

    /**
     * Get shared database connection
     *
     * @return Connection to shared database
     * @throws SQLException if error occurs while getting connection
     */
    private Connection getSharedDatabaseConnection() throws SQLException {
        try {
            Context ctx = new InitialContext();
            DataSource dataSource = (DataSource) ctx.lookup(SHARED_DB_JNDI_NAME);
            if (dataSource == null) {
                throw new SQLException("Shared database datasource not found: " + SHARED_DB_JNDI_NAME);
            }
            return dataSource.getConnection();
        } catch (NamingException e) {
            throw new SQLException("Error while looking up shared database datasource: " +
                    SHARED_DB_JNDI_NAME, e);
        }
    }

    /**
     * Close database connection
     */
    private void closeConnection(Connection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                log.warn("Error while closing database connection", e);
            }
        }
    }

    /**
     * Close prepared statement
     */
    private void closeStatement(PreparedStatement statement) {
        if (statement != null) {
            try {
                statement.close();
            } catch (SQLException e) {
                log.warn("Error while closing prepared statement", e);
            }
        }
    }

    /**
     * Close result set
     */
    private void closeResultSet(ResultSet resultSet) {
        if (resultSet != null) {
            try {
                resultSet.close();
            } catch (SQLException e) {
                log.warn("Error while closing result set", e);
            }
        }
    }

    @Override
    public void init(InitConfig configuration) throws IdentityRuntimeException {
        log.info("FederatedUserSyncHandler init() called");
        super.init(configuration);
    }

    /**
     * Override isEnabled to always return true, so the handler is activated even without configuration.
     * This prevents the "Properties for federatedUserSyncHandler is not configured" warning.
     */
    @Override
    public boolean isEnabled(@SuppressWarnings("rawtypes") MessageContext messageContext) {
        // Always enable this handler, even without explicit configuration
        return true;
    }

    @Override
    public int getPriority(@SuppressWarnings("rawtypes") MessageContext messageContext) {
        return HIGH_PRIORITY;
    }
}
