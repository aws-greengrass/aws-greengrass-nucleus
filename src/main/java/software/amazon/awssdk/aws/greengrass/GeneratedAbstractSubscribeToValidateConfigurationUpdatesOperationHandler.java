/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.awssdk.aws.greengrass;

import software.amazon.awssdk.aws.greengrass.model.SubscribeToValidateConfigurationUpdatesRequest;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToValidateConfigurationUpdatesResponse;
import software.amazon.awssdk.aws.greengrass.model.ValidateConfigurationUpdateEvents;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandler;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandlerContext;
import software.amazon.awssdk.eventstreamrpc.OperationModelContext;
import software.amazon.awssdk.eventstreamrpc.model.EventStreamJsonMessage;

public abstract class GeneratedAbstractSubscribeToValidateConfigurationUpdatesOperationHandler
        extends
            OperationContinuationHandler<SubscribeToValidateConfigurationUpdatesRequest, SubscribeToValidateConfigurationUpdatesResponse, EventStreamJsonMessage, ValidateConfigurationUpdateEvents> {
    protected GeneratedAbstractSubscribeToValidateConfigurationUpdatesOperationHandler(
            OperationContinuationHandlerContext context) {
        super(context);
    }

    @Override
    public OperationModelContext<SubscribeToValidateConfigurationUpdatesRequest, SubscribeToValidateConfigurationUpdatesResponse, EventStreamJsonMessage, ValidateConfigurationUpdateEvents> getOperationModelContext() {
        return GreengrassCoreIPCServiceModel.getSubscribeToValidateConfigurationUpdatesModelContext();
    }
}
