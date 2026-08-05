/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.awssdk.aws.greengrass;

import java.lang.Override;
import software.amazon.awssdk.aws.greengrass.model.IoTCoreConnectionStatusEvent;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToIoTCoreConnectionStatusRequest;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToIoTCoreConnectionStatusResponse;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandler;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandlerContext;
import software.amazon.awssdk.eventstreamrpc.OperationModelContext;
import software.amazon.awssdk.eventstreamrpc.model.EventStreamJsonMessage;

public abstract class GeneratedAbstractSubscribeToIoTCoreConnectionStatusOperationHandler extends OperationContinuationHandler<SubscribeToIoTCoreConnectionStatusRequest, SubscribeToIoTCoreConnectionStatusResponse, EventStreamJsonMessage, IoTCoreConnectionStatusEvent> {
  protected GeneratedAbstractSubscribeToIoTCoreConnectionStatusOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  public OperationModelContext<SubscribeToIoTCoreConnectionStatusRequest, SubscribeToIoTCoreConnectionStatusResponse, EventStreamJsonMessage, IoTCoreConnectionStatusEvent> getOperationModelContext(
      ) {
    return GreengrassCoreIPCServiceModel.getSubscribeToIoTCoreConnectionStatusModelContext();
  }
}
