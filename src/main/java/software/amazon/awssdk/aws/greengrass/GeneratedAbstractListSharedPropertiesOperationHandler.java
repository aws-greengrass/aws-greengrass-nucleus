/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.awssdk.aws.greengrass;

import software.amazon.awssdk.aws.greengrass.model.ListSharedPropertiesRequest;
import software.amazon.awssdk.aws.greengrass.model.ListSharedPropertiesResponse;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandler;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandlerContext;
import software.amazon.awssdk.eventstreamrpc.OperationModelContext;
import software.amazon.awssdk.eventstreamrpc.model.EventStreamJsonMessage;

public abstract class GeneratedAbstractListSharedPropertiesOperationHandler extends OperationContinuationHandler<ListSharedPropertiesRequest, ListSharedPropertiesResponse, EventStreamJsonMessage, EventStreamJsonMessage> {
  protected GeneratedAbstractListSharedPropertiesOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  public OperationModelContext<ListSharedPropertiesRequest, ListSharedPropertiesResponse, EventStreamJsonMessage, EventStreamJsonMessage> getOperationModelContext(
      ) {
    return GreengrassCoreIPCServiceModel.getListSharedPropertiesModelContext();
  }
}
