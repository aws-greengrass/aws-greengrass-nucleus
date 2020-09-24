package generated.software.amazon.awssdk.iot.greengrass;

import java.lang.Class;
import java.lang.Override;
import java.lang.String;
import generated.software.amazon.awssdk.iot.greengrass.model.UpdateConfigurationRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.UpdateConfigurationResponse;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;
import software.amazon.eventstream.iot.server.OperationContinuationHandler;
import software.amazon.eventstream.iot.server.OperationContinuationHandlerContext;

public abstract class GeneratedAbstractUpdateConfigurationOperationHandler extends OperationContinuationHandler<UpdateConfigurationRequest, UpdateConfigurationResponse, EventStreamableJsonMessage, EventStreamableJsonMessage> {
  protected GeneratedAbstractUpdateConfigurationOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  protected final Class<UpdateConfigurationRequest> getRequestClass() {
    return generated.software.amazon.awssdk.iot.greengrass.model.UpdateConfigurationRequest.class;
  }

  @Override
  protected final Class<EventStreamableJsonMessage> getStreamingRequestClass() {
    return software.amazon.eventstream.iot.EventStreamableJsonMessage.class;
  }

  @Override
  protected final Class<UpdateConfigurationResponse> getResponseClass() {
    return generated.software.amazon.awssdk.iot.greengrass.model.UpdateConfigurationResponse.class;
  }

  @Override
  protected final Class<EventStreamableJsonMessage> getStreamingResponseClass() {
    return software.amazon.eventstream.iot.EventStreamableJsonMessage.class;
  }

  @Override
  protected final String getOperationName() {
    return GreengrassCoreIPCService.UPDATE_CONFIGURATION;
  }

  @Override
  protected final boolean isStreamingOperation() {
    return false;
  }
}
