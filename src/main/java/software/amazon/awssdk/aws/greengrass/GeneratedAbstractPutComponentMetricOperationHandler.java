/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.awssdk.aws.greengrass;

import java.lang.Override;
import software.amazon.awssdk.aws.greengrass.model.PutComponentMetricRequest;
import software.amazon.awssdk.aws.greengrass.model.PutComponentMetricResponse;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandler;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandlerContext;
import software.amazon.awssdk.eventstreamrpc.OperationModelContext;
import software.amazon.awssdk.eventstreamrpc.model.EventStreamJsonMessage;

public abstract class GeneratedAbstractPutComponentMetricOperationHandler extends OperationContinuationHandler<PutComponentMetricRequest, PutComponentMetricResponse, EventStreamJsonMessage, EventStreamJsonMessage> {
  protected GeneratedAbstractPutComponentMetricOperationHandler(
          OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  public OperationModelContext<PutComponentMetricRequest, PutComponentMetricResponse, EventStreamJsonMessage, EventStreamJsonMessage> getOperationModelContext(
  ) {
    return GreengrassCoreIPCServiceModel.getPutComponentMetricModelContext();
  }
}
