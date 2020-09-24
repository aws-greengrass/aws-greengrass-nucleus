package generated.software.amazon.awssdk.iot.greengrass;

import java.lang.Class;
import java.lang.Override;
import java.lang.String;
import generated.software.amazon.awssdk.iot.greengrass.model.StopComponentRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.StopComponentResponse;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;
import software.amazon.eventstream.iot.server.OperationContinuationHandler;
import software.amazon.eventstream.iot.server.OperationContinuationHandlerContext;

public abstract class GeneratedAbstractStopComponentOperationHandler extends OperationContinuationHandler<StopComponentRequest, StopComponentResponse, EventStreamableJsonMessage, EventStreamableJsonMessage> {
  protected GeneratedAbstractStopComponentOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  protected final Class<StopComponentRequest> getRequestClass() {
    return generated.software.amazon.awssdk.iot.greengrass.model.StopComponentRequest.class;
  }

  @Override
  protected final Class<EventStreamableJsonMessage> getStreamingRequestClass() {
    return software.amazon.eventstream.iot.EventStreamableJsonMessage.class;
  }

  @Override
  protected final Class<StopComponentResponse> getResponseClass() {
    return generated.software.amazon.awssdk.iot.greengrass.model.StopComponentResponse.class;
  }

  @Override
  protected final Class<EventStreamableJsonMessage> getStreamingResponseClass() {
    return software.amazon.eventstream.iot.EventStreamableJsonMessage.class;
  }

  @Override
  protected final String getOperationName() {
    return GreengrassCoreIPCService.STOP_COMPONENT;
  }

  @Override
  protected final boolean isStreamingOperation() {
    return false;
  }
}
