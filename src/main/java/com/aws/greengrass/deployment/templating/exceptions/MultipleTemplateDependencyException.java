/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.templating.exceptions;

import com.aws.greengrass.deployment.templating.TemplateExecutionException;

public class MultipleTemplateDependencyException extends TemplateExecutionException {
    private static final long serialVersionUID = 4897628950643037974L;

    public MultipleTemplateDependencyException(String message) {
        super(message);
    }

    public MultipleTemplateDependencyException(Throwable e) {
        super(e);
    }
}
