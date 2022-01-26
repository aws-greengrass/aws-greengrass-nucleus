/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.awssdk.aws.greengrass;

import software.amazon.awssdk.aws.greengrass.model.ConfigurationUpdateEvents;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToConfigurationUpdateRequest;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToConfigurationUpdateResponse;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandler;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandlerContext;
import software.amazon.awssdk.eventstreamrpc.OperationModelContext;
import software.amazon.awssdk.eventstreamrpc.model.EventStreamJsonMessage;

public abstract class GeneratedAbstractSubscribeToConfigurationUpdateOperationHandler extends OperationContinuationHandler<SubscribeToConfigurationUpdateRequest, SubscribeToConfigurationUpdateResponse, EventStreamJsonMessage, ConfigurationUpdateEvents> {
  protected GeneratedAbstractSubscribeToConfigurationUpdateOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  public OperationModelContext<SubscribeToConfigurationUpdateRequest, SubscribeToConfigurationUpdateResponse, EventStreamJsonMessage, ConfigurationUpdateEvents> getOperationModelContext(
      ) {
    return GreengrassCoreIPCServiceModel.getSubscribeToConfigurationUpdateModelContext();
  }
}
