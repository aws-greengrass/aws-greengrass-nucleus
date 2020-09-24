package generated.software.amazon.awssdk.iot.greengrass;

import java.lang.Class;
import java.lang.Override;
import java.lang.String;
import generated.software.amazon.awssdk.iot.greengrass.model.ConfigurationUpdateEvents;
import generated.software.amazon.awssdk.iot.greengrass.model.SubscribeToConfigurationUpdateRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.SubscribeToConfigurationUpdateResponse;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;
import software.amazon.eventstream.iot.server.OperationContinuationHandler;
import software.amazon.eventstream.iot.server.OperationContinuationHandlerContext;

public abstract class GeneratedAbstractSubscribeToConfigurationUpdateOperationHandler extends OperationContinuationHandler<SubscribeToConfigurationUpdateRequest, SubscribeToConfigurationUpdateResponse, EventStreamableJsonMessage, ConfigurationUpdateEvents> {
  protected GeneratedAbstractSubscribeToConfigurationUpdateOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  protected final Class<SubscribeToConfigurationUpdateRequest> getRequestClass() {
    return generated.software.amazon.awssdk.iot.greengrass.model.SubscribeToConfigurationUpdateRequest.class;
  }

  @Override
  protected final Class<EventStreamableJsonMessage> getStreamingRequestClass() {
    return software.amazon.eventstream.iot.EventStreamableJsonMessage.class;
  }

  @Override
  protected final Class<SubscribeToConfigurationUpdateResponse> getResponseClass() {
    return generated.software.amazon.awssdk.iot.greengrass.model.SubscribeToConfigurationUpdateResponse.class;
  }

  @Override
  protected final Class<ConfigurationUpdateEvents> getStreamingResponseClass() {
    return generated.software.amazon.awssdk.iot.greengrass.model.ConfigurationUpdateEvents.class;
  }

  @Override
  protected final String getOperationName() {
    return GreengrassCoreIPCService.SUBSCRIBE_TO_CONFIGURATION_UPDATE;
  }

  @Override
  protected final boolean isStreamingOperation() {
    return true;
  }
}
