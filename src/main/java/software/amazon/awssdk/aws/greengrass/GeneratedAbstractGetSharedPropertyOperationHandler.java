/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.awssdk.aws.greengrass;

import software.amazon.awssdk.aws.greengrass.model.GetSharedPropertyRequest;
import software.amazon.awssdk.aws.greengrass.model.GetSharedPropertyResponse;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandler;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandlerContext;
import software.amazon.awssdk.eventstreamrpc.OperationModelContext;
import software.amazon.awssdk.eventstreamrpc.model.EventStreamJsonMessage;

public abstract class GeneratedAbstractGetSharedPropertyOperationHandler extends OperationContinuationHandler<GetSharedPropertyRequest, GetSharedPropertyResponse, EventStreamJsonMessage, EventStreamJsonMessage> {
  protected GeneratedAbstractGetSharedPropertyOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  public OperationModelContext<GetSharedPropertyRequest, GetSharedPropertyResponse, EventStreamJsonMessage, EventStreamJsonMessage> getOperationModelContext(
      ) {
    return GreengrassCoreIPCServiceModel.getGetSharedPropertyModelContext();
  }
}
