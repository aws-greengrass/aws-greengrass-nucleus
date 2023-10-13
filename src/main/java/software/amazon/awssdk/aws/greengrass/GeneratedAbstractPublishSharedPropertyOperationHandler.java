/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.awssdk.aws.greengrass;

import java.lang.Override;
import software.amazon.awssdk.aws.greengrass.model.PublishSharedPropertyRequest;
import software.amazon.awssdk.aws.greengrass.model.PublishSharedPropertyResponse;
import software.amazon.awssdk.aws.greengrass.model.SharedPropertyChangeResponse;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandler;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandlerContext;
import software.amazon.awssdk.eventstreamrpc.OperationModelContext;
import software.amazon.awssdk.eventstreamrpc.model.EventStreamJsonMessage;

public abstract class GeneratedAbstractPublishSharedPropertyOperationHandler extends OperationContinuationHandler<PublishSharedPropertyRequest, PublishSharedPropertyResponse, EventStreamJsonMessage, SharedPropertyChangeResponse> {
  protected GeneratedAbstractPublishSharedPropertyOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  public OperationModelContext<PublishSharedPropertyRequest, PublishSharedPropertyResponse, EventStreamJsonMessage, SharedPropertyChangeResponse> getOperationModelContext(
      ) {
    return GreengrassCoreIPCServiceModel.getPublishSharedPropertyModelContext();
  }
}
