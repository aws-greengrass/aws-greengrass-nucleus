/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.awssdk.aws.greengrass;

import software.amazon.awssdk.aws.greengrass.model.VerifyClientDeviceIdentityRequest;
import software.amazon.awssdk.aws.greengrass.model.VerifyClientDeviceIdentityResponse;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandler;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandlerContext;
import software.amazon.awssdk.eventstreamrpc.OperationModelContext;
import software.amazon.awssdk.eventstreamrpc.model.EventStreamJsonMessage;

public abstract class GeneratedAbstractVerifyClientDeviceIdentityOperationHandler
        extends
            OperationContinuationHandler<VerifyClientDeviceIdentityRequest, VerifyClientDeviceIdentityResponse, EventStreamJsonMessage, EventStreamJsonMessage> {
    protected GeneratedAbstractVerifyClientDeviceIdentityOperationHandler(OperationContinuationHandlerContext context) {
        super(context);
    }

    @Override
    public OperationModelContext<VerifyClientDeviceIdentityRequest, VerifyClientDeviceIdentityResponse, EventStreamJsonMessage, EventStreamJsonMessage> getOperationModelContext() {
        return GreengrassCoreIPCServiceModel.getVerifyClientDeviceIdentityModelContext();
    }
}
