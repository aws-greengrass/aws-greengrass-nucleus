/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.packagemanager.exceptions;

public class InvalidArtifactUriException extends PackagingException {
    static final long serialVersionUID = -3387516993124229948L;

    public InvalidArtifactUriException(String message) {
        super(message);
    }

    public InvalidArtifactUriException(String message, Throwable cause) {
        super(message, cause);
    }
}
