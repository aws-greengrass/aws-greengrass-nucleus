/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.templating;

public class TemplateExecutionException extends Exception {
    private static final long serialVersionUID = 8270613415461482760L;

    public TemplateExecutionException(String message) {
        super(message);
    }

    public TemplateExecutionException(Throwable e) {
        super(e);
    }

    public TemplateExecutionException(String message, Throwable e) {
        super(message, e);
    }
}
