/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.awssdk.aws.greengrass;

import software.amazon.awssdk.aws.greengrass.model.GetClientDeviceAuthTokenRequest;
import software.amazon.awssdk.aws.greengrass.model.GetClientDeviceAuthTokenResponse;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandler;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandlerContext;
import software.amazon.awssdk.eventstreamrpc.OperationModelContext;
import software.amazon.awssdk.eventstreamrpc.model.EventStreamJsonMessage;

public abstract class GeneratedAbstractGetClientDeviceAuthTokenOperationHandler
        extends
            OperationContinuationHandler<GetClientDeviceAuthTokenRequest, GetClientDeviceAuthTokenResponse, EventStreamJsonMessage, EventStreamJsonMessage> {
    protected GeneratedAbstractGetClientDeviceAuthTokenOperationHandler(OperationContinuationHandlerContext context) {
        super(context);
    }

    @Override
    public OperationModelContext<GetClientDeviceAuthTokenRequest, GetClientDeviceAuthTokenResponse, EventStreamJsonMessage, EventStreamJsonMessage> getOperationModelContext() {
        return GreengrassCoreIPCServiceModel.getGetClientDeviceAuthTokenModelContext();
    }
}
