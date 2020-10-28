/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.awssdk.eventstreamrpc;

import software.amazon.awssdk.crt.eventstream.ClientConnectionContinuation;

public class OperationResponseHandlerContext {
    final ClientConnectionContinuation continuation;

    public OperationResponseHandlerContext(ClientConnectionContinuation continuation) {
        this.continuation = continuation;
    }

    public ClientConnectionContinuation getContinuation() {
        return continuation;
    }
}
