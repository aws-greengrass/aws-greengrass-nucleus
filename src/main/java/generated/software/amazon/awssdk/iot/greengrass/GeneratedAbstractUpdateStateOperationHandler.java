package generated.software.amazon.awssdk.iot.greengrass;

import java.lang.Class;
import java.lang.Override;
import java.lang.String;

import generated.software.amazon.awssdk.iot.greengrass.model.UpdateStateRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.UpdateStateResponse;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;
import software.amazon.eventstream.iot.server.OperationContinuationHandler;
import software.amazon.eventstream.iot.server.OperationContinuationHandlerContext;

public abstract class GeneratedAbstractUpdateStateOperationHandler extends OperationContinuationHandler<UpdateStateRequest, UpdateStateResponse, EventStreamableJsonMessage, EventStreamableJsonMessage> {
  protected GeneratedAbstractUpdateStateOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  protected final Class<UpdateStateRequest> getRequestClass() {
    return UpdateStateRequest.class;
  }

  @Override
  protected final Class<EventStreamableJsonMessage> getStreamingRequestClass() {
    return software.amazon.eventstream.iot.EventStreamableJsonMessage.class;
  }

  @Override
  protected final Class<UpdateStateResponse> getResponseClass() {
    return UpdateStateResponse.class;
  }

  @Override
  protected final Class<EventStreamableJsonMessage> getStreamingResponseClass() {
    return software.amazon.eventstream.iot.EventStreamableJsonMessage.class;
  }

  @Override
  protected final String getOperationName() {
    return GreengrassCoreIPCService.UPDATE_STATE;
  }

  @Override
  protected final boolean isStreamingOperation() {
    return false;
  }
}
