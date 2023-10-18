/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.awssdk.aws.greengrass;

import software.amazon.awssdk.aws.greengrass.model.ClusterStateUpdateEvent;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToCoreDeviceClusterStateUpdatesRequest;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToCoreDeviceClusterStateUpdatesResponse;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandler;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandlerContext;
import software.amazon.awssdk.eventstreamrpc.OperationModelContext;
import software.amazon.awssdk.eventstreamrpc.model.EventStreamJsonMessage;

public abstract class GeneratedAbstractSubscribeToCoreDeviceClusterStateUpdatesOperationHandler extends OperationContinuationHandler<SubscribeToCoreDeviceClusterStateUpdatesRequest, SubscribeToCoreDeviceClusterStateUpdatesResponse, EventStreamJsonMessage, ClusterStateUpdateEvent> {
  protected GeneratedAbstractSubscribeToCoreDeviceClusterStateUpdatesOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  public OperationModelContext<SubscribeToCoreDeviceClusterStateUpdatesRequest, SubscribeToCoreDeviceClusterStateUpdatesResponse, EventStreamJsonMessage, ClusterStateUpdateEvent> getOperationModelContext(
      ) {
    return GreengrassCoreIPCServiceModel.getSubscribeToCoreDeviceClusterStateUpdatesModelContext();
  }
}
