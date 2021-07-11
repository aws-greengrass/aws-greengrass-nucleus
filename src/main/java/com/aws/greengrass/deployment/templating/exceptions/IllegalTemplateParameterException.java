/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.templating.exceptions;

import com.aws.greengrass.deployment.templating.TemplateParameterException;

public class IllegalTemplateParameterException extends TemplateParameterException {
    private static final long serialVersionUID = 6638067823749416076L;

    public IllegalTemplateParameterException(String message) {
        super(message);
    }

    public IllegalTemplateParameterException(String message, Throwable e) {
        super(message, e);
    }
}
