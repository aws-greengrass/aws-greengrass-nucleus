package generated.software.amazon.awssdk.iot.greengrass;

import java.lang.Class;
import java.lang.Override;
import java.lang.String;
import java.util.Optional;
import generated.software.amazon.awssdk.iot.greengrass.model.SubscribeToValidateConfigurationUpdatesRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.SubscribeToValidateConfigurationUpdatesResponse;
import software.amazon.eventstream.iot.EventStreamRPCServiceModel;
import software.amazon.eventstream.iot.OperationModelContext;
import software.amazon.eventstream.iot.model.EventStreamJsonMessage;

public class SubscribeToValidateConfigurationUpdatesOperationContext implements OperationModelContext<SubscribeToValidateConfigurationUpdatesRequest, SubscribeToValidateConfigurationUpdatesResponse, EventStreamJsonMessage, EventStreamJsonMessage> {
  @Override
  public EventStreamRPCServiceModel getServiceModel() {
    return GreengrassCoreIPCServiceModel.getInstance();
  }

  @Override
  public String getOperationName() {
    return GreengrassCoreIPCServiceModel.SUBSCRIBE_TO_VALIDATE_CONFIGURATION_UPDATES;
  }

  @Override
  public Class<SubscribeToValidateConfigurationUpdatesRequest> getRequestTypeClass() {
    return SubscribeToValidateConfigurationUpdatesRequest.class;
  }

  @Override
  public Class<SubscribeToValidateConfigurationUpdatesResponse> getResponseTypeClass() {
    return SubscribeToValidateConfigurationUpdatesResponse.class;
  }

  @Override
  public String getRequestApplicationModelType() {
    return SubscribeToValidateConfigurationUpdatesRequest.APPLICATION_MODEL_TYPE;
  }

  @Override
  public String getResponseApplicationModelType() {
    return SubscribeToValidateConfigurationUpdatesResponse.APPLICATION_MODEL_TYPE;
  }

  @Override
  public Optional<Class<EventStreamJsonMessage>> getStreamingRequestTypeClass() {
    return Optional.empty();
  }

  @Override
  public Optional<Class<EventStreamJsonMessage>> getStreamingResponseTypeClass() {
    return Optional.empty();
  }

  public Optional<String> getStreamingRequestApplicationModelType() {
    return Optional.empty();
  }

  @Override
  public Optional<String> getStreamingResponseApplicationModelType() {
    return Optional.empty();
  }
}
