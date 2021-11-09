/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.awssdk.aws.greengrass;

import java.lang.Override;
import software.amazon.awssdk.aws.greengrass.model.UpdateClusterStateRequest;
import software.amazon.awssdk.aws.greengrass.model.UpdateClusterStateResponse;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandler;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandlerContext;
import software.amazon.awssdk.eventstreamrpc.OperationModelContext;
import software.amazon.awssdk.eventstreamrpc.model.EventStreamJsonMessage;

public abstract class GeneratedAbstractUpdateClusterStateOperationHandler extends OperationContinuationHandler<UpdateClusterStateRequest, UpdateClusterStateResponse, EventStreamJsonMessage, EventStreamJsonMessage> {
  protected GeneratedAbstractUpdateClusterStateOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  public OperationModelContext<UpdateClusterStateRequest, UpdateClusterStateResponse, EventStreamJsonMessage, EventStreamJsonMessage> getOperationModelContext(
      ) {
    return GreengrassCoreIPCServiceModel.getUpdateClusterStateModelContext();
  }
}
