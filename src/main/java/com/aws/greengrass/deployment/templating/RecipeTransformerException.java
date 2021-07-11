/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.templating;

public class RecipeTransformerException extends Exception {
    private static final long serialVersionUID = -5983084851017096286L;

    public RecipeTransformerException(String message) {
        super(message);
    }

    public RecipeTransformerException(String message, Throwable e) {
        super(message, e);
    }
}
