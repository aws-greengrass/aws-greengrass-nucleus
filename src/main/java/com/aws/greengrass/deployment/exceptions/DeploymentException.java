/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.exceptions;

import com.aws.greengrass.deployment.errorcode.DeploymentErrorCode;
import com.aws.greengrass.deployment.errorcode.DeploymentErrorType;
import lombok.Getter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// root class for all deployment exceptions hosting error codes
@SuppressWarnings("checkstyle:MissingJavadocMethod")
public class DeploymentException extends Exception {
    static final long serialVersionUID = -3387516993124229948L;
    // A map between Exception class name and error code, which assigns specific error codes to external exceptions.
    // For example, when the following chain of exception is thrown: InvalidRequestException -> JsonMappingException.
    // InvalidRequestException translates to DEPLOYMENT_DOCUMENT_NOT_VALID;
    // JsonMappingException is an external exception, which translates to IO_ERROR, IO_MAPPING_ERROR;
    // we want to add another error code to describe the mapping error at the end of stack.
    // We store JsonMappingException : DEPLOYMENT_DOCUMENT_PARSE_ERROR in the error context,
    // collect it in DeploymentErrorCodeUtils.translateExceptionToErrorCode,
    // and add it to the end of translation of JsonMappingException
    // JsonMappingException -> IO_ERROR, IO_MAPPING_ERROR, DEPLOYMENT_DOCUMENT_PARSE_ERROR
    @Getter
    protected final Map<String, DeploymentErrorCode> errorContext = new HashMap<>();
    @Getter
    protected final List<DeploymentErrorCode> errorCodes = new ArrayList<>();
    @Getter
    protected final List<DeploymentErrorType> errorTypes = new ArrayList<>();

    public DeploymentException(String message) {
        super(message);
    }

    public DeploymentException(Throwable e) {
        super(e);
    }

    public DeploymentException(String message, Throwable e) {
        super(message, e);
    }

    public DeploymentException(DeploymentErrorCode errorCode) {
        super();
        addErrorCode(errorCode);
    }

    public DeploymentException(String message, DeploymentErrorCode errorCode) {
        super(message);
        addErrorCode(errorCode);
    }

    public DeploymentException(Throwable cause, DeploymentErrorCode errorCode) {
        super(cause);
        addErrorCode(errorCode);
    }

    public DeploymentException(String message, Throwable cause, DeploymentErrorCode errorCode) {
        super(message, cause);
        addErrorCode(errorCode);
    }

    public DeploymentException(List<DeploymentErrorCode> errorCodes) {
        super();
        this.errorCodes.addAll(errorCodes);
    }

    public DeploymentException(String message, List<DeploymentErrorCode> errorCodes,
                               List<DeploymentErrorType> errorTypes) {
        super(message);
        this.errorCodes.addAll(errorCodes);
        this.errorTypes.addAll(errorTypes);
    }

    public DeploymentException withErrorContext(Throwable t, DeploymentErrorCode errorCode) {
        errorContext.putIfAbsent(t.getClass().getSimpleName(), errorCode);
        return this;
    }

    protected void addErrorCode(DeploymentErrorCode errorCode) {
        errorCodes.add(errorCode);
    }

    protected void addErrorType(DeploymentErrorType errorType) {
        errorTypes.add(errorType);
    }
}
