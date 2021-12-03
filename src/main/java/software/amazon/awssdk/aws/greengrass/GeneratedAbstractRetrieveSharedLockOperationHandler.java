/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.awssdk.aws.greengrass;

import java.lang.Override;
import software.amazon.awssdk.aws.greengrass.model.RetrieveSharedLockRequest;
import software.amazon.awssdk.aws.greengrass.model.SharedLockConsistentResponse;
import software.amazon.awssdk.aws.greengrass.model.SharedLockResponse;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandler;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandlerContext;
import software.amazon.awssdk.eventstreamrpc.OperationModelContext;
import software.amazon.awssdk.eventstreamrpc.model.EventStreamJsonMessage;

public abstract class GeneratedAbstractRetrieveSharedLockOperationHandler extends OperationContinuationHandler<RetrieveSharedLockRequest, SharedLockResponse, EventStreamJsonMessage, SharedLockConsistentResponse> {
  protected GeneratedAbstractRetrieveSharedLockOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  public OperationModelContext<RetrieveSharedLockRequest, SharedLockResponse, EventStreamJsonMessage, SharedLockConsistentResponse> getOperationModelContext(
      ) {
    return GreengrassCoreIPCServiceModel.getRetrieveSharedLockModelContext();
  }
}
