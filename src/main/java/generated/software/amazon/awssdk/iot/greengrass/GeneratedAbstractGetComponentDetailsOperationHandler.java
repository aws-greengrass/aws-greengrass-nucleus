package generated.software.amazon.awssdk.iot.greengrass;

import java.lang.Class;
import java.lang.Override;
import java.lang.String;
import generated.software.amazon.awssdk.iot.greengrass.model.GetComponentDetailsRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.GetComponentDetailsResponse;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;
import software.amazon.eventstream.iot.server.OperationContinuationHandler;
import software.amazon.eventstream.iot.server.OperationContinuationHandlerContext;

public abstract class GeneratedAbstractGetComponentDetailsOperationHandler extends OperationContinuationHandler<GetComponentDetailsRequest, GetComponentDetailsResponse, EventStreamableJsonMessage, EventStreamableJsonMessage> {
  protected GeneratedAbstractGetComponentDetailsOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  protected final Class<GetComponentDetailsRequest> getRequestClass() {
    return generated.software.amazon.awssdk.iot.greengrass.model.GetComponentDetailsRequest.class;
  }

  @Override
  protected final Class<EventStreamableJsonMessage> getStreamingRequestClass() {
    return software.amazon.eventstream.iot.EventStreamableJsonMessage.class;
  }

  @Override
  protected final Class<GetComponentDetailsResponse> getResponseClass() {
    return generated.software.amazon.awssdk.iot.greengrass.model.GetComponentDetailsResponse.class;
  }

  @Override
  protected final Class<EventStreamableJsonMessage> getStreamingResponseClass() {
    return software.amazon.eventstream.iot.EventStreamableJsonMessage.class;
  }

  @Override
  protected final String getOperationName() {
    return GreengrassCoreIPCService.GET_COMPONENT_DETAILS;
  }

  @Override
  protected final boolean isStreamingOperation() {
    return false;
  }
}
