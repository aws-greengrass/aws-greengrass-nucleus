package generated.software.amazon.awssdk.iot.greengrass;

import java.lang.Class;
import java.lang.Override;
import java.lang.String;

import generated.software.amazon.awssdk.iot.greengrass.model.ComponentUpdatePolicyEvents;
import generated.software.amazon.awssdk.iot.greengrass.model.SubscribeToComponentUpdatesRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.SubscribeToComponentUpdatesResponse;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;
import software.amazon.eventstream.iot.server.OperationContinuationHandler;
import software.amazon.eventstream.iot.server.OperationContinuationHandlerContext;

public abstract class GeneratedAbstractSubscribeToComponentUpdatesOperationHandler extends OperationContinuationHandler<SubscribeToComponentUpdatesRequest, SubscribeToComponentUpdatesResponse, EventStreamableJsonMessage, ComponentUpdatePolicyEvents> {
  protected GeneratedAbstractSubscribeToComponentUpdatesOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  protected final Class<SubscribeToComponentUpdatesRequest> getRequestClass() {
    return SubscribeToComponentUpdatesRequest.class;
  }

  @Override
  protected final Class<EventStreamableJsonMessage> getStreamingRequestClass() {
    return software.amazon.eventstream.iot.EventStreamableJsonMessage.class;
  }

  @Override
  protected final Class<SubscribeToComponentUpdatesResponse> getResponseClass() {
    return SubscribeToComponentUpdatesResponse.class;
  }

  @Override
  protected final Class<ComponentUpdatePolicyEvents> getStreamingResponseClass() {
    return ComponentUpdatePolicyEvents.class;
  }

  @Override
  protected final String getOperationName() {
    return GreengrassCoreIPCService.SUBSCRIBE_TO_COMPONENT_UPDATES;
  }

  @Override
  protected final boolean isStreamingOperation() {
    return true;
  }
}
