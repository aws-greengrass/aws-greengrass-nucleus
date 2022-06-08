/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.awssdk.aws.greengrass;

import java.lang.Override;
import software.amazon.awssdk.aws.greengrass.model.EmitTelemetryMetricsRequest;
import software.amazon.awssdk.aws.greengrass.model.EmitTelemetryMetricsResponse;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandler;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandlerContext;
import software.amazon.awssdk.eventstreamrpc.OperationModelContext;
import software.amazon.awssdk.eventstreamrpc.model.EventStreamJsonMessage;

public abstract class GeneratedAbstractEmitTelemetryMetricsOperationHandler extends OperationContinuationHandler<EmitTelemetryMetricsRequest, EmitTelemetryMetricsResponse, EventStreamJsonMessage, EventStreamJsonMessage> {
  protected GeneratedAbstractEmitTelemetryMetricsOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  public OperationModelContext<EmitTelemetryMetricsRequest, EmitTelemetryMetricsResponse, EventStreamJsonMessage, EventStreamJsonMessage> getOperationModelContext(
      ) {
    return GreengrassCoreIPCServiceModel.getEmitTelemetryMetricsModelContext();
  }
}
