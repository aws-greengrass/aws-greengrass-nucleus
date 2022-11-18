/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.plugins.docker.exceptions;

import com.aws.greengrass.componentmanager.exceptions.PackageLoadingException;

@SuppressWarnings("checkstyle:MissingJavadocMethod")
public class DockerImageDeleteException extends PackageLoadingException {
    static final long serialVersionUID = -3387516993124229948L;

    public DockerImageDeleteException(String message) {
        super(message);
    }

    public DockerImageDeleteException(String message, Throwable cause) {
        super(message, cause);
    }
}
