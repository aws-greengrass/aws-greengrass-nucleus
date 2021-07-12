/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.templating.exceptions;

import com.aws.greengrass.deployment.templating.TemplateParameterException;

public class TemplateParameterTypeMismatchException extends TemplateParameterException {
    private static final long serialVersionUID = 1422205784013132313L;

    public TemplateParameterTypeMismatchException(String message) {
        super(message);
    }

    public TemplateParameterTypeMismatchException(String message, Throwable e) {
        super(message, e);
    }
}
