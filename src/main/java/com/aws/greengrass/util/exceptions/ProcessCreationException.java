/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.exceptions;

import java.io.IOException;

public class ProcessCreationException extends IOException {
    static final long serialVersionUID = -3387516993124229948L;

    public ProcessCreationException(String message) {
        super(message);
    }

    public ProcessCreationException(String message, Throwable cause) {
        super(message, cause);
    }
}
