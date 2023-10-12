/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.awssdk.aws.greengrass;

import software.amazon.awssdk.aws.greengrass.model.CreateLockRequest;
import software.amazon.awssdk.aws.greengrass.model.CreateLockResponse;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandler;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandlerContext;
import software.amazon.awssdk.eventstreamrpc.OperationModelContext;
import software.amazon.awssdk.eventstreamrpc.model.EventStreamJsonMessage;

public abstract class GeneratedAbstractCreateLockOperationHandler extends OperationContinuationHandler<CreateLockRequest, CreateLockResponse, EventStreamJsonMessage, EventStreamJsonMessage> {
  protected GeneratedAbstractCreateLockOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  public OperationModelContext<CreateLockRequest, CreateLockResponse, EventStreamJsonMessage, EventStreamJsonMessage> getOperationModelContext(
      ) {
    return GreengrassCoreIPCServiceModel.getCreateLockModelContext();
  }
}
