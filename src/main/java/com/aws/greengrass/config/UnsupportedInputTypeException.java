/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.config;

public class UnsupportedInputTypeException extends Exception {
    static final long serialVersionUID = -3387516993124229948L;

    public UnsupportedInputTypeException(Class<?> clazz) {
        super("Unsupported input type " + clazz.getName());
    }
}
