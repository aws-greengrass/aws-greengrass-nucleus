package generated.software.amazon.awssdk.iot.greengrass;

import java.lang.Class;
import java.lang.Override;
import java.lang.String;
import generated.software.amazon.awssdk.iot.greengrass.model.UnsubscribeFromIoTCoreRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.UnsubscribeFromIoTCoreResponse;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;
import software.amazon.eventstream.iot.server.OperationContinuationHandler;
import software.amazon.eventstream.iot.server.OperationContinuationHandlerContext;

public abstract class GeneratedAbstractUnsubscribeFromIoTCoreOperationHandler extends OperationContinuationHandler<UnsubscribeFromIoTCoreRequest, UnsubscribeFromIoTCoreResponse, EventStreamableJsonMessage, EventStreamableJsonMessage> {
  protected GeneratedAbstractUnsubscribeFromIoTCoreOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  protected final Class<UnsubscribeFromIoTCoreRequest> getRequestClass() {
    return generated.software.amazon.awssdk.iot.greengrass.model.UnsubscribeFromIoTCoreRequest.class;
  }

  @Override
  protected final Class<EventStreamableJsonMessage> getStreamingRequestClass() {
    return software.amazon.eventstream.iot.EventStreamableJsonMessage.class;
  }

  @Override
  protected final Class<UnsubscribeFromIoTCoreResponse> getResponseClass() {
    return generated.software.amazon.awssdk.iot.greengrass.model.UnsubscribeFromIoTCoreResponse.class;
  }

  @Override
  protected final Class<EventStreamableJsonMessage> getStreamingResponseClass() {
    return software.amazon.eventstream.iot.EventStreamableJsonMessage.class;
  }

  @Override
  protected final String getOperationName() {
    return GreengrassCoreIPCService.UNSUBSCRIBE_FROM_IOT_CORE;
  }

  @Override
  protected final boolean isStreamingOperation() {
    return false;
  }
}
