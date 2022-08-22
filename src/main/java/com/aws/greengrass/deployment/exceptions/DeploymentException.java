/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.exceptions;

import com.aws.greengrass.deployment.errorcode.DeploymentErrorCode;
import lombok.Getter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DeploymentException extends Exception {
    static final long serialVersionUID = -3387516993124229948L;

    @Getter
    protected final List<DeploymentErrorCode> errorCodes = new ArrayList<>();

    @Getter
    protected final Map<Class<? extends Throwable>, DeploymentErrorCode> errorContext = new HashMap<>();


    public DeploymentException(List<DeploymentErrorCode> errorCodes) {
        super();
        this.errorCodes.addAll(errorCodes);
    }

    public DeploymentException(String message) {
        super(message);
    }

    public DeploymentException(Throwable e) {
        super(e);
    }

    public DeploymentException(String message, Throwable e) {
        super(message, e);
    }

    public DeploymentException(String message, List<DeploymentErrorCode> errorCodes) {
        super(message);
        this.errorCodes.addAll(errorCodes);
    }

    public DeploymentException(Throwable e, List<DeploymentErrorCode> errorCodes) {
        super(e);
        this.errorCodes.addAll(errorCodes);
    }

    public DeploymentException(DeploymentErrorCode errorCode) {
        super();
        this.errorCodes.add(errorCode);
    }

    public DeploymentException(String message, DeploymentErrorCode errorCode) {
        super(message);
        this.errorCodes.add(errorCode);
    }

    public DeploymentException(String message, Throwable cause, DeploymentErrorCode errorCode) {
        super(message, cause);
        this.errorCodes.add(errorCode);
    }

    public DeploymentException(String message, Throwable cause, List<DeploymentErrorCode> errorCodes) {
        super(message, cause);
        this.errorCodes.addAll(errorCodes);
    }

    public DeploymentException(Throwable cause, DeploymentErrorCode errorCode) {
        super(cause);
        this.errorCodes.add(errorCode);
    }

    public DeploymentException withErrorContext(Class<? extends Throwable> clazz, DeploymentErrorCode errorCode) {
        errorContext.putIfAbsent(clazz, errorCode);
        return this;
    }
}
