/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.awssdk.aws.greengrass;

import software.amazon.awssdk.aws.greengrass.model.ReleaseSharedPropertyRequest;
import software.amazon.awssdk.aws.greengrass.model.ReleaseSharedPropertyResponse;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandler;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandlerContext;
import software.amazon.awssdk.eventstreamrpc.OperationModelContext;
import software.amazon.awssdk.eventstreamrpc.model.EventStreamJsonMessage;

public abstract class GeneratedAbstractReleaseSharedPropertyOperationHandler extends OperationContinuationHandler<ReleaseSharedPropertyRequest, ReleaseSharedPropertyResponse, EventStreamJsonMessage, EventStreamJsonMessage> {
  protected GeneratedAbstractReleaseSharedPropertyOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  public OperationModelContext<ReleaseSharedPropertyRequest, ReleaseSharedPropertyResponse, EventStreamJsonMessage, EventStreamJsonMessage> getOperationModelContext(
      ) {
    return GreengrassCoreIPCServiceModel.getReleaseSharedPropertyModelContext();
  }
}
