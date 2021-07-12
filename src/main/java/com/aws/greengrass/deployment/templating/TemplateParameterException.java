/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.templating;

public class TemplateParameterException extends Exception {
    private static final long serialVersionUID = 7301618807263349618L;

    public TemplateParameterException() {
        super();
    }

    public TemplateParameterException(String message) {
        super(message);
    }

    public TemplateParameterException(Throwable e) {
        super(e);
    }

    public TemplateParameterException(String message, Throwable e) {
        super(message, e);
    }
}
