/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.resources;

public class ResourceNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;
    
    ResourceNotFoundException(String message, Throwable throwable) {
        super(message, throwable);
    }
}
