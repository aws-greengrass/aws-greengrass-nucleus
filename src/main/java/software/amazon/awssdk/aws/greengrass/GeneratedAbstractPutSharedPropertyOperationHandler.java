/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.awssdk.aws.greengrass;

import software.amazon.awssdk.aws.greengrass.model.PutSharedPropertyRequest;
import software.amazon.awssdk.aws.greengrass.model.PutSharedPropertyResponse;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandler;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandlerContext;
import software.amazon.awssdk.eventstreamrpc.OperationModelContext;
import software.amazon.awssdk.eventstreamrpc.model.EventStreamJsonMessage;

public abstract class GeneratedAbstractPutSharedPropertyOperationHandler extends OperationContinuationHandler<PutSharedPropertyRequest, PutSharedPropertyResponse, EventStreamJsonMessage, EventStreamJsonMessage> {
  protected GeneratedAbstractPutSharedPropertyOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  public OperationModelContext<PutSharedPropertyRequest, PutSharedPropertyResponse, EventStreamJsonMessage, EventStreamJsonMessage> getOperationModelContext(
      ) {
    return GreengrassCoreIPCServiceModel.getPutSharedPropertyModelContext();
  }
}
