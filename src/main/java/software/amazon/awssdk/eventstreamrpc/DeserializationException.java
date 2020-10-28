/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.awssdk.eventstreamrpc;

public class DeserializationException extends RuntimeException {
    public DeserializationException(Object lexicalData) {
        this(lexicalData, null);
    }

    public DeserializationException(Object lexicalData, Throwable cause) {
        super("Could not deserialize data: [" + lexicalData.toString() + "]", cause);
    }
}
