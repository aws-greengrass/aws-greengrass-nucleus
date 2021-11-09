/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.awssdk.aws.greengrass;

import java.lang.Override;
import software.amazon.awssdk.aws.greengrass.model.ClusterStateEvents;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToClusterStateEventsRequest;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToClusterStateEventsResponse;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandler;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandlerContext;
import software.amazon.awssdk.eventstreamrpc.OperationModelContext;
import software.amazon.awssdk.eventstreamrpc.model.EventStreamJsonMessage;

public abstract class GeneratedAbstractSubscribeToClusterStateEventsOperationHandler extends OperationContinuationHandler<SubscribeToClusterStateEventsRequest, SubscribeToClusterStateEventsResponse, EventStreamJsonMessage, ClusterStateEvents> {
  protected GeneratedAbstractSubscribeToClusterStateEventsOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  public OperationModelContext<SubscribeToClusterStateEventsRequest, SubscribeToClusterStateEventsResponse, EventStreamJsonMessage, ClusterStateEvents> getOperationModelContext(
      ) {
    return GreengrassCoreIPCServiceModel.getSubscribeToClusterStateEventsModelContext();
  }
}
