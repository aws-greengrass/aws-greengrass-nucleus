/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.awssdk.aws.greengrass;

import software.amazon.awssdk.aws.greengrass.model.GetComponentDetailsRequest;
import software.amazon.awssdk.aws.greengrass.model.GetComponentDetailsResponse;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandler;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandlerContext;
import software.amazon.awssdk.eventstreamrpc.OperationModelContext;
import software.amazon.awssdk.eventstreamrpc.model.EventStreamJsonMessage;

public abstract class GeneratedAbstractGetComponentDetailsOperationHandler
        extends
            OperationContinuationHandler<GetComponentDetailsRequest, GetComponentDetailsResponse, EventStreamJsonMessage, EventStreamJsonMessage> {
    protected GeneratedAbstractGetComponentDetailsOperationHandler(OperationContinuationHandlerContext context) {
        super(context);
    }

    @Override
    public OperationModelContext<GetComponentDetailsRequest, GetComponentDetailsResponse, EventStreamJsonMessage, EventStreamJsonMessage> getOperationModelContext() {
        return GreengrassCoreIPCServiceModel.getGetComponentDetailsModelContext();
    }
}
