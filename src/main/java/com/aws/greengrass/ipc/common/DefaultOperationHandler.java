/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.ipc.common;

import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import software.amazon.awssdk.aws.greengrass.model.ServiceError;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandler;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandlerContext;
import software.amazon.awssdk.eventstreamrpc.OperationModelContext;
import software.amazon.awssdk.eventstreamrpc.model.EventStreamJsonMessage;

public class DefaultOperationHandler extends OperationContinuationHandler<EventStreamJsonMessage,
        EventStreamJsonMessage, EventStreamJsonMessage, EventStreamJsonMessage> {
    private static final Logger LOGGER = LogManager.getLogger(DefaultOperationHandler.class.getName());
    private final OperationModelContext operationModelContext;

    public DefaultOperationHandler(final OperationModelContext modelContext,
                                   final OperationContinuationHandlerContext context) {
        super(context);
        this.operationModelContext = modelContext;
    }

    @Override
    public OperationModelContext<EventStreamJsonMessage, EventStreamJsonMessage,
            EventStreamJsonMessage, EventStreamJsonMessage> getOperationModelContext() {
        return operationModelContext;
    }

    /**
     * Called when the underlying continuation is closed. Gives operations a chance to cleanup whatever
     * resources may be on the other end of an open stream. Also invoked when an underlying ServerConnection
     * is closed associated with the stream/continuation
     */
    @Override
    protected void onStreamClosed() {
        LOGGER.atDebug().log("Stream closed for operation {}", operationModelContext.getOperationName());
    }

    @Override
    public EventStreamJsonMessage handleRequest(EventStreamJsonMessage request) {
        LOGGER.atDebug().log("Request received for unsupported operation {}",
                operationModelContext.getOperationName());
        throw new ServiceError(String.format("Operation %s is not supported by Greengrass",
                operationModelContext.getOperationName()));
    }

    @Override
    public void handleStreamEvent(EventStreamJsonMessage streamRequestEvent) {
        LOGGER.atDebug().log("Event received on stream for operation {}",
                operationModelContext.getOperationName());
        throw new ServiceError(String.format("Operation %s is not supported by Greengrass",
                operationModelContext.getOperationName()));
    }
}
