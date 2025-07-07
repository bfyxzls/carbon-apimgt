package org.wso2.carbon.apimgt.api.model;


import org.wso2.carbon.apimgt.api.APIConstants;

import java.io.Serializable;

/**
 * Represents a Backend Operation in the API Management system.
 * This class encapsulates the details of a backend operation including its reference URI mapping ID,
 * target endpoint, and HTTP verb.
 */
public class BackendOperation implements Serializable {

    private static final long serialVersionUID = 1L;
    private int refUriMappingId;
    private String target;
    private APIConstants.SupportedHTTPVerbs verb;
    public BackendOperation() {

    }

    public String getTarget() {

        return target;
    }

    public void setTarget(String target) {

        this.target = target;
    }

    public APIConstants.SupportedHTTPVerbs getVerb() {

        return verb;
    }

    public void setVerb(APIConstants.SupportedHTTPVerbs verb) {

        this.verb = verb;
    }

    public int getRefUriMappingId() {

        return refUriMappingId;
    }

    public void setRefUriMappingId(int refUriMappingId) {

        this.refUriMappingId = refUriMappingId;
    }

    @Override
    public String toString() {
        return "BackendOperation {" +
                "target='" + target + '\'' +
                ", verb='" + verb + '\'' +
                '}';
    }
}