package generated.software.amazon.awssdk.iot.greengrass;

import java.lang.Class;
import java.lang.Override;
import java.lang.String;

import generated.software.amazon.awssdk.iot.greengrass.model.GetConfigurationRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.GetConfigurationResponse;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;
import software.amazon.eventstream.iot.server.OperationContinuationHandler;
import software.amazon.eventstream.iot.server.OperationContinuationHandlerContext;

public abstract class GeneratedAbstractGetConfigurationOperationHandler extends OperationContinuationHandler<GetConfigurationRequest, GetConfigurationResponse, EventStreamableJsonMessage, EventStreamableJsonMessage> {
  protected GeneratedAbstractGetConfigurationOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  protected final Class<GetConfigurationRequest> getRequestClass() {
    return GetConfigurationRequest.class;
  }

  @Override
  protected final Class<EventStreamableJsonMessage> getStreamingRequestClass() {
    return software.amazon.eventstream.iot.EventStreamableJsonMessage.class;
  }

  @Override
  protected final Class<GetConfigurationResponse> getResponseClass() {
    return GetConfigurationResponse.class;
  }

  @Override
  protected final Class<EventStreamableJsonMessage> getStreamingResponseClass() {
    return software.amazon.eventstream.iot.EventStreamableJsonMessage.class;
  }

  @Override
  protected final String getOperationName() {
    return GreengrassCoreIPCService.GET_CONFIGURATION;
  }

  @Override
  protected final boolean isStreamingOperation() {
    return false;
  }
}
