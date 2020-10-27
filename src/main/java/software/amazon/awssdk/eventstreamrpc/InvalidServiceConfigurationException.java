/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.awssdk.eventstreamrpc;

public class InvalidServiceConfigurationException extends RuntimeException {
    public InvalidServiceConfigurationException(String msg) {
        super(msg);
    }

    public InvalidServiceConfigurationException(String msg, Throwable cause) {
        super(msg, cause);
    }

    public InvalidServiceConfigurationException(Throwable cause) {
        super(cause);
    }
}
