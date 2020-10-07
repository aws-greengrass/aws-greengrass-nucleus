package generated.software.amazon.awssdk.iot.greengrass;

import java.lang.Class;
import java.lang.Override;
import java.lang.String;

import generated.software.amazon.awssdk.iot.greengrass.model.DeferComponentUpdateRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.DeferComponentUpdateResponse;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;
import software.amazon.eventstream.iot.server.OperationContinuationHandler;
import software.amazon.eventstream.iot.server.OperationContinuationHandlerContext;

public abstract class GeneratedAbstractDeferComponentUpdateOperationHandler extends OperationContinuationHandler<DeferComponentUpdateRequest, DeferComponentUpdateResponse, EventStreamableJsonMessage, EventStreamableJsonMessage> {
  protected GeneratedAbstractDeferComponentUpdateOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  protected final Class<DeferComponentUpdateRequest> getRequestClass() {
    return DeferComponentUpdateRequest.class;
  }

  @Override
  protected final Class<EventStreamableJsonMessage> getStreamingRequestClass() {
    return software.amazon.eventstream.iot.EventStreamableJsonMessage.class;
  }

  @Override
  protected final Class<DeferComponentUpdateResponse> getResponseClass() {
    return DeferComponentUpdateResponse.class;
  }

  @Override
  protected final Class<EventStreamableJsonMessage> getStreamingResponseClass() {
    return software.amazon.eventstream.iot.EventStreamableJsonMessage.class;
  }

  @Override
  protected final String getOperationName() {
    return GreengrassCoreIPCService.DEFER_COMPONENT_UPDATE;
  }

  @Override
  protected final boolean isStreamingOperation() {
    return false;
  }
}
