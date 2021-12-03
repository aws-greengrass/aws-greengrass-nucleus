/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.awssdk.aws.greengrass;

import java.lang.Override;
import software.amazon.awssdk.aws.greengrass.model.RetrieveSharedPropertyConsistentResponse;
import software.amazon.awssdk.aws.greengrass.model.RetrieveSharedPropertyRequest;
import software.amazon.awssdk.aws.greengrass.model.RetrieveSharedPropertyResponse;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandler;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandlerContext;
import software.amazon.awssdk.eventstreamrpc.OperationModelContext;
import software.amazon.awssdk.eventstreamrpc.model.EventStreamJsonMessage;

public abstract class GeneratedAbstractRetrieveSharedPropertyOperationHandler extends OperationContinuationHandler<RetrieveSharedPropertyRequest, RetrieveSharedPropertyResponse, EventStreamJsonMessage, RetrieveSharedPropertyConsistentResponse> {
  protected GeneratedAbstractRetrieveSharedPropertyOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  public OperationModelContext<RetrieveSharedPropertyRequest, RetrieveSharedPropertyResponse, EventStreamJsonMessage, RetrieveSharedPropertyConsistentResponse> getOperationModelContext(
      ) {
    return GreengrassCoreIPCServiceModel.getRetrieveSharedPropertyModelContext();
  }
}
