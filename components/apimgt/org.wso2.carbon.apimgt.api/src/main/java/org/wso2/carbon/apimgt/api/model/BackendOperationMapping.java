package org.wso2.carbon.apimgt.api.model;

/**
 * Represents a mapping between a backend and its operations.
 * This class encapsulates the backend ID and the corresponding backend operation.
 */
public class BackendOperationMapping {

    private String backendId = null;
    private BackendOperation backendOperation = null;

    public String getBackendId() {

        return backendId;
    }

    public void setBackendId(String backendId) {

        this.backendId = backendId;
    }

    public BackendOperation getBackendOperation() {

        return backendOperation;
    }

    public void setBackendOperation(BackendOperation backendOperation) {

        this.backendOperation = backendOperation;
    }

    @Override
    public String toString() {
        return "BackendOperationMapping {" +
                "backendId='" + backendId + '\'' +
                ", backendOperation=" + backendOperation +
                '}';
    }
}