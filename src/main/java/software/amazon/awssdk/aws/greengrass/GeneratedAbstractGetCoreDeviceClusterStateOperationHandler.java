/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.awssdk.aws.greengrass;

import software.amazon.awssdk.aws.greengrass.model.GetCoreDeviceClusterStateRequest;
import software.amazon.awssdk.aws.greengrass.model.GetCoreDeviceClusterStateResponse;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandler;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandlerContext;
import software.amazon.awssdk.eventstreamrpc.OperationModelContext;
import software.amazon.awssdk.eventstreamrpc.model.EventStreamJsonMessage;

public abstract class GeneratedAbstractGetCoreDeviceClusterStateOperationHandler extends OperationContinuationHandler<GetCoreDeviceClusterStateRequest, GetCoreDeviceClusterStateResponse, EventStreamJsonMessage, EventStreamJsonMessage> {
  protected GeneratedAbstractGetCoreDeviceClusterStateOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  public OperationModelContext<GetCoreDeviceClusterStateRequest, GetCoreDeviceClusterStateResponse, EventStreamJsonMessage, EventStreamJsonMessage> getOperationModelContext(
      ) {
    return GreengrassCoreIPCServiceModel.getGetCoreDeviceClusterStateModelContext();
  }
}
