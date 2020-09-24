package generated.software.amazon.awssdk.iot.greengrass;

import java.lang.Class;
import java.lang.Override;
import java.lang.String;
import generated.software.amazon.awssdk.iot.greengrass.model.ListLocalDeploymentResponse;
import generated.software.amazon.awssdk.iot.greengrass.model.ListLocalDeploymentsRequest;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;
import software.amazon.eventstream.iot.server.OperationContinuationHandler;
import software.amazon.eventstream.iot.server.OperationContinuationHandlerContext;

public abstract class GeneratedAbstractListLocalDeploymentsOperationHandler extends OperationContinuationHandler<ListLocalDeploymentsRequest, ListLocalDeploymentResponse, EventStreamableJsonMessage, EventStreamableJsonMessage> {
  protected GeneratedAbstractListLocalDeploymentsOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  protected final Class<ListLocalDeploymentsRequest> getRequestClass() {
    return generated.software.amazon.awssdk.iot.greengrass.model.ListLocalDeploymentsRequest.class;
  }

  @Override
  protected final Class<EventStreamableJsonMessage> getStreamingRequestClass() {
    return software.amazon.eventstream.iot.EventStreamableJsonMessage.class;
  }

  @Override
  protected final Class<ListLocalDeploymentResponse> getResponseClass() {
    return generated.software.amazon.awssdk.iot.greengrass.model.ListLocalDeploymentResponse.class;
  }

  @Override
  protected final Class<EventStreamableJsonMessage> getStreamingResponseClass() {
    return software.amazon.eventstream.iot.EventStreamableJsonMessage.class;
  }

  @Override
  protected final String getOperationName() {
    return GreengrassCoreIPCService.LIST_LOCAL_DEPLOYMENTS;
  }

  @Override
  protected final boolean isStreamingOperation() {
    return false;
  }
}
