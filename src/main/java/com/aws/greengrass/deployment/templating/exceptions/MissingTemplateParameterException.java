/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.templating.exceptions;

import com.aws.greengrass.deployment.templating.TemplateParameterException;

public class MissingTemplateParameterException extends TemplateParameterException {
    private static final long serialVersionUID = 7402287373413632784L;

    public MissingTemplateParameterException(String message) {
        super(message);
    }

    public MissingTemplateParameterException(String message, Throwable e) {
        super(message, e);
    }
}
