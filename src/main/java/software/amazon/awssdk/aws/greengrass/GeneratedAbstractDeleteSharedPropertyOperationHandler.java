/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.awssdk.aws.greengrass;

import java.lang.Override;
import software.amazon.awssdk.aws.greengrass.model.DeleteSharedPropertyRequest;
import software.amazon.awssdk.aws.greengrass.model.DeleteSharedPropertyResponse;
import software.amazon.awssdk.aws.greengrass.model.SharedPropertyChangeResponse;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandler;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandlerContext;
import software.amazon.awssdk.eventstreamrpc.OperationModelContext;
import software.amazon.awssdk.eventstreamrpc.model.EventStreamJsonMessage;

public abstract class GeneratedAbstractDeleteSharedPropertyOperationHandler extends OperationContinuationHandler<DeleteSharedPropertyRequest, DeleteSharedPropertyResponse, EventStreamJsonMessage, SharedPropertyChangeResponse> {
  protected GeneratedAbstractDeleteSharedPropertyOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  public OperationModelContext<DeleteSharedPropertyRequest, DeleteSharedPropertyResponse, EventStreamJsonMessage, SharedPropertyChangeResponse> getOperationModelContext(
      ) {
    return GreengrassCoreIPCServiceModel.getDeleteSharedPropertyModelContext();
  }
}
