/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.templating.exceptions;

import com.aws.greengrass.deployment.templating.TemplateExecutionException;

public class IllegalTemplateDependencyException extends TemplateExecutionException {
    private static final long serialVersionUID = 2405458707367333187L;

    public IllegalTemplateDependencyException(String message) {
        super(message);
    }

    public IllegalTemplateDependencyException(Throwable e) {
        super(e);
    }
}
