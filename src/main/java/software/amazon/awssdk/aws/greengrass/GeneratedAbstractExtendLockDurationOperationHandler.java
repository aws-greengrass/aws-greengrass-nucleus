/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.awssdk.aws.greengrass;

import software.amazon.awssdk.aws.greengrass.model.ExtendLockDurationRequest;
import software.amazon.awssdk.aws.greengrass.model.ExtendLockDurationResponse;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandler;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandlerContext;
import software.amazon.awssdk.eventstreamrpc.OperationModelContext;
import software.amazon.awssdk.eventstreamrpc.model.EventStreamJsonMessage;

public abstract class GeneratedAbstractExtendLockDurationOperationHandler extends OperationContinuationHandler<ExtendLockDurationRequest, ExtendLockDurationResponse, EventStreamJsonMessage, EventStreamJsonMessage> {
  protected GeneratedAbstractExtendLockDurationOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  public OperationModelContext<ExtendLockDurationRequest, ExtendLockDurationResponse, EventStreamJsonMessage, EventStreamJsonMessage> getOperationModelContext(
      ) {
    return GreengrassCoreIPCServiceModel.getExtendLockDurationModelContext();
  }
}
