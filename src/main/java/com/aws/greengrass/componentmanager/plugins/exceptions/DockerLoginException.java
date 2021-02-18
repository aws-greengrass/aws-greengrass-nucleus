/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */


package com.aws.greengrass.componentmanager.plugins.exceptions;

import com.aws.greengrass.componentmanager.exceptions.PackageDownloadException;

public class DockerLoginException extends PackageDownloadException {
    static final long serialVersionUID = -3387516993124229948L;

    public DockerLoginException(String message) {
        super(message);
    }

    public DockerLoginException(String message, Throwable cause) {
        super(message, cause);
    }
}