package generated.software.amazon.awssdk.iot.greengrass;

import java.lang.Class;
import java.lang.Override;
import java.lang.String;

import generated.software.amazon.awssdk.iot.greengrass.model.ListLocalDeploymentsRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.ListLocalDeploymentsResponse;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;
import software.amazon.eventstream.iot.server.OperationContinuationHandler;
import software.amazon.eventstream.iot.server.OperationContinuationHandlerContext;

public abstract class GeneratedAbstractListLocalDeploymentsOperationHandler extends OperationContinuationHandler<ListLocalDeploymentsRequest, ListLocalDeploymentsResponse, EventStreamableJsonMessage, EventStreamableJsonMessage> {
  protected GeneratedAbstractListLocalDeploymentsOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  protected final Class<ListLocalDeploymentsRequest> getRequestClass() {
    return ListLocalDeploymentsRequest.class;
  }

  @Override
  protected final Class<EventStreamableJsonMessage> getStreamingRequestClass() {
    return software.amazon.eventstream.iot.EventStreamableJsonMessage.class;
  }

  @Override
  protected final Class<ListLocalDeploymentsResponse> getResponseClass() {
    return ListLocalDeploymentsResponse.class;
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
