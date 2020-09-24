package generated.software.amazon.awssdk.iot.greengrass;

import java.lang.Class;
import java.lang.Override;
import java.lang.String;
import generated.software.amazon.awssdk.iot.greengrass.model.ValidateAuthorizationTokenRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.ValidateAuthorizationTokenResponse;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;
import software.amazon.eventstream.iot.server.OperationContinuationHandler;
import software.amazon.eventstream.iot.server.OperationContinuationHandlerContext;

public abstract class GeneratedAbstractValidateAuthorizationTokenOperationHandler extends OperationContinuationHandler<ValidateAuthorizationTokenRequest, ValidateAuthorizationTokenResponse, EventStreamableJsonMessage, EventStreamableJsonMessage> {
  protected GeneratedAbstractValidateAuthorizationTokenOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  protected final Class<ValidateAuthorizationTokenRequest> getRequestClass() {
    return generated.software.amazon.awssdk.iot.greengrass.model.ValidateAuthorizationTokenRequest.class;
  }

  @Override
  protected final Class<EventStreamableJsonMessage> getStreamingRequestClass() {
    return software.amazon.eventstream.iot.EventStreamableJsonMessage.class;
  }

  @Override
  protected final Class<ValidateAuthorizationTokenResponse> getResponseClass() {
    return generated.software.amazon.awssdk.iot.greengrass.model.ValidateAuthorizationTokenResponse.class;
  }

  @Override
  protected final Class<EventStreamableJsonMessage> getStreamingResponseClass() {
    return software.amazon.eventstream.iot.EventStreamableJsonMessage.class;
  }

  @Override
  protected final String getOperationName() {
    return GreengrassCoreIPCService.VALIDATE_AUTHORIZATION_TOKEN;
  }

  @Override
  protected final boolean isStreamingOperation() {
    return false;
  }
}
