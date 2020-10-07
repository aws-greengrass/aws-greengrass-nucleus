package generated.software.amazon.awssdk.iot.greengrass;

import java.lang.Class;
import java.lang.Override;
import java.lang.String;

import generated.software.amazon.awssdk.iot.greengrass.model.GetSecretValueRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.GetSecretValueResponse;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;
import software.amazon.eventstream.iot.server.OperationContinuationHandler;
import software.amazon.eventstream.iot.server.OperationContinuationHandlerContext;

public abstract class GeneratedAbstractGetSecretValueOperationHandler extends OperationContinuationHandler<GetSecretValueRequest, GetSecretValueResponse, EventStreamableJsonMessage, EventStreamableJsonMessage> {
  protected GeneratedAbstractGetSecretValueOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  protected final Class<GetSecretValueRequest> getRequestClass() {
    return GetSecretValueRequest.class;
  }

  @Override
  protected final Class<EventStreamableJsonMessage> getStreamingRequestClass() {
    return software.amazon.eventstream.iot.EventStreamableJsonMessage.class;
  }

  @Override
  protected final Class<GetSecretValueResponse> getResponseClass() {
    return GetSecretValueResponse.class;
  }

  @Override
  protected final Class<EventStreamableJsonMessage> getStreamingResponseClass() {
    return software.amazon.eventstream.iot.EventStreamableJsonMessage.class;
  }

  @Override
  protected final String getOperationName() {
    return GreengrassCoreIPCService.GET_SECRET_VALUE;
  }

  @Override
  protected final boolean isStreamingOperation() {
    return false;
  }
}
