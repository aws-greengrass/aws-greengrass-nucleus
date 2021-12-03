/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.awssdk.aws.greengrass;

import java.lang.Override;
import software.amazon.awssdk.aws.greengrass.model.QuerySharedPropertiesRequest;
import software.amazon.awssdk.aws.greengrass.model.QuerySharedPropertiesResponse;
import software.amazon.awssdk.aws.greengrass.model.QuerySharedPropertiesResponseMessage;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandler;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandlerContext;
import software.amazon.awssdk.eventstreamrpc.OperationModelContext;
import software.amazon.awssdk.eventstreamrpc.model.EventStreamJsonMessage;

public abstract class GeneratedAbstractQuerySharedPropertiesOperationHandler extends OperationContinuationHandler<QuerySharedPropertiesRequest, QuerySharedPropertiesResponse, EventStreamJsonMessage, QuerySharedPropertiesResponseMessage> {
  protected GeneratedAbstractQuerySharedPropertiesOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  public OperationModelContext<QuerySharedPropertiesRequest, QuerySharedPropertiesResponse, EventStreamJsonMessage, QuerySharedPropertiesResponseMessage> getOperationModelContext(
      ) {
    return GreengrassCoreIPCServiceModel.getQuerySharedPropertiesModelContext();
  }
}
