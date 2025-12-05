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

package org.wso2.carbon.apimgt.impl.utils;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.identity.core.util.IdentityTenantUtil;

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
 * Utility class for synchronizing federated users from idn_auth_user table to um_user table
 */
public class FederatedUserSyncUtil {

    private static final Log log = LogFactory.getLog(FederatedUserSyncUtil.class);

    private static final String CHECK_USER_IN_UM_USER_SQL =
            "SELECT UM_USER_NAME FROM UM_USER WHERE UM_USER_NAME = ? AND UM_TENANT_ID = ?";

    private static final String INSERT_USER_TO_UM_USER_SQL =
            "INSERT INTO UM_USER (UM_USER_ID, UM_USER_NAME, UM_USER_PASSWORD, UM_SALT_VALUE, " +
                    "UM_REQUIRE_CHANGE, UM_CHANGED_TIME, UM_TENANT_ID) VALUES (?, ?, ?, ?, ?, ?, ?)";

    private static final String INSERT_USER_TO_UM_USER_ROLE_SQL =
            "INSERT INTO UM_HYBRID_USER_ROLE (UM_USER_NAME,UM_ROLE_ID,UM_TENANT_ID,UM_DOMAIN_ID) VALUES (?, ?, ?, ?)";

    /**
     * Synchronize federated user from idn_auth_user to um_user table
     * This method checks if user exists in idn_auth_user and not in um_user, then syncs it
     *
     * @param tenantDomain tenant domain
     * @param userName     username (should be the sub claim from JWT, i.e., the federated user ID)
     */
    public static void syncFederatedUserToUMUser(String tenantDomain, String userName) {

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
            PreparedStatement insertUmUserRoleStmt =
                    sharedDbConnection.prepareStatement(INSERT_USER_TO_UM_USER_ROLE_SQL);
            insertUmUserRoleStmt.setString(1, userName);
            insertUmUserRoleStmt.setInt(2, 5);
            insertUmUserRoleStmt.setInt(3, tenantId);
            insertUmUserRoleStmt.setInt(4, 1);
            insertUmUserRoleStmt.executeUpdate();

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
        } catch (Exception e) {
            log.error("Error while synchronizing user " + userName + " to UM_USER table", e);
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
    private static Connection getSharedDatabaseConnection() throws SQLException {
        try {
            Context ctx = new InitialContext();
            String sharedDbJndiName = "jdbc/SHARED_DB";
            DataSource dataSource = (DataSource) ctx.lookup(sharedDbJndiName);
            if (dataSource == null) {
                throw new SQLException("Shared database datasource not found: " + sharedDbJndiName);
            }
            return dataSource.getConnection();
        } catch (NamingException e) {
            throw new SQLException("Error while looking up shared database datasource: jdbc/SHARED_DB", e);
        }
    }

    /**
     * Close database connection
     *
     * @param connection database connection to close
     */
    private static void closeConnection(Connection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                log.error("Error while closing database connection", e);
            }
        }
    }

    /**
     * Close prepared statement
     *
     * @param statement prepared statement to close
     */
    private static void closeStatement(PreparedStatement statement) {
        if (statement != null) {
            try {
                statement.close();
            } catch (SQLException e) {
                log.error("Error while closing prepared statement", e);
            }
        }
    }

    /**
     * Close result set
     *
     * @param resultSet result set to close
     */
    private static void closeResultSet(ResultSet resultSet) {
        if (resultSet != null) {
            try {
                resultSet.close();
            } catch (SQLException e) {
                log.error("Error while closing result set", e);
            }
        }
    }
}
