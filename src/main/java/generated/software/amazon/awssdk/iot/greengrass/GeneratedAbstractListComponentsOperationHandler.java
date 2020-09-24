package generated.software.amazon.awssdk.iot.greengrass;

import java.lang.Class;
import java.lang.Override;
import java.lang.String;
import generated.software.amazon.awssdk.iot.greengrass.model.ListComponentsRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.ListComponentsResponse;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;
import software.amazon.eventstream.iot.server.OperationContinuationHandler;
import software.amazon.eventstream.iot.server.OperationContinuationHandlerContext;

public abstract class GeneratedAbstractListComponentsOperationHandler extends OperationContinuationHandler<ListComponentsRequest, ListComponentsResponse, EventStreamableJsonMessage, EventStreamableJsonMessage> {
  protected GeneratedAbstractListComponentsOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  protected final Class<ListComponentsRequest> getRequestClass() {
    return generated.software.amazon.awssdk.iot.greengrass.model.ListComponentsRequest.class;
  }

  @Override
  protected final Class<EventStreamableJsonMessage> getStreamingRequestClass() {
    return software.amazon.eventstream.iot.EventStreamableJsonMessage.class;
  }

  @Override
  protected final Class<ListComponentsResponse> getResponseClass() {
    return generated.software.amazon.awssdk.iot.greengrass.model.ListComponentsResponse.class;
  }

  @Override
  protected final Class<EventStreamableJsonMessage> getStreamingResponseClass() {
    return software.amazon.eventstream.iot.EventStreamableJsonMessage.class;
  }

  @Override
  protected final String getOperationName() {
    return GreengrassCoreIPCService.LIST_COMPONENTS;
  }

  @Override
  protected final boolean isStreamingOperation() {
    return false;
  }
}
