/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.awssdk.aws.greengrass;

import software.amazon.awssdk.aws.greengrass.model.ComponentUpdatePolicyEvents;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToComponentUpdatesRequest;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToComponentUpdatesResponse;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandler;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandlerContext;
import software.amazon.awssdk.eventstreamrpc.OperationModelContext;
import software.amazon.awssdk.eventstreamrpc.model.EventStreamJsonMessage;

public abstract class GeneratedAbstractSubscribeToComponentUpdatesOperationHandler extends OperationContinuationHandler<SubscribeToComponentUpdatesRequest, SubscribeToComponentUpdatesResponse, EventStreamJsonMessage, ComponentUpdatePolicyEvents> {
  protected GeneratedAbstractSubscribeToComponentUpdatesOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  public OperationModelContext<SubscribeToComponentUpdatesRequest, SubscribeToComponentUpdatesResponse, EventStreamJsonMessage, ComponentUpdatePolicyEvents> getOperationModelContext(
      ) {
    return GreengrassCoreIPCServiceModel.getSubscribeToComponentUpdatesModelContext();
  }
}
