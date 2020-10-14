package generated.software.amazon.awssdk.iot.greengrass;

import java.lang.Class;
import java.lang.Override;
import java.lang.String;
import java.util.Optional;

import generated.software.amazon.awssdk.iot.greengrass.model.ComponentUpdatePolicyEvents;
import generated.software.amazon.awssdk.iot.greengrass.model.SubscribeToComponentUpdatesRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.SubscribeToComponentUpdatesResponse;
import software.amazon.eventstream.iot.EventStreamRPCServiceModel;
import software.amazon.eventstream.iot.OperationModelContext;
import software.amazon.eventstream.iot.model.EventStreamJsonMessage;

public class SubscribeToComponentUpdatesOperationContext implements OperationModelContext<SubscribeToComponentUpdatesRequest, SubscribeToComponentUpdatesResponse, EventStreamJsonMessage, ComponentUpdatePolicyEvents> {
  @Override
  public EventStreamRPCServiceModel getServiceModel() {
    return GreengrassCoreIPCServiceModel.getInstance();
  }

  @Override
  public String getOperationName() {
    return GreengrassCoreIPCServiceModel.SUBSCRIBE_TO_COMPONENT_UPDATES;
  }

  @Override
  public Class<SubscribeToComponentUpdatesRequest> getRequestTypeClass() {
    return SubscribeToComponentUpdatesRequest.class;
  }

  @Override
  public Class<SubscribeToComponentUpdatesResponse> getResponseTypeClass() {
    return SubscribeToComponentUpdatesResponse.class;
  }

  @Override
  public String getRequestApplicationModelType() {
    return SubscribeToComponentUpdatesRequest.APPLICATION_MODEL_TYPE;
  }

  @Override
  public String getResponseApplicationModelType() {
    return SubscribeToComponentUpdatesResponse.APPLICATION_MODEL_TYPE;
  }

  @Override
  public Optional<Class<EventStreamJsonMessage>> getStreamingRequestTypeClass() {
    return Optional.empty();
  }

  @Override
  public Optional<Class<ComponentUpdatePolicyEvents>> getStreamingResponseTypeClass() {
    return Optional.of(ComponentUpdatePolicyEvents.class);
  }

  public Optional<String> getStreamingRequestApplicationModelType() {
    return Optional.empty();
  }

  @Override
  public Optional<String> getStreamingResponseApplicationModelType() {
    return Optional.of(ComponentUpdatePolicyEvents.APPLICATION_MODEL_TYPE);
  }
}
