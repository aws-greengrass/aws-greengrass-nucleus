/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.awssdk.eventstreamrpc.model;

public class ValidationException extends EventStreamOperationError {
    public static final String ERROR_CODE = "aws#ValidationException";

    public ValidationException(String serviceName, String message) {
        super(serviceName, ERROR_CODE, message);
    }

    /**
     * Returns the named model type. May be used for a header.
     *
     * @return
     */
    @Override
    public String getApplicationModelType() {
        return ERROR_CODE;
    }
}
