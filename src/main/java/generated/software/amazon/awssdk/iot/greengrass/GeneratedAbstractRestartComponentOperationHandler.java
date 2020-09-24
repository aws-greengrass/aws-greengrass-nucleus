package generated.software.amazon.awssdk.iot.greengrass;

import java.lang.Class;
import java.lang.Override;
import java.lang.String;
import generated.software.amazon.awssdk.iot.greengrass.model.RestartComponentRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.RestartComponentResponse;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;
import software.amazon.eventstream.iot.server.OperationContinuationHandler;
import software.amazon.eventstream.iot.server.OperationContinuationHandlerContext;

public abstract class GeneratedAbstractRestartComponentOperationHandler extends OperationContinuationHandler<RestartComponentRequest, RestartComponentResponse, EventStreamableJsonMessage, EventStreamableJsonMessage> {
  protected GeneratedAbstractRestartComponentOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  protected final Class<RestartComponentRequest> getRequestClass() {
    return generated.software.amazon.awssdk.iot.greengrass.model.RestartComponentRequest.class;
  }

  @Override
  protected final Class<EventStreamableJsonMessage> getStreamingRequestClass() {
    return software.amazon.eventstream.iot.EventStreamableJsonMessage.class;
  }

  @Override
  protected final Class<RestartComponentResponse> getResponseClass() {
    return generated.software.amazon.awssdk.iot.greengrass.model.RestartComponentResponse.class;
  }

  @Override
  protected final Class<EventStreamableJsonMessage> getStreamingResponseClass() {
    return software.amazon.eventstream.iot.EventStreamableJsonMessage.class;
  }

  @Override
  protected final String getOperationName() {
    return GreengrassCoreIPCService.RESTART_COMPONENT;
  }

  @Override
  protected final boolean isStreamingOperation() {
    return false;
  }
}
