/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.awssdk.aws.greengrass;

import java.lang.Override;
import software.amazon.awssdk.aws.greengrass.model.CreateSharedLockRequest;
import software.amazon.awssdk.aws.greengrass.model.SharedLockConsistentResponse;
import software.amazon.awssdk.aws.greengrass.model.SharedLockResponse;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandler;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandlerContext;
import software.amazon.awssdk.eventstreamrpc.OperationModelContext;
import software.amazon.awssdk.eventstreamrpc.model.EventStreamJsonMessage;

public abstract class GeneratedAbstractCreateSharedLockOperationHandler extends OperationContinuationHandler<CreateSharedLockRequest, SharedLockResponse, EventStreamJsonMessage, SharedLockConsistentResponse> {
  protected GeneratedAbstractCreateSharedLockOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  public OperationModelContext<CreateSharedLockRequest, SharedLockResponse, EventStreamJsonMessage, SharedLockConsistentResponse> getOperationModelContext(
      ) {
    return GreengrassCoreIPCServiceModel.getCreateSharedLockModelContext();
  }
}
