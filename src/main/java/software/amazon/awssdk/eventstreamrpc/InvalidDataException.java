/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.awssdk.eventstreamrpc;

import software.amazon.awssdk.crt.eventstream.MessageType;

public class InvalidDataException extends RuntimeException {
    public InvalidDataException(MessageType unexpectedType) {
        super(String.format("Unexpected message type recieved: %s", unexpectedType.name()));
    }
}
