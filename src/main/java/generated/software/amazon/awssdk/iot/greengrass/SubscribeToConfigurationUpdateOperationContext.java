package generated.software.amazon.awssdk.iot.greengrass;

import java.lang.Class;
import java.lang.Override;
import java.lang.String;
import java.util.Optional;

import generated.software.amazon.awssdk.iot.greengrass.model.ConfigurationUpdateEvents;
import generated.software.amazon.awssdk.iot.greengrass.model.SubscribeToConfigurationUpdateRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.SubscribeToConfigurationUpdateResponse;
import software.amazon.eventstream.iot.EventStreamRPCServiceModel;
import software.amazon.eventstream.iot.OperationModelContext;
import software.amazon.eventstream.iot.model.EventStreamJsonMessage;

public class SubscribeToConfigurationUpdateOperationContext implements OperationModelContext<SubscribeToConfigurationUpdateRequest, SubscribeToConfigurationUpdateResponse, EventStreamJsonMessage, ConfigurationUpdateEvents> {
  @Override
  public EventStreamRPCServiceModel getServiceModel() {
    return GreengrassCoreIPCServiceModel.getInstance();
  }

  @Override
  public String getOperationName() {
    return GreengrassCoreIPCServiceModel.SUBSCRIBE_TO_CONFIGURATION_UPDATE;
  }

  @Override
  public Class<SubscribeToConfigurationUpdateRequest> getRequestTypeClass() {
    return SubscribeToConfigurationUpdateRequest.class;
  }

  @Override
  public Class<SubscribeToConfigurationUpdateResponse> getResponseTypeClass() {
    return SubscribeToConfigurationUpdateResponse.class;
  }

  @Override
  public String getRequestApplicationModelType() {
    return SubscribeToConfigurationUpdateRequest.APPLICATION_MODEL_TYPE;
  }

  @Override
  public String getResponseApplicationModelType() {
    return SubscribeToConfigurationUpdateResponse.APPLICATION_MODEL_TYPE;
  }

  @Override
  public Optional<Class<EventStreamJsonMessage>> getStreamingRequestTypeClass() {
    return Optional.empty();
  }

  @Override
  public Optional<Class<ConfigurationUpdateEvents>> getStreamingResponseTypeClass() {
    return Optional.of(ConfigurationUpdateEvents.class);
  }

  public Optional<String> getStreamingRequestApplicationModelType() {
    return Optional.empty();
  }

  @Override
  public Optional<String> getStreamingResponseApplicationModelType() {
    return Optional.of(ConfigurationUpdateEvents.APPLICATION_MODEL_TYPE);
  }
}
