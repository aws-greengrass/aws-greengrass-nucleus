package generated.software.amazon.awssdk.iot.greengrass;

import java.lang.Class;
import java.lang.Override;
import java.lang.String;
import generated.software.amazon.awssdk.iot.greengrass.model.SubscribeToValidateConfigurationUpdatesRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.SubscribeToValidateConfigurationUpdatesResponse;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;
import software.amazon.eventstream.iot.server.OperationContinuationHandler;
import software.amazon.eventstream.iot.server.OperationContinuationHandlerContext;

public abstract class GeneratedAbstractSubscribeToValidateConfigurationUpdatesOperationHandler extends OperationContinuationHandler<SubscribeToValidateConfigurationUpdatesRequest, SubscribeToValidateConfigurationUpdatesResponse, EventStreamableJsonMessage, EventStreamableJsonMessage> {
  protected GeneratedAbstractSubscribeToValidateConfigurationUpdatesOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  protected final Class<SubscribeToValidateConfigurationUpdatesRequest> getRequestClass() {
    return generated.software.amazon.awssdk.iot.greengrass.model.SubscribeToValidateConfigurationUpdatesRequest.class;
  }

  @Override
  protected final Class<EventStreamableJsonMessage> getStreamingRequestClass() {
    return software.amazon.eventstream.iot.EventStreamableJsonMessage.class;
  }

  @Override
  protected final Class<SubscribeToValidateConfigurationUpdatesResponse> getResponseClass() {
    return generated.software.amazon.awssdk.iot.greengrass.model.SubscribeToValidateConfigurationUpdatesResponse.class;
  }

  @Override
  protected final Class<EventStreamableJsonMessage> getStreamingResponseClass() {
    return software.amazon.eventstream.iot.EventStreamableJsonMessage.class;
  }

  @Override
  protected final String getOperationName() {
    return GreengrassCoreIPCService.SUBSCRIBE_TO_VALIDATE_CONFIGURATION_UPDATES;
  }

  @Override
  protected final boolean isStreamingOperation() {
    return false;
  }
}
